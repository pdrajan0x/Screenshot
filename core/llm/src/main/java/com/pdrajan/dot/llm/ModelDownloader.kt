package com.pdrajan.dot.llm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import com.pdrajan.dot.media.DotLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A downloadable GGUF model with its exact size and checksum. */
data class ModelSpec(
    val id: String,
    val label: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val urls: List<String>,
    /** Qwen3-style model whose thinking must be switched off in the prompt. */
    val thinking: Boolean,
)

object Models {
    /**
     * LFM2.5-VL 1.6B (Liquid AI): the one model both apps use for every description, summary and
     * keyword. Language half; [VISION_PROJECTOR] is its image encoder.
     */
    val VISION_TEXT = ModelSpec(
        id = "lfm2.5-vl-1.6b-q4_0",
        label = "LFM2.5-VL 1.6B",
        fileName = "LFM2.5-VL-1.6B-Q4_0.gguf",
        sizeBytes = 695_752_480L,
        sha256 = "8186364a4e7c3ad30f6dd3d3b7a4e0074c77dd91eed6cad5d8be9090ce285804",
        urls = listOf(
            "https://huggingface.co/LiquidAI/LFM2.5-VL-1.6B-GGUF/resolve/main/LFM2.5-VL-1.6B-Q4_0.gguf",
            "https://github.com/pdrajan0x/Screenshot/releases/download/vlm-models-v1/LFM2.5-VL-1.6B-Q4_0.gguf",
        ),
        thinking = false,
    )

    /** Its vision encoder and projector (mmproj): turns an image into tokens for [VISION_TEXT]. */
    val VISION_PROJECTOR = ModelSpec(
        id = "lfm2.5-vl-1.6b-mmproj-q8_0",
        label = "LFM2.5-VL 1.6B vision",
        fileName = "mmproj-LFM2.5-VL-1.6b-Q8_0.gguf",
        sizeBytes = 583_109_888L,
        sha256 = "2ce89e610c56f3198ece2b86cf61743a08b9307279c89125eb2412ebb908689d",
        urls = listOf(
            "https://huggingface.co/LiquidAI/LFM2.5-VL-1.6B-GGUF/resolve/main/mmproj-LFM2.5-VL-1.6b-Q8_0.gguf",
            "https://github.com/pdrajan0x/Screenshot/releases/download/vlm-models-v1/mmproj-LFM2.5-VL-1.6b-Q8_0.gguf",
        ),
        thinking = false,
    )

    /** Models earlier versions downloaded and nothing uses any more (offered for deletion). */
    val RETIRED = listOf(
        "Qwen3-1.7B-Q4_0.gguf" to "summary model (Qwen3 1.7B)",
        "LFM2.5-VL-450M-Q4_0.gguf" to "photo model (LFM2.5-VL 450M)",
        "mmproj-LFM2.5-VL-450m-Q8_0.gguf" to "photo model (LFM2.5-VL 450M)",
    )
}

/** The shared AI model (both apps), and access to copies another app downloaded. */
object SharedModel {

    fun bundle(context: Context) = ModelBundle(context, Models.VISION_TEXT.label, listOf(Models.VISION_TEXT, Models.VISION_PROJECTOR))

    /**
     * Whether this app may read files other apps put in Download/AI Models ("All files access"):
     * with it, a model Dot Gallery downloaded is used by Dot Screenshots too, and the other way round.
     */
    fun canReadOtherAppsFiles(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    /** The system screen where the user grants "All files access" to this app (Android 11+). */
    fun allFilesAccessIntent(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", context.packageName, null))

    /** Old model files still on the phone that this app can delete: (file, what it was). */
    fun retiredFiles(context: Context): List<Pair<File, String>> {
        val dirs = listOfNotNull(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                @Suppress("DEPRECATION")
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), ModelDownloader.SHARED_FOLDER)
            } else {
                null
            },
            File(context.filesDir, "models"),
        )
        return dirs.flatMap { dir ->
            Models.RETIRED.mapNotNull { (name, label) -> File(dir, name).takeIf { runCatching { it.isFile && it.canWrite() }.getOrDefault(false) }?.let { it to label } }
        }
    }
}

/** Where a ready model lives. */
sealed interface ModelSource {
    /** A file this app can open by path: its own download, in shared or app storage. */
    data class Local(val file: File) : ModelSource

    /** A file chosen with the system file picker, e.g. one another app downloaded. */
    data class Picked(val uri: Uri, val name: String) : ModelSource
}

/**
 * Resumable, checksum-verified download of a [ModelSpec].
 *
 * On Android 11 and newer the model goes to Download/AI Models, where it survives reinstalling and
 * other apps can open it through the file picker; older Android versions keep it in app storage.
 * A copy that is already on the phone can be picked instead of downloading again.
 */
