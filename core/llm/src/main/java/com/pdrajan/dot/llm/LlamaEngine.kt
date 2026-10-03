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
    @JvmStatic external fun nativeCountTokens(handle: Long, text: ByteArray): Int
    @JvmStatic external fun nativeGenerate(
        handle: Long,
        prompt: ByteArray,
        grammar: ByteArray?,
        maxTokens: Int,
        temperature: Float,
        seed: Int,
    ): ByteArray?
    @JvmStatic external fun nativeLastError(handle: Long): String
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

    /** Blocking; run off the main thread. [grammar] is GBNF constraining the output. */
    fun generate(prompt: String, grammar: String? = null, maxTokens: Int = 160, temperature: Float = 0f): String =
        synchronized(lock) {
            val h = handle
            if (h == 0L) throw LlmException("model closed")
            val out = LlamaNative.nativeGenerate(h, prompt.toByteArray(), grammar?.toByteArray(), maxTokens, temperature, 0)
                ?: throw LlmException(LlamaNative.nativeLastError(h))
            String(out, Charsets.UTF_8)
        }

    fun countTokens(text: String): Int = synchronized(lock) {
        val h = handle
        if (h == 0L) 0 else LlamaNative.nativeCountTokens(h, text.toByteArray())
    }

    /** Stops a running [generate] early (it then throws). Safe from any thread. */
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
            val h = LlamaNative.nativeLoad(model.absolutePath, contextTokens, threads)
            if (h == 0L) {
                DotLog.e("llm: failed to load ${model.name}")
                return null
            }
            DotLog.i("llm: loaded ${model.name} with $threads threads in ${System.currentTimeMillis() - started} ms")
            return LlamaEngine(h)
        }

        /** Big cores do the work; little ones mostly add contention. */
        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 4)
    }
}
