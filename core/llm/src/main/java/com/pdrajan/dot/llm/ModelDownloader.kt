package com.pdrajan.dot.llm

import android.content.Context
import com.pdrajan.dot.media.DotLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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
    /** Qwen3 1.7B, 4-bit (Q4_0 runs fastest on ARM: llama.cpp repacks it for dot-product/i8mm cores). */
    val SUMMARY = ModelSpec(
        id = "qwen3-1.7b-q4_0",
        label = "Qwen3 1.7B",
        fileName = "Qwen3-1.7B-Q4_0.gguf",
        sizeBytes = 1_056_782_912L,
        sha256 = "c876f159707a4e4f70e045106c69db15bfc935a4981706fd4f65c6e7ea1e81c5",
        urls = listOf(
            "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_0.gguf",
            "https://github.com/pdrajan0x/Screenshot/releases/download/llm-models-v1/Qwen3-1.7B-Q4_0.gguf",
        ),
        thinking = true,
    )
}

/** Resumable, checksum-verified download of a [ModelSpec] into app storage. */
class ModelDownloader(context: Context, val spec: ModelSpec) {

    sealed interface State {
        data object Missing : State
        data class Downloading(val bytes: Long, val total: Long) : State
        data object Verifying : State
        data object Ready : State
        data class Failed(val message: String, val bytes: Long) : State
    }

    private val dir = File(context.filesDir, "models")
    val file = File(dir, spec.fileName)
    private val part = File(dir, spec.fileName + ".part")

    private val _state = MutableStateFlow(if (isReady()) State.Ready else State.Missing)
    val state: StateFlow<State> = _state.asStateFlow()

    fun isReady(): Boolean = file.exists() && file.length() == spec.sizeBytes

    /** Bytes of an interrupted download that will be resumed. */
    fun partialBytes(): Long = if (part.exists()) part.length() else 0L

    suspend fun download() = withContext(Dispatchers.IO) {
        if (isReady()) {
            _state.value = State.Ready
            return@withContext
        }
        dir.mkdirs()
        val needed = spec.sizeBytes - partialBytes() + 100_000_000L
        if (dir.usableSpace < needed) {
            _state.value = State.Failed("Not enough storage: about ${needed / 1_000_000} MB needed", partialBytes())
            return@withContext
        }
        var lastError: Exception? = null
        for (url in spec.urls) {
            try {
                fetch(url)
                _state.value = State.Verifying
                if (sha256(part) != spec.sha256) {
                    part.delete()
                    throw IOException("checksum mismatch")
                }
                if (!part.renameTo(file)) throw IOException("could not move the model into place")
                DotLog.i("llm: model downloaded from ${URL(url).host}")
                _state.value = State.Ready
                return@withContext
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

    private suspend fun fetch(url: String) {
        var existing = partialBytes()
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

    fun delete() {
        file.delete()
        part.delete()
        _state.value = State.Missing
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
