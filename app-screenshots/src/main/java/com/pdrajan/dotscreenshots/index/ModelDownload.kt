package com.pdrajan.dotscreenshots.index

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pdrajan.dot.engine.ModelFile
import com.pdrajan.dot.media.DotLog
import com.pdrajan.dot.ml.FlorenceDescriber
import com.pdrajan.dotscreenshots.DotScreenshotsApp
import com.pdrajan.dotscreenshots.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Duration

/**
 * Gets the description model (Florence-2-base, about 215 MB) after install. The app downloads it
 * itself in a background job ([ModelDownloadWorker]): on Wi-Fi unless mobile data is allowed,
 * resuming where it stopped after an interruption, retrying on errors, and checking each file
 * against the size and sha256 in the shipped config before it's used.
 */
class ModelDownload(private val context: Context) {

    sealed interface State {
        data object Ready : State
        data object NotStarted : State
        data object Checking : State
        data class Downloading(val bytes: Long, val total: Long) : State
        data class Waiting(val bytes: Long, val total: Long, val reason: String) : State
        data class Failed(val reason: String) : State
    }

    private val files: List<ModelFile> by lazy { FlorenceDescriber.config(context, withTokens = false).files }
    private val revision: String by lazy { files.joinToString(",") { it.sha256.take(12) } }
    private val prefs = context.getSharedPreferences("model_download", Context.MODE_PRIVATE)
    private val work get() = WorkManager.getInstance(context)