class ModelDownloader(private val context: Context, val spec: ModelSpec) {

    sealed interface State {
        data object Missing : State
        data class Downloading(val bytes: Long, val total: Long) : State
        data object Verifying : State
        data object Ready : State
        data class Failed(val message: String, val bytes: Long) : State
    }

    private val prefs = context.getSharedPreferences("dot_models", Context.MODE_PRIVATE)
    private val privateDir = File(context.filesDir, "models")

    /** Download/AI Models, or null where apps can't write to shared storage without a permission. */
    @Suppress("DEPRECATION") // Plain file access to Download works from Android 11 for the app's own files (and all, with All files access).
    val sharedDir: File? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SHARED_FOLDER)
        } else {
            null
        }

    private val lock = Mutex()

    @Volatile private var current: ModelSource? = locate()

    private val _state = MutableStateFlow(if (current != null) State.Ready else State.Missing)
    val state: StateFlow<State> = _state.asStateFlow()

    fun isReady(): Boolean = current != null

    /** The model to load, or null when it isn't on the phone (any more). */
    fun source(): ModelSource? = current

    /** Where the model is, for people: "Download/AI Models/…", the picked file's name, or app storage. */
    fun describeLocation(): String? = when (val s = current) {
        is ModelSource.Picked -> s.name
        is ModelSource.Local -> if (sharedDir != null && s.file.parentFile == sharedDir) {
            "${Environment.DIRECTORY_DOWNLOADS}/$SHARED_FOLDER/${s.file.name}"
        } else {
            "app storage"
        }
        null -> null
    }

    /** Re-checks the model is still there (it can be deleted from the Files app). */
    fun refresh() {
        current = locate()
        val s = _state.value
        if (s == State.Ready || s == State.Missing) _state.value = if (current != null) State.Ready else State.Missing
    }

    private fun locate(): ModelSource? {
        prefs.getString(KEY_URI + spec.id, null)?.let { saved ->
            val uri = Uri.parse(saved)
            val held = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            if (held) return ModelSource.Picked(uri, prefs.getString(KEY_NAME + spec.id, null) ?: spec.fileName)
            prefs.edit().remove(KEY_URI + spec.id).remove(KEY_NAME + spec.id).apply()
        }
        val candidates = listOfNotNull(
            prefs.getString(KEY_PATH + spec.id, null)?.let(::File),
            sharedDir?.let { File(it, spec.fileName) },
            File(privateDir, spec.fileName),
        )
        return candidates.firstOrNull { complete(it) }?.let { ModelSource.Local(it) }
    }

    private fun complete(f: File) = runCatching { f.isFile && f.length() == spec.sizeBytes }.getOrDefault(false)

    /** The folder new downloads go to: the shared one when it can be created. */
    private fun downloadDir(): File {
        sharedDir?.let { dir -> if (runCatching { dir.isDirectory || dir.mkdirs() }.getOrDefault(false)) return dir }
        return privateDir.also { it.mkdirs() }
    }

    private fun partIn(dir: File) = File(dir, spec.fileName + ".part")

    /** The interrupted download to resume, wherever it was started. */
    private fun existingPart(): File? = listOfNotNull(sharedDir, privateDir).map(::partIn).firstOrNull { it.isFile && it.length() > 0 }

    /** Bytes of an interrupted download that will be resumed. */
    fun partialBytes(): Long = existingPart()?.length() ?: 0L

    suspend fun download() = withContext(Dispatchers.IO) {
        lock.withLock {
            if (current != null) {
                _state.value = State.Ready
                return@withLock
            }
            val part = existingPart() ?: partIn(downloadDir())
            val dir = part.parentFile ?: privateDir
            val needed = spec.sizeBytes - partialBytes() + 100_000_000L
            if (dir.usableSpace < needed) {
                _state.value = State.Failed("Not enough storage: about ${needed / 1_000_000} MB needed", partialBytes())
                return@withLock
            }
            var lastError: Exception? = null
            for (url in spec.urls) {
                try {
                    fetch(url, part)
                    _state.value = State.Verifying
                    if (part.inputStream().use(::sha256) != spec.sha256) {
                        part.delete()
                        throw IOException("checksum mismatch")
                    }
                    val file = moveIntoPlace(part)
                    prefs.edit().putString(KEY_PATH + spec.id, file.absolutePath).apply()
                    current = ModelSource.Local(file)
                    DotLog.i("llm: model downloaded from ${URL(url).host} to ${file.parent}")
                    _state.value = State.Ready
                    return@withLock
                } catch (e: CancellationException) {
                    _state.value = State.Failed("Paused", partialBytes())
                    throw e
                } catch (e: Exception) {
                    DotLog.w("llm: download from ${runCatching { URL(url).host }.getOrDefault(url)} failed", e)
                    lastError = e
                }
            }
            _state.value = State.Failed(lastError?.message ?: "Download failed", partialBytes())
        }
    }

    /**
     * Renames the finished download. In shared storage a file of the same name left behind by an
     * earlier install belongs to nobody we can see, so a numbered name is used instead.
     */
    private fun moveIntoPlace(part: File): File {
        val dir = part.parentFile ?: throw IOException("no folder")
        val base = spec.fileName.removeSuffix(".gguf")
        val names = listOf(spec.fileName) + (2..9).map { "$base ($it).gguf" }
        for (name in names) {
            val target = File(dir, name)
            if (target.exists()) {
                if (complete(target)) continue
                target.delete()
            }
            if (part.renameTo(target)) return target
        }
        throw IOException("could not move the model into place")
    }

    private suspend fun fetch(url: String, part: File) {
        var existing = if (part.exists()) part.length() else 0L
        if (existing >= spec.sizeBytes) {
            part.delete()
            existing = 0
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "DotScreenshots")
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            // A server that ignores Range sends everything again: start over.
            val append = code == HttpURLConnection.HTTP_PARTIAL && existing > 0
            if (!append) existing = 0
            var bytes = existing
            var lastReport = 0L
            _state.value = State.Downloading(bytes, spec.sizeBytes)
            FileOutputStream(part, append).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        bytes += n
                        if (bytes - lastReport >= 2_000_000) {
                            lastReport = bytes
                            _state.value = State.Downloading(bytes, spec.sizeBytes)
                            currentCoroutineContext().ensureActive()
                        }
                    }
                }
            }
            if (bytes != spec.sizeBytes) throw IOException("incomplete download ($bytes of ${spec.sizeBytes} bytes)")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Uses a model file chosen in the system file picker (one another app downloaded, or this
     * app's own copy after a reinstall). It is read where it is, never copied.
     */
    suspend fun adopt(uri: Uri) = withContext(Dispatchers.IO) {
        lock.withLock {
            val previous = _state.value
            try {
                val resolver = context.contentResolver
                val (name, size) = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (!c.moveToFirst()) null else (c.getString(0) ?: spec.fileName) to (if (c.isNull(1)) -1L else c.getLong(1))
                } ?: (spec.fileName to -1L)
                if (size != spec.sizeBytes) {
                    _state.value = State.Failed("That file isn't ${spec.label} (${spec.fileName}, ${spec.sizeBytes / 1_000_000} MB)", partialBytes())
                    return@withLock
                }
                _state.value = State.Verifying
                val hash = resolver.openInputStream(uri)?.use(::sha256) ?: throw IOException("could not open the file")
                if (hash != spec.sha256) {
                    _state.value = State.Failed("That file is damaged or a different version of ${spec.label}", partialBytes())
                    return@withLock
                }
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                prefs.edit().putString(KEY_URI + spec.id, uri.toString()).putString(KEY_NAME + spec.id, name).apply()
                existingPart()?.delete()
                current = ModelSource.Picked(uri, name)
                DotLog.i("llm: using a picked model file")
                _state.value = State.Ready
            } catch (e: CancellationException) {
                _state.value = previous
                throw e
            } catch (e: Exception) {
                DotLog.w("llm: could not use the picked file", e)
                _state.value = State.Failed("Couldn't read that file: ${e.message}", partialBytes())
            }
        }
    }

    /**
     * Deletes the downloaded model (from the shared folder too: other apps lose it as well), or
     * stops using a picked file without touching it.
     */
    fun delete() {
        when (val s = current) {
            is ModelSource.Picked -> runCatching {
                context.contentResolver.releasePersistableUriPermission(s.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            is ModelSource.Local -> s.file.delete()
            null -> Unit
        }
        listOfNotNull(sharedDir, privateDir).forEach { partIn(it).delete() }
        File(privateDir, spec.fileName).delete()
        prefs.edit().remove(KEY_URI + spec.id).remove(KEY_NAME + spec.id).remove(KEY_PATH + spec.id).apply()
        current = locate()
        _state.value = if (current != null) State.Ready else State.Missing
    }

    private fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 20)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Under Download; a plain name so people (and other apps' file pickers) can find it. */
        const val SHARED_FOLDER = "AI Models"
        private const val KEY_URI = "uri:"
        private const val KEY_NAME = "name:"
        private const val KEY_PATH = "path:"
    }
}
