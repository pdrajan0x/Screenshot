package com.pdrajan.dotscreenshots.index

import android.content.Context
import com.pdrajan.dot.engine.AppRecognizer
import com.pdrajan.dot.engine.SummaryParser
import com.pdrajan.dot.engine.SummaryPrompt
import com.pdrajan.dot.llm.LlamaEngine
import com.pdrajan.dot.llm.ModelDownloader
import com.pdrajan.dot.media.DotLog
import com.pdrajan.dotscreenshots.data.Settings
import com.pdrajan.dotscreenshots.data.ShotsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Titles, summaries and keywords from the on-device language model (Qwen3 1.7B via llama.cpp),
 * written after a screenshot's text has been read. The model is loaded on demand and released
 * a minute after the last summary.
 */
class SummaryEngine(
    private val context: Context,
    private val repo: ShotsRepository,
    private val settings: Settings,
    val downloader: ModelDownloader,
    private val scope: CoroutineScope,
) {
    private val lock = Mutex()
    @Volatile private var engine: LlamaEngine? = null
    private var releaseJob: Job? = null

    private val _progress = MutableStateFlow(IndexProgress())
    val progress: StateFlow<IndexProgress> = _progress.asStateFlow()

    /** The model is downloaded and summaries are switched on. */
    val available: Boolean get() = settings.summariesEnabled.value && downloader.isReady()

    /**
     * Summarises up to [limit] screenshots taken after [since], newest first. Returns how many
     * summaries were written.
     */
    suspend fun process(limit: Int, since: Long = 0L, deadline: Long = Long.MAX_VALUE, isStopped: () -> Boolean = { false }): Int =
        lock.withLock {
            if (!available) return 0
            val jobs = repo.summaryQueue(limit, since)
            if (jobs.isEmpty()) return 0
            releaseJob?.cancel()
            var done = 0
            var written = 0
            val started = System.currentTimeMillis()
            _progress.value = IndexProgress(running = true, total = jobs.size, preparing = engine == null)
            try {
                val llm = engine ?: withContext(Dispatchers.IO) { LlamaEngine.load(context, downloader.file) }?.also { engine = it }
                if (llm == null) {
                    DotLog.e("summary: model could not be loaded")
                    return 0
                }
                _progress.value = IndexProgress(running = true, total = jobs.size)
                for (job in jobs) {
                    if (isStopped() || System.currentTimeMillis() > deadline) break
                    currentCoroutineContext().ensureActive()
                    val itemStart = System.currentTimeMillis()
                    try {
                        val prompt = SummaryPrompt.build(job.app, job.text, downloader.spec.thinking)
                        val parsed = SummaryParser.parse(generate(llm, prompt))
                        if (parsed == null) {
                            DotLog.w("summary: unreadable answer for ${job.name}")
                            repo.markSummaryFailed(job.id)
                        } else {
                            // The model's app guess only counts when the screen's text backs it up.
                            val guess = if (job.app == null) AppRecognizer.recognize(job.text, null, parsed.app)?.app else null
                            repo.saveSummary(job.id, parsed, guess)
                            written++
                            if (written == 1) DotLog.i("summary: first one took ${System.currentTimeMillis() - itemStart} ms")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        DotLog.e("summary: failed on ${job.name}", e)
                        repo.markSummaryFailed(job.id)
                    }
                    done++
                    _progress.value = IndexProgress(running = true, done = done, total = jobs.size)
                }
                DotLog.i("summary: $written written, ${done - written} failed in ${System.currentTimeMillis() - started} ms")
            } catch (e: CancellationException) {
                DotLog.i("summary: stopped after $done (app left the screen or work was stopped)")
                throw e
            } finally {
                _progress.value = IndexProgress()
                scheduleRelease()
            }
            written
        }

    /** Generation is a blocking native call: on cancellation, tell llama.cpp to stop right away. */
    private suspend fun generate(llm: LlamaEngine, prompt: String): String = coroutineScope {
        val work = async(Dispatchers.Default) { llm.generate(prompt, SummaryPrompt.GRAMMAR, MAX_TOKENS) }
        try {
            work.await()
        } catch (e: CancellationException) {
            llm.cancel()
            throw e
        }
    }

    private fun scheduleRelease() {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(60_000)
            lock.withLock {
                engine?.close()
                engine = null
            }
        }
    }

    /** After the model file is deleted. */
    suspend fun unload() = lock.withLock {
        engine?.close()
        engine = null
    }

    private companion object {
        const val MAX_TOKENS = 160
    }
}
