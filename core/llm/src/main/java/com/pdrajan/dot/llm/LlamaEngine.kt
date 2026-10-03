package com.pdrajan.dot.llm

import android.content.Context
import com.pdrajan.dot.media.DotLog
import java.io.Closeable
import java.io.File

/** JNI entry points of libdotllm.so (see src/main/cpp/dot_llm.cpp). Text crosses as UTF-8 bytes. */
object LlamaNative {
    @JvmStatic external fun nativeInit(backendDir: String?)
    @JvmStatic external fun nativeSystemInfo(): String
    /** Loads from [path], or from the open file descriptor [fd] when it is >= 0 (the path is then ignored). */
    @JvmStatic external fun nativeLoad(path: String?, fd: Int, nCtx: Int, nThreads: Int): Long
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

        fun load(context: Context, model: File, contextTokens: Int = 2048, threads: Int = defaultThreads()): LlamaEngine? =
            load(context, ModelSource.Local(model), contextTokens, threads)

        fun load(context: Context, model: ModelSource, contextTokens: Int = 2048, threads: Int = defaultThreads()): LlamaEngine? {
            if (!init(context)) return null
            val started = System.currentTimeMillis()
            val h = when (model) {
                is ModelSource.Local -> {
                    if (!model.file.exists()) return null
                    LlamaNative.nativeLoad(model.file.absolutePath, -1, contextTokens, threads)
                }
                // The native side keeps its own duplicate of the descriptor.
                is ModelSource.Picked -> runCatching {
                    context.contentResolver.openFileDescriptor(model.uri, "r")?.use { LlamaNative.nativeLoad(null, it.fd, contextTokens, threads) }
                }.onFailure { DotLog.e("llm: could not open the picked model file", it) }.getOrNull() ?: 0L
            }
            val name = when (model) {
                is ModelSource.Local -> model.file.name
                is ModelSource.Picked -> model.name
            }
            if (h == 0L) {
                DotLog.e("llm: failed to load $name")
                return null
            }
            DotLog.i("llm: loaded $name with $threads threads in ${System.currentTimeMillis() - started} ms")
            return LlamaEngine(h)
        }

        /** Big cores do the work; little ones mostly add contention. */
        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 4)
    }
}
