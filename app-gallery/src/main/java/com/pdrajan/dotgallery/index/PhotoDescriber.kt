package com.pdrajan.dotgallery.index

import android.content.Context
import com.pdrajan.dot.engine.VisionPrompts
import com.pdrajan.dot.llm.LlamaEngine
import com.pdrajan.dot.llm.LlmThread
import com.pdrajan.dot.llm.ModelBundle
import com.pdrajan.dot.llm.PowerGate
import com.pdrajan.dot.media.DotLog
import com.pdrajan.dot.ml.RgbImage
import com.pdrajan.dotgallery.data.GalleryRepository
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
 * A one-sentence description and keywords for every photo ("A blue bus parked on a city street…";
 * "bus, street, people…") from the on-device vision model both apps share (LFM2.5-VL 1.6B via
 * llama.cpp). Search relies on them. The model is loaded on demand and released a minute after the
 * last photo; [PowerGate] decides when it may run.
 */
class PhotoDescriber(
    private val context: Context,
    private val repo: GalleryRepository,
    val model: ModelBundle,
    private val power: PowerGate,
    private val scope: CoroutineScope,
) {
    private val lock = Mutex()
    @Volatile private var engine: LlamaEngine? = null
    private var releaseJob: Job? = null
    private var lastBlocker: String? = null
    /** When loading the model last failed: background work waits a while before trying again. */
    @Volatile private var loadFailedAt = 0L

    /** (done, total) while describing, for the status strip; null when idle. */
    private val _progress = MutableStateFlow<Pair<Int, Int>?>(null)
    val progress: StateFlow<Pair<Int, Int>?> = _progress.asStateFlow()

    /** Why the model couldn't run, for the status strip; null when it works. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** The model is downloaded. */
    val available: Boolean get() = model.isReady()

    /** Describes up to [limit] photos taken after [since], newest first. Returns how many were written. */
    suspend fun process(
        limit: Int,
        since: Long = 0L,
        deadline: Long = Long.MAX_VALUE,
        userAsked: Boolean = false,
        foreground: Boolean = true,
        isStopped: () -> Boolean = { false },
    ): Int = lock.withLock {
        if (!available || blocked(userAsked, foreground)) return 0
        val jobs = repo.captionQueue(limit, since)
        if (jobs.isEmpty()) return 0
        describeAll(jobs, deadline, userAsked, foreground, isStopped)
    }

    /** "Describe now" in the viewer: this photo right away (the user asked, so battery rules don't apply). */
    suspend fun describeNow(id: Long): Boolean = lock.withLock {
        val job = repo.captionJob(id) ?: return false
        available && !blocked(userAsked = true, foreground = true) && describeAll(listOf(job), Long.MAX_VALUE, userAsked = true, foreground = true) { false } > 0
    }

    private suspend fun describeAll(
        jobs: List<GalleryRepository.CaptionJob>,
        deadline: Long,
        userAsked: Boolean,
        foreground: Boolean,
        isStopped: () -> Boolean,
    ): Int {
        releaseJob?.cancel()
        var done = 0
        var written = 0
        val started = System.currentTimeMillis()
        _progress.value = 0 to jobs.size
        try {
            if (engine == null && !userAsked && System.currentTimeMillis() - loadFailedAt < RETRY_LOAD_MILLIS) return 0
            val vlm = engine ?: withContext(Dispatchers.IO) { load() }?.also { engine = it }
            if (vlm == null) {
                loadFailedAt = System.currentTimeMillis()
                DotLog.e("describe: model could not be loaded")
                _error.value = "The AI model couldn't start"
                model.refresh()
                return 0
            }
            _error.value = null
            for (job in jobs) {
                if (isStopped() || System.currentTimeMillis() > deadline || blocked(userAsked, foreground)) break
                currentCoroutineContext().ensureActive()
                val itemStart = System.currentTimeMillis()
                try {
                    val rgb = withContext(Dispatchers.IO) { RgbImage.load(context.contentResolver, job.uri, VisionPrompts.PHOTO_IMAGE_TOKENS * VisionPrompts.PIXELS_PER_IMAGE_TOKEN) }
                    val answer = VisionPrompts.parsePhoto(describe(vlm, rgb, power.gentle(userAsked)))
                    if (answer == null) {
                        repo.markCaptionFailed(job.id)
                    } else {
                        repo.saveCaption(job.id, answer.description, answer.keywords)
                        written++
                        if (written == 1) DotLog.i("describe: first photo took ${System.currentTimeMillis() - itemStart} ms")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    DotLog.w("describe: failed on ${job.name}: ${e.javaClass.simpleName}: ${e.message}")
                    repo.markCaptionFailed(job.id)
                }
                done++
                _progress.value = done to jobs.size
            }
            DotLog.i("describe: $written written, ${done - written} failed in ${System.currentTimeMillis() - started} ms")
        } catch (e: CancellationException) {
            DotLog.i("describe: stopped after $done photos")
            throw e
        } finally {
            _progress.value = null
            scheduleRelease()
        }
        return written
    }

    private fun load(): LlamaEngine? {
        val files = model.files() ?: return null
        val threads = power.threads()
        val llm = LlamaEngine.load(context, files[0], contextTokens = 2048, threads = threads) ?: return null
        if (!llm.loadVision(files[1], threads, VisionPrompts.PHOTO_IMAGE_TOKENS)) {
            llm.close()
            return null
        }
        return llm
    }

    /** The model call, on the AI thread; [gentle] keeps it out of the way of the phone's other work. */
    private suspend fun describe(vlm: LlamaEngine, image: RgbImage, gentle: Boolean): String = LlmThread.run(vlm, gentle) {
        it.describe(image.bytes, image.width, image.height, VisionPrompts.PHOTO, VisionPrompts.PHOTO_GRAMMAR, VisionPrompts.MAX_TOKENS)
    }

    /** Logs when descriptions pause or resume for battery or heat (once per change). */
    private fun blocked(userAsked: Boolean, foreground: Boolean): Boolean {
        val reason = power.blocker(userAsked, foreground)
        if (reason != lastBlocker) {
            DotLog.i(if (reason != null) "describe: paused ($reason)" else "describe: allowed again")
            lastBlocker = reason
        }
        return reason != null
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
