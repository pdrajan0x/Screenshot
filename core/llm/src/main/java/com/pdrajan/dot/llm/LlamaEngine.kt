package com.pdrajan.dot.llm

import android.content.Context
import com.pdrajan.dot.media.DotLog
import java.io.Closeable
import java.io.File

/** JNI entry points of libdotllm.so (see src/main/cpp/dot_llm.cpp). Text crosses as UTF-8 bytes. */
object LlamaNative {
    @JvmStatic external fun nativeInit(backendDir: String?)
    @JvmStatic external fun nativeSystemInfo(): String
    @JvmStatic external fun nativeLoad(path: String, nCtx: Int, nThreads: Int): Long
    /** Loads a vision projector (mmproj) for the loaded model; [maxImageTokens] > 0 caps tokens per image. */
    @JvmStatic external fun nativeLoadVision(handle: Long, mmprojPath: String, nThreads: Int, maxImageTokens: Int): Boolean
    /** Answers [instruction] about an RGB image (3 bytes per pixel, row by row). */
    @JvmStatic external fun nativeDescribe(
        handle: Long,
        rgb: ByteArray,
        width: Int,
        height: Int,
        instruction: ByteArray,
        grammar: ByteArray?,
        maxTokens: Int,
    ): ByteArray?
    @JvmStatic external fun nativeLastError(handle: Long): String
    /** Recent llama.cpp warnings and errors (cleared by reading). */
    @JvmStatic external fun nativeTakeLog(): String
    @JvmStatic external fun nativeCancel(handle: Long)
    @JvmStatic external fun nativeFree(handle: Long)
}

class LlmException(message: String) : Exception(message)

/**
 * A loaded GGUF model (llama.cpp, CPU). The weights are memory-mapped, so they cost page cache
 * rather than app heap; call [close] when idle to give the memory back.
 */
class LlamaEngine private constructor(handle: Long) : Closeable {

    @Volatile private var handle: Long = handle
    private val lock = Any()

    /** Lets [describe] see images: loads a vision model's projector file. */
    fun loadVision(mmproj: File, threads: Int = defaultThreads(), maxImageTokens: Int = 0): Boolean = synchronized(lock) {
        val h = handle
        val ok = h != 0L && readable(mmproj) && LlamaNative.nativeLoadVision(h, mmproj.absolutePath, threads, maxImageTokens)
        val notes = LlamaNative.nativeTakeLog().trim()
        if (!ok) DotLog.e("llm: failed to load ${mmproj.name}" + if (notes.isNotEmpty()) ":\n$notes" else "")
        ok
    }

    /**
     * Blocking; run off the main thread. Answers [instruction] about an RGB image ([width] x
     * [height]); [grammar] is GBNF constraining the answer.
     */
    fun describe(rgb: ByteArray, width: Int, height: Int, instruction: String, grammar: String? = null, maxTokens: Int = 60): String = synchronized(lock) {
        val h = handle
        if (h == 0L) throw LlmException("model closed")
        val out = LlamaNative.nativeDescribe(h, rgb, width, height, instruction.toByteArray(), grammar?.toByteArray(), maxTokens)
            ?: throw LlmException(LlamaNative.nativeLastError(h))
        String(out, Charsets.UTF_8)
    }

    /** Stops a running [describe] early (it then throws). Safe from any thread. */
    fun cancel() {
        val h = handle
        if (h != 0L) LlamaNative.nativeCancel(h)
    }

    override fun close() {
        cancel()
        synchronized(lock) {
            val h = handle
            if (h != 0L) {
                handle = 0L
                LlamaNative.nativeFree(h)
            }
        }
    }

    companion object {
        @Volatile private var initialised = false

        /** Loads the native library and the best CPU backend for this phone. */
        fun init(context: Context): Boolean = synchronized(this) {
            if (!initialised) {
                runCatching {
                    System.loadLibrary("dotllm")
                    LlamaNative.nativeInit(context.applicationInfo.nativeLibraryDir)
                    initialised = true
                    DotLog.i("llm: ${LlamaNative.nativeSystemInfo().trim()}")
                }.onFailure { DotLog.e("llm: native library unavailable", it) }
            }
            initialised
        }

        fun load(context: Context, model: File, contextTokens: Int = 2048, threads: Int = defaultThreads()): LlamaEngine? {
            if (!init(context) || !model.exists()) return null
            val started = System.currentTimeMillis()
            if (!readable(model)) {
                DotLog.e("llm: can't read ${model.path} (another app's copy needs All files access)")
                return null
            }
            val h = LlamaNative.nativeLoad(model.absolutePath, contextTokens, threads)
            val notes = LlamaNative.nativeTakeLog().trim()
            if (h == 0L) {
                DotLog.e("llm: failed to load ${model.name} (${model.length() / 1_000_000} MB)" + if (notes.isNotEmpty()) ":\n$notes" else "")
                return null
            }
            if (notes.isNotEmpty()) DotLog.w("llm: ${model.name} loaded with warnings:\n$notes")
            DotLog.i("llm: loaded ${model.name} with $threads threads in ${System.currentTimeMillis() - started} ms")
            return LlamaEngine(h)
        }

        /** The file can really be opened (not just seen): another app's copy needs All files access. */
        fun readable(file: File): Boolean = runCatching { file.inputStream().use { it.read() >= 0 } }.getOrDefault(false)

        /**
         * One thread per fast core (prime + big), at most 4. Many budget phones have only two fast
         * cores next to six slow ones: a job split across all of them waits for the slow ones and
         * burns more power for less speed.
         */
        fun defaultThreads(): Int = bigCores.coerceIn(1, 4)

        private val bigCores: Int by lazy {
            val n = Runtime.getRuntime().availableProcessors()
            fun read(cpu: Int, name: String): Long? =
                runCatching { File("/sys/devices/system/cpu/cpu$cpu/$name").readText().trim().toLong() }.getOrNull()
            // Scheduler capacity (1024 for the fastest core) where the kernel reports it; else max clocks.
            val capacity = (0 until n).map { read(it, "cpu_capacity") }
            val clocks = (0 until n).map { read(it, "cpufreq/cpuinfo_max_freq") }
            val count = when {
                capacity.all { it != null } -> capacity.filterNotNull().let { c -> c.count { it >= c.max() / 2 } }
                clocks.all { it != null } -> clocks.filterNotNull().let { c -> if (c.distinct().size > 1) c.count { it > c.min() } else n / 2 }
                else -> n - 2
            }
            DotLog.i("llm: $count fast cores of $n")
            count
        }
    }
}
