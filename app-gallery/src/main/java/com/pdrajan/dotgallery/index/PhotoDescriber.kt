package com.pdrajan.dotgallery.index

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.pdrajan.dot.llm.LlamaEngine
import com.pdrajan.dot.llm.ModelBundle
import com.pdrajan.dot.llm.PowerGate
import com.pdrajan.dot.media.DotLog
import com.pdrajan.dot.ml.BitmapLoader
import com.pdrajan.dotgallery.data.GalleryRepository
import com.pdrajan.dotgallery.data.GallerySettings
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
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One-sentence photo descriptions ("A blue bus parked on a city street…") from a small on-device
 * vision model (LFM2.5-VL 450M via llama.cpp), searchable like everything else. The model is
 * loaded on demand and released a minute after the last photo; [PowerGate] decides when it may run.
 */
class PhotoDescriber(
    private val context: Context,
    private val repo: GalleryRepository,
    private val settings: GallerySettings,
    val model: ModelBundle,
    private val power: PowerGate,
    private val scope: CoroutineScope,
) {
    private val lock = Mutex()
    @Volatile private var engine: LlamaEngine? = null
    private var releaseJob: Job? = null
    private var lastBlocker: String? = null

    /** (done, total) while describing, for the status strip; null when idle. */
    private val _progress = MutableStateFlow<Pair<Int, Int>?>(null)
    val progress: StateFlow<Pair<Int, Int>?> = _progress.asStateFlow()

    /** The model is downloaded and descriptions are switched on. */
    val available: Boolean get() = settings.captionsEnabled.value && model.isReady()

    /** Describes up to [limit] photos taken after [since], newest first. Returns how many were written. */
    suspend fun process(
        limit: Int,
        since: Long = 0L,
        deadline: Long = Long.MAX_VALUE,
        userAsked: Boolean = false,
        isStopped: () -> Boolean = { false },
    ): Int = lock.withLock {
        if (!available || blocked(userAsked)) return 0
        val jobs = repo.captionQueue(limit, since)
        if (jobs.isEmpty()) return 0
        releaseJob?.cancel()
        var done = 0
        var written = 0
        val started = System.currentTimeMillis()
        _progress.value = 0 to jobs.size
        try {
            val vlm = engine ?: withContext(Dispatchers.IO) { load() }?.also { engine = it }
            if (vlm == null) {
                DotLog.e("describe: model could not be loaded")
                model.refresh()
                return 0
            }
            for (job in jobs) {
                if (isStopped() || System.currentTimeMillis() > deadline || blocked(userAsked)) break
                currentCoroutineContext().ensureActive()
                val itemStart = System.currentTimeMillis()
                try {
                    val rgb = withContext(Dispatchers.IO) { loadRgb(job.uri) }
                    val caption = clean(describe(vlm, rgb))
                    if (caption.isEmpty()) {
                        repo.markCaptionFailed(job.id)
                    } else {
                        repo.saveCaption(job.id, caption)
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
        written
    }

    private fun load(): LlamaEngine? {
        val files = model.files() ?: return null
        val threads = power.threads()
        val llm = LlamaEngine.load(context, files[0], contextTokens = 2048, threads = threads) ?: return null
        if (!llm.loadVision(files[1], threads, MAX_IMAGE_TOKENS)) {
            llm.close()
            return null
        }
        return llm
    }

    private class Rgb(val bytes: ByteArray, val width: Int, val height: Int)

    /** The photo at most [MAX_SIDE] px on its longest side, as RGB bytes. */
    private fun loadRgb(uri: Uri): Rgb {
        val decoded = BitmapLoader.load(context.contentResolver, uri, maxWidth = MAX_SIDE * 2, maxPixels = MAX_SIDE * MAX_SIDE * 4)
        val scale = MAX_SIDE.toFloat() / max(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, max(1, (decoded.width * scale).roundToInt()), max(1, (decoded.height * scale).roundToInt()), true)
                .also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }
        try {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val rgb = ByteArray(w * h * 3)
            var k = 0
            for (p in pixels) {
                rgb[k++] = (p shr 16).toByte()
                rgb[k++] = (p shr 8).toByte()
                rgb[k++] = p.toByte()
            }
            return Rgb(rgb, w, h)
        } finally {
            bitmap.recycle()
        }
    }

    /** Generation is a blocking native call: on cancellation, tell llama.cpp to stop right away. */
    private suspend fun describe(vlm: LlamaEngine, image: Rgb): String = coroutineScope {
        val work = async(Dispatchers.Default) { vlm.describe(image.bytes, image.width, image.height, INSTRUCTION, MAX_TOKENS) }
        try {
            work.await()
        } catch (e: CancellationException) {
            vlm.cancel()
            throw e
        }
    }

    /** Logs when descriptions pause or resume for battery or heat (once per change). */
    private fun blocked(userAsked: Boolean): Boolean {
        val reason = power.blocker(userAsked)
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

    /** After the model files are deleted. */
    suspend fun unload() = lock.withLock {
        engine?.close()
        engine = null
    }

    companion object {
        const val INSTRUCTION = "Describe this photo in one short sentence."
        private const val MAX_SIDE = 512
        // Enough detail for one sentence; fewer image tokens keep each photo to about a second or two.
        private const val MAX_IMAGE_TOKENS = 128
        private const val MAX_TOKENS = 60

        /** One tidy sentence: an answer cut off by the token limit loses its unfinished tail. */
        fun clean(raw: String): String {
            val text = raw.replace(Regex("\\s+"), " ").trim().trim('"')
            if (text.isEmpty()) return ""
            val end = text.indexOfAny(charArrayOf('.', '!', '?'))
            return when {
                end >= 0 -> text.substring(0, end + 1)
                text.length > 140 -> text.substring(0, text.lastIndexOf(' ', 140).takeIf { it > 40 } ?: 140) + "…"
                else -> text
            }
        }
    }
}
