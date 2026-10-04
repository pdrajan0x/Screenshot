package com.pdrajan.dotscreenshots.index

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import com.pdrajan.dot.engine.ModelFile
import com.pdrajan.dot.media.DotLog
import com.pdrajan.dot.ml.FlorenceDescriber
import com.pdrajan.dotscreenshots.DotScreenshotsApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Gets the description model (Florence-2-base, about 215 MB) after install, with Android's
 * download manager: it resumes after interruptions, shows its own progress notification and, by
 * default, waits for Wi-Fi. Each file is checked against the size and sha256 in the shipped config
 * before it's used.
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
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val installLock = Mutex()

    /** App-specific storage the download manager can write to (removed with the app). */
    private val dir: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "florence")
    private val marker: File get() = File(dir, "verified.txt")

    val totalBytes: Long get() = files.sumOf { it.size }

    /** Where the checked model files are; null until all of them are downloaded and checked. */
    fun installedDir(): File? {
        if (!marker.exists() || runCatching { marker.readText() }.getOrNull() != revision) return null
        return dir.takeIf { files.all { f -> File(it, f.name).length() == f.size } }
    }

    val ready: Boolean get() = installedDir() != null

    /** Starts (or restarts) downloading whatever is missing. [mobileData]: also over mobile data. */
    @Synchronized
    fun start(mobileData: Boolean) {
        if (ready) return
        dir.mkdirs()
        val current = query()
        for (f in files) {
            if (File(dir, f.name).length() == f.size) continue
            val id = prefs.getLong(key(f), -1L)
            val row = current[id]
            val active = row != null && row.status in listOf(DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED)
            if (active && prefs.getBoolean(KEY_MOBILE, false) == mobileData) continue
            if (row?.status == DownloadManager.STATUS_SUCCESSFUL) continue
            if (id >= 0) manager.remove(id)
            File(dir, f.name + PART).delete()
            val newId = runCatching {
                val request = DownloadManager.Request(Uri.parse(f.url))
                    .setTitle("Dot Screenshots")
                    .setDescription("Description model (${f.name})")
                    // Throws when there's no external storage to download to.
                    .setDestinationInExternalFilesDir(context, null, "florence/${f.name}$PART")
                    .setAllowedOverMetered(mobileData)
                    .setAllowedOverRoaming(false)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                manager.enqueue(request)
            }.getOrElse {
                DotLog.e("model: couldn't start downloading ${f.name}", it)
                return
            }
            prefs.edit { putLong(key(f), newId) }
        }
        prefs.edit { putBoolean(KEY_MOBILE, mobileData) }
        DotLog.i("model: downloading the description model" + if (mobileData) " (mobile data allowed)" else " (Wi-Fi)")
    }

    /** What the download is doing, for Settings. Cheap: no file is read. */
    fun state(): State {
        if (ready) return State.Ready
        val rows = query()
        var bytes = 0L
        var waiting: String? = null
        var running = false
        var allDone = true
        var started = false
        for (f in files) {
            if (File(dir, f.name).length() == f.size) {
                bytes += f.size
                continue
            }
            val row = rows[prefs.getLong(key(f), -1L)]
            if (row == null) {
                allDone = false
                continue
            }
            started = true
            bytes += row.bytes.coerceAtLeast(0)
            when (row.status) {
                DownloadManager.STATUS_SUCCESSFUL -> Unit
                DownloadManager.STATUS_FAILED -> return State.Failed(failure(row.reason))
                DownloadManager.STATUS_PAUSED -> {
                    allDone = false
                    waiting = when (row.reason) {
                        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Waiting for Wi-Fi"
                        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for a connection"
                        DownloadManager.PAUSED_WAITING_TO_RETRY -> "Retrying soon"
                        else -> "Paused"
                    }
                }
                else -> {
                    allDone = false
                    running = true
                }
            }
        }
        return when {
            !started && bytes == 0L -> State.NotStarted
            allDone -> State.Checking
            running -> State.Downloading(bytes, totalBytes)
            waiting != null -> State.Waiting(bytes, totalBytes, waiting)
            else -> State.NotStarted
        }
    }

    /**
     * Checks finished downloads and moves the good ones into place. Returns true when the whole
     * model is ready. Reads every finished file once (sha256), so call it off the main thread.
     */
    suspend fun install(): Boolean = installLock.withLock {
        withContext(Dispatchers.IO) {
            if (ready) return@withContext true
            val rows = query()
            for (f in files) {
                val target = File(dir, f.name)
                if (target.length() == f.size) continue
                val row = rows[prefs.getLong(key(f), -1L)] ?: continue
                if (row.status != DownloadManager.STATUS_SUCCESSFUL) continue
                val part = row.localPath?.let(::File) ?: File(dir, f.name + PART)
                if (part.length() == f.size && sha256(part) == f.sha256) {
                    target.delete()
                    if (!part.renameTo(target)) part.copyTo(target, overwrite = true).also { part.delete() }
                    DotLog.i("model: ${f.name} downloaded and checked")
                } else {
                    DotLog.w("model: ${f.name} didn't match its checksum; downloading it again")
                    part.delete()
                    manager.remove(row.id)
                    prefs.edit { remove(key(f)) }
                }
            }
            val complete = files.all { File(dir, it.name).length() == it.size }
            if (complete) marker.writeText(revision)
            complete
        }
    }

    private class Row(val id: Long, val status: Int, val reason: Int, val bytes: Long, val localPath: String?)

    private fun query(): Map<Long, Row> {
        val ids = files.map { prefs.getLong(key(it), -1L) }.filter { it >= 0 }
        if (ids.isEmpty()) return emptyMap()
        return runCatching {
            manager.query(DownloadManager.Query().setFilterById(*ids.toLongArray()))?.use { c ->
                val id = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val status = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                val reason = c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                val bytes = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val local = c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
                buildMap {
                    while (c.moveToNext()) {
                        val path = c.getString(local)?.toUri()?.path
                        put(c.getLong(id), Row(c.getLong(id), c.getInt(status), c.getInt(reason), c.getLong(bytes), path))
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyMap())
    }

    private fun failure(reason: Int) = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough storage (needs about ${totalBytes / 1_000_000} MB)"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Storage not available"
        else -> "Download failed"
    }

    private fun key(f: ModelFile) = "id_${f.name}"

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

    private companion object {
        const val PART = ".part"
        const val KEY_MOBILE = "mobile_data"
    }
}

/** A model file finished downloading: check it and, once the model is complete, get describing. */
class ModelDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val container = (context.applicationContext as DotScreenshotsApp).container
        val pending = goAsync()
        container.scope.launch {
            try {
                if (container.hub.download.install()) container.onModelReady()
            } finally {
                pending.finish()
            }
        }
    }
}
