package com.pdrajan.dotscreenshots.index

import android.content.Context
import com.pdrajan.dot.engine.SummaryParser
import com.pdrajan.dot.engine.VisionPrompts
import com.pdrajan.dot.llm.LlamaEngine
import com.pdrajan.dot.llm.ModelBundle
import com.pdrajan.dot.llm.PowerGate
import com.pdrajan.dot.media.DotLog
import com.pdrajan.dot.ml.RgbImage
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
 * Titles, summaries, keywords and the app's name from the on-device vision model both Dot apps
 * share (LFM2.5-VL 1.6B via llama.cpp). It sees the screenshot itself plus the text read from it.
 * The model is loaded on demand and released a minute after the last screenshot.
 */
class SummaryEngine(
    private val context: Context,
    private val repo: ShotsRepository,
    val model: ModelBundle,
    private val power: PowerGate,
    private val scope: CoroutineScope,
) {
    private val lock = Mutex()
    @Volatile private var engine: LlamaEngine? = null
    private var releaseJob: Job? = null
    private var lastBlocker: String? = null

    private val _progress = MutableStateFlow(IndexProgress())
    val progress: StateFlow<IndexProgress> = _progress.asStateFlow()

    /** The model is downloaded. */
    val available: Boolean get() = model.isReady()

    /**
     * Summarises up to [limit] screenshots taken after [since], newest first, while [PowerGate]
     * allows ([userAsked]: the user tapped "Process now"; [foreground]: the app is open). Returns
     * how many summaries were written.
     */
    suspend fun process(
        limit: Int,
        since: Long = 0L,
        deadline: Long = Long.MAX_VALUE,
        userAsked: Boolean = false,
        foreground: Boolean = true,
        isStopped: () -> Boolean = { false },
    ): Int =
        lock.withLock {
            if (!available || blocked(userAsked, foreground)) return 0
            val jobs = repo.summaryQueue(limit, since)
            if (jobs.isEmpty()) return 0
            releaseJob?.cancel()
            var done = 0
            var written = 0
            val started = System.currentTimeMillis()
            _progress.value = IndexProgress(running = true, total = jobs.size, preparing = engine == null)
            try {
                val vlm = engine ?: withContext(Dispatchers.IO) { load() }?.also { engine = it }
                if (vlm == null) {
                    DotLog.e("summary: model could not be loaded")
                    // Deleted from the Files app: show the download again.
                    model.refresh()
                    return 0
                }
                _progress.value = IndexProgress(running = true, total = jobs.size)
                for (job in jobs) {
                    if (isStopped() || System.currentTimeMillis() > deadline || blocked(userAsked, foreground)) break
                    currentCoroutineContext().ensureActive()
                    val itemStart = System.currentTimeMillis()
                    try {
                        val image = withContext(Dispatchers.IO) { RgbImage.load(context.contentResolver, job.uri, VisionPrompts.SCREENSHOT_IMAGE_TOKENS * VisionPrompts.PIXELS_PER_IMAGE_TOKEN) }
                        val parsed = SummaryParser.parse(describe(vlm, image, VisionPrompts.screenshot(job.text, job.app)))
                        if (parsed == null) {
                            DotLog.w("summary: unreadable answer for ${job.name}")
                            repo.markSummaryFailed(job.id)
                        } else {
                            repo.saveSummary(job.id, parsed)
                            written++
                            if (written == 1) DotLog.i("summary: first one took ${System.currentTimeMillis() - itemStart} ms")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        DotLog.w("summary: failed on ${job.name}: ${e.javaClass.simpleName}: ${e.message}")
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

    private fun load(): LlamaEngine? {
        val files = model.files() ?: return null
        val threads = power.threads()
        // Screen text (up to 1,000 characters) plus 128 image tokens and the answer.
        val llm = LlamaEngine.load(context, files[0], contextTokens = 2048, threads = threads) ?: return null
        if (!llm.loadVision(files[1], threads, VisionPrompts.SCREENSHOT_IMAGE_TOKENS)) {
            llm.close()
            return null
        }
        return llm
    }

    /** Logs when summaries pause or resume for battery or heat (once per change, not per call). */
    private fun blocked(userAsked: Boolean, foreground: Boolean): Boolean {
        val reason = power.blocker(userAsked, foreground)
        if (reason != lastBlocker) {
            DotLog.i(if (reason != null) "summary: paused ($reason)" else "summary: allowed again")
            lastBlocker = reason
        }
        return reason != null
    }

    /** Generation is a blocking native call: on cancellation, tell llama.cpp to stop right away. */
    private suspend fun describe(vlm: LlamaEngine, image: RgbImage, prompt: String): String = coroutineScope {
        val work = async(Dispatchers.Default) {
            vlm.describe(image.bytes, image.width, image.height, prompt, VisionPrompts.SCREENSHOT_GRAMMAR, VisionPrompts.MAX_TOKENS)
        }
        try {
            work.await()
        } catch (e: CancellationException) {
            vlm.cancel()
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
}