    /** App-specific storage (removed with the app). */
    private val dir: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "florence")
    private val marker: File get() = File(dir, "verified.txt")

    val totalBytes: Long get() = files.sumOf { it.size }

    /** Where the checked model files are; null until all of them are downloaded and checked. */
    fun installedDir(): File? {
        if (!marker.exists() || runCatching { marker.readText() }.getOrNull() != revision) return null
        return dir.takeIf { files.all { f -> File(it, f.name).length() == f.size } }
    }

    val ready: Boolean get() = installedDir() != null

    /** Starts (or, when the network rule changed, restarts) the download. [mobileData]: also over mobile data. */
    @Synchronized
    fun start(mobileData: Boolean) {
        if (ready) return
        forgetSystemDownloads()
        val sameRule = prefs.getBoolean(KEY_MOBILE, false) == mobileData
        prefs.edit {
            putBoolean(KEY_MOBILE, mobileData)
            remove(KEY_ERROR)
        }
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (mobileData) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
            .build()
        work.enqueueUniqueWork(WORK, if (sameRule) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE, request)
        DotLog.i("model: download queued" + if (mobileData) " (mobile data allowed)" else " (Wi-Fi)")
    }

    /** What the download is doing, for Settings. Blocking (asks WorkManager); call off the main thread. */
    fun state(): State {
        if (ready) return State.Ready
        val total = totalBytes
        val bytes = downloadedBytes()
        val info = runCatching { work.getWorkInfosForUniqueWork(WORK).get() }.getOrNull()?.lastOrNull()
        val error = prefs.getString(KEY_ERROR, null)
        return when (info?.state) {
            WorkInfo.State.RUNNING -> if (bytes >= total) State.Checking else State.Downloading(bytes, total)
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                val network = network()
                when {
                    error != null -> State.Waiting(bytes, total, "retrying soon ($error)")
                    network == null -> State.Waiting(bytes, total, "waiting for a connection")
                    network == Network.MOBILE && !prefs.getBoolean(KEY_MOBILE, false) -> State.Waiting(bytes, total, "waiting for Wi-Fi")
                    // The right network is there: the job is about to start.
                    else -> State.Downloading(bytes, total)
                }
            }
            WorkInfo.State.FAILED -> State.Failed(error ?: "download failed")
            else -> if (error != null) State.Failed(error) else State.NotStarted
        }
    }

    private enum class Network { WIFI, MOBILE }

    private fun network(): Network? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return null
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return null
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) Network.WIFI else Network.MOBILE
    }.getOrNull()

    private fun downloadedBytes(): Long = files.sumOf { f ->
        val done = File(dir, f.name).length()
        if (done == f.size) done else File(dir, f.name + PART).length().coerceAtMost(f.size)
    }

    /**
     * Downloads every missing file (resuming partial ones), checks it and moves it into place.
     * [onProgress] gets the bytes so far of the whole model. Throws IOException to retry later.
     */
    suspend fun fetchAll(onProgress: suspend (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val total = totalBytes
        for (f in files) {
            val target = File(dir, f.name)
            if (target.length() == f.size) continue
            val part = File(dir, f.name + PART)
            fetch(f, part) { onProgress(downloadedBytes(), total) }
            if (sha256(part) != f.sha256) {
                part.delete()
                throw IOException("${f.name} didn't match its checksum")
            }
            target.delete()
            if (!part.renameTo(target)) throw IOException("couldn't move ${f.name} into place")
            DotLog.i("model: ${f.name} downloaded and checked")
        }
        marker.writeText(revision)
        prefs.edit { remove(KEY_ERROR) }
        onProgress(total, total)
    }

    private suspend fun fetch(f: ModelFile, part: File, onChunk: suspend () -> Unit) {
        var have = part.length()
        if (have >= f.size) {
            if (have > f.size) part.delete()
            if (have == f.size) return
            have = 0
        }
        val conn = (URL(f.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "DotScreenshots (Android)")
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val code = conn.responseCode
            val append = when (code) {
                HttpURLConnection.HTTP_PARTIAL -> true
                HttpURLConnection.HTTP_OK -> false
                else -> throw IOException("server said HTTP $code")
            }
            DotLog.i("model: downloading ${f.name}" + if (append) " from ${have / 1_000_000} MB" else "")
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var lastReport = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 500) {
                            lastReport = now
                            onChunk()
                        }
                    }
                }
            }
            if (part.length() != f.size) throw IOException("${f.name} ended early")
        } finally {
            conn.disconnect()
        }
    }

    /** Why the last attempt failed, shown in Settings while it waits to retry. */
    fun recordError(message: String) = prefs.edit { putString(KEY_ERROR, message) }

    /** Earlier versions downloaded with Android's download manager: cancel those and their files. */
    private fun forgetSystemDownloads() {
        val ids = prefs.all.filterKeys { it.startsWith("id_") }.values.mapNotNull { it as? Long }
        if (ids.isEmpty()) return
        runCatching { context.getSystemService(DownloadManager::class.java).remove(*ids.toLongArray()) }
        prefs.edit { prefs.all.keys.filter { it.startsWith("id_") }.forEach { remove(it) } }
        dir.listFiles { file -> file.name.endsWith(".part") }?.forEach { it.delete() }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1 shl 16).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val WORK = "model-download"
        private const val PART = ".download"
        private const val KEY_MOBILE = "mobile_data"
        private const val KEY_ERROR = "last_error"
    }
}

/**
 * Downloads the description model. While the app is open it runs as a foreground service with a
 * progress notification, so it isn't cut off; otherwise as an ordinary job that picks up where
 * the last one stopped.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as DotScreenshotsApp).container
        val download = container.hub.download
        if (download.ready) return Result.success()
        runCatching { setForeground(foregroundInfo(0, download.totalBytes)) }
        var shown = 0L
        return try {
            download.fetchAll { bytes, total ->
                // The notification's progress, every couple of seconds.
                val now = System.currentTimeMillis()
                if (now - shown > 2_000) {
                    shown = now
                    runCatching { setForeground(foregroundInfo(bytes, total)) }
                }
            }
            container.onModelReady()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            DotLog.w("model: download stopped ($reason); retrying later")
            download.recordError(reason)
            if (runAttemptCount < 10) Result.retry() else Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0)

    private fun foregroundInfo(bytes: Long, total: Long): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Description model download", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Downloading the description model")
            .setContentText(if (total > 0) "${bytes / 1_000_000} of ${total / 1_000_000} MB" else null)
            .setProgress(total.toInt().coerceAtLeast(0), bytes.toInt().coerceAtLeast(0), total <= 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val CHANNEL = "model-download"
        const val NOTIFICATION_ID = 8
    }
}
