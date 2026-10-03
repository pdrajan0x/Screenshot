package com.pdrajan.dot.llm

import android.os.Process
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.Executors

/**
 * The one thread the AI model runs on. Work started with [gentle] runs at background priority:
 * Android then keeps it (and the model's worker threads, which inherit it) on the power-efficient
 * cores and out of the way of whatever the user is doing, so the phone doesn't feel slow. Slower per
 * item, lighter on the battery. "Do it now" and charging run at normal priority.
 */
object LlmThread {

    private val dispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "dot-llm").apply { isDaemon = true } }.asCoroutineDispatcher()

    /** Runs a blocking model call; on cancellation the model is told to stop right away. */
    suspend fun <T> run(engine: LlamaEngine, gentle: Boolean, block: (LlamaEngine) -> T): T = coroutineScope {
        val work = async(dispatcher) {
            Process.setThreadPriority(if (gentle) Process.THREAD_PRIORITY_BACKGROUND else Process.THREAD_PRIORITY_DEFAULT)
            block(engine)
        }
        try {
            work.await()
        } catch (e: CancellationException) {
            engine.cancel()
            throw e
        }
    }
}
