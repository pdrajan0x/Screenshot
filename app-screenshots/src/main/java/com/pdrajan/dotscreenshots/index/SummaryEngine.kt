package com.pdrajan.dotscreenshots.index

import android.content.Context
import com.pdrajan.dot.engine.AppHints
import com.pdrajan.dot.engine.AppNames
import com.pdrajan.dot.engine.SummaryParser
import com.pdrajan.dot.engine.VisionPrompts
import com.pdrajan.dot.llm.LlamaEngine
import com.pdrajan.dot.llm.LlmThread
import com.pdrajan.dot.llm.ModelBundle
import com.pdrajan.dot.llm.PowerGate
import com.pdrajan.dot.media.DotLog
import com.pdrajan.dot.ml.InstalledApps
import com.pdrajan.dot.ml.RgbImage
import com.pdrajan.dotscreenshots.data.ShotsRepository
import com.pdrajan.dotscreenshots.data.SummaryJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val installed: InstalledApps,
) {
    private val lock = Mutex()
    @Volatile private var engine: LlamaEngine? = null
    private var releaseJob: Job? = null
    private var lastBlocker: String? = null
    /** When loading the model last failed: background work waits a while before trying again. */
    @Volatile private var loadFailedAt = 0L

    private val _progress = MutableStateFlow(IndexProgress())
    val progress: StateFlow<IndexProgress> = _progress.asStateFlow()

    /** Why the model couldn't run, for the status strip; null when it works. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** The model is downloaded. */
    val available: Boolean get() = model.isReady()

    /**
     * Summarises up to [limit] screenshots taken after [since], newest first, while [PowerGate]
     * allows ([userAsked]: the user tapped "Do it now"; [foreground]: the app is open). Returns
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
            summariseAll(jobs, deadline, userAsked, foreground, isStopped)
        }

    /** "Summarise now" in the viewer: this screenshot right away (the user asked, so battery rules don't apply). */
    suspend fun summariseNow(id: Long): Boolean = lock.withLock {
        val job = repo.summaryJob(id) ?: return false
        available && !blocked(userAsked = true, foreground = true) && summariseAll(listOf(job), Long.MAX_VALUE, userAsked = true, foreground = true) { false } > 0
    }

    private suspend fun summariseAll(
        jobs: List<SummaryJob>,
        deadline: Long,
        userAsked: Boolean,
        foreground: Boolean,
        isStopped: () -> Boolean,
    ): Int {
        releaseJob?.cancel()
        var done = 0
        var written = 0
        val started = System.currentTimeMillis()
        _progress.value = IndexProgress(running = true, total = jobs.size, preparing = engine == null)
        try {
            if (engine == null && !userAsked && System.currentTimeMillis() - loadFailedAt < RETRY_LOAD_MILLIS) return 0
            val vlm = engine ?: withContext(Dispatchers.IO) { load() }?.also { engine = it }
            if (vlm == null) {
                loadFailedAt = System.currentTimeMillis()
                DotLog.e("summary: model could not be loaded")
                _error.value = "The AI model couldn't start"
                // Deleted from the Files app: show the download again.
                model.refresh()
                return 0
            }
            _error.value = null
            _progress.value = IndexProgress(running = true, total = jobs.size)
            val apps = appChoices()
            for (job in jobs) {
                if (isStopped() || System.currentTimeMillis() > deadline || blocked(userAsked, foreground)) break
                currentCoroutineContext().ensureActive()
                val itemStart = System.currentTimeMillis()
                try {
                    val image = withContext(Dispatchers.IO) { RgbImage.load(context.contentResolver, job.uri, VisionPrompts.SCREENSHOT_IMAGE_TOKENS * VisionPrompts.PIXELS_PER_IMAGE_TOKEN) }
                    val hints = AppHints.fromText(job.text)
                    val parsed = SummaryParser.parse(describe(vlm, image, VisionPrompts.screenshot(job.text, job.app, hints), power.gentle(userAsked)))
                    if (parsed == null) {
                        DotLog.w("summary: unreadable answer for ${job.name}")
                        repo.markSummaryFailed(job.id)
                    } else {
                        // The AI names the app; only an app on this phone (or the lock / home screen) counts.
                        // If its answer names none, an app whose own interface words are on screen does.
                        // The lock screen (big clock, weekday and date) is clear from the text alone.
                        val app = if (hints.firstOrNull() == AppNames.LOCK_SCREEN) {
                            AppNames.Choice(AppNames.LOCK_SCREEN, null)
                        } else {
                            AppNames.match(parsed.app, apps) ?: hints.firstNotNullOfOrNull { AppNames.match(it, apps) }
                        }
                        repo.saveSummary(job.id, parsed.copy(app = app?.label), app?.packageName)
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
        return written
    }

    /** The phone's apps, as the AI's answer is matched against them. */
    private fun appChoices(): List<AppNames.Choice> =
        runCatching { installed.launchable().map { AppNames.Choice(it.label, it.packageName) } }
            .onFailure { DotLog.w("summary: could not list installed apps", it) }
            .getOrDefault(emptyList())

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

    /** The model call, on the AI thread; [gentle] keeps it out of the way of the phone's other work. */
    private suspend fun describe(vlm: LlamaEngine, image: RgbImage, prompt: String, gentle: Boolean): String = LlmThread.run(vlm, gentle) {
        it.describe(image.bytes, image.width, image.height, prompt, VisionPrompts.SCREENSHOT_GRAMMAR, VisionPrompts.MAX_TOKENS)
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

/** After a failed model load, background work tries again this much later ("Do it now" tries right away). */
private const val RETRY_LOAD_MILLIS = 15 * 60_000L
