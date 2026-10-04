package com.pdrajan.dotscreenshots.index

import android.content.Context
import android.os.Process
import com.pdrajan.dot.engine.AppNames
import com.pdrajan.dot.engine.CategoryClassifier
import com.pdrajan.dot.media.DotLog
import com.pdrajan.dot.media.MediaAccess
import com.pdrajan.dot.media.MediaPermissions
import com.pdrajan.dot.media.MediaStoreSource
import com.pdrajan.dot.ml.BitmapLoader
import com.pdrajan.dot.ml.ScreenshotAnalyzer
import com.pdrajan.dot.ml.TextReader
import com.pdrajan.dotscreenshots.data.Settings
import com.pdrajan.dotscreenshots.data.ShotsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Threads that read and describe screenshots, below the UI's priority so scrolling and animations
 * stay smooth while they work. ONNX Runtime's own threads, started from these, inherit it.
 */
private val WorkDispatcher: CoroutineDispatcher = Executors.newFixedThreadPool(2) { r ->
    Thread({
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        r.run()
    }, "dot-work").apply { isDaemon = true }
}.asCoroutineDispatcher()

/** Whether screenshots can be read now: with text, not yet (the text model is on its way), or without text. */
enum class TextGate { READY, WAIT, WITHOUT }

/** Syncs MediaStore → database and analyses pending screenshots, one batch at a time. */
class IndexEngine(
    private val context: Context,
    private val repo: ShotsRepository,
    private val settings: Settings,
    private val hub: ModelHub,
    private val media: MediaStoreSource,
    /** The phone's apps, so a recognised app shows under the phone's name for it. */
    private val installed: () -> List<AppNames.Choice>,
    /** Whether Play services' text model is here (see [TextGate]). */
    private val textGate: suspend () -> TextGate,
) {
    private val _lastError = MutableStateFlow<String?>(null)
    /** Why the last batch stopped or what failed in it, for the status strip; null when it went fine. */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val batchLock = Mutex()

    suspend fun sync(): ShotsRepository.SyncResult {
        if (MediaPermissions.access(context) == MediaAccess.NONE) {
            DotLog.w("sync: no photo access")
            return ShotsRepository.SyncResult(0, 0)
        }
        val started = System.currentTimeMillis()
        val shots = media.screenshots()
        val result = repo.sync(shots)
        val counts = repo.counts()
        DotLog.i(
            "sync: ${shots.size} screenshots on device, ${result.added} new, ${result.removed} removed · " +
                "indexed ${counts.indexed}, pending ${counts.pending}, failed ${counts.failed} (${System.currentTimeMillis() - started} ms)",
        )
        return result
    }

    /**
     * Analyses up to [limit] pending screenshots, newest first. Stops early at [deadline]
     * (epoch millis) or when [isStopped] says so. Returns how many were indexed successfully.
     */
    suspend fun process(limit: Int, deadline: Long = Long.MAX_VALUE, since: Long = 0L, isStopped: () -> Boolean = { false }): Int =
        batchLock.withLock {
            // Most wake-ups (any new image on the phone) find nothing to do: check before loading anything.
            if (repo.ocrPendingCount() == 0 && repo.pending(1, since).isEmpty()) return 0
            // Reading before the text model is here would index screenshots without their words: wait for it.
            val gate = textGate()
            if (gate == TextGate.WAIT) return 0
            var done = 0
            var failed = 0
            val batchStart = System.currentTimeMillis()
            var reader: TextReader? = null
            try {
                val textReader = if (gate == TextGate.WITHOUT) null else runCatching { TextReader(hindi = settings.readHindi.value) }
                    .onFailure { DotLog.e("process: text recognizer unavailable; indexing without text for now", it) }
                    .getOrNull()
                reader = textReader
                // Screenshots indexed without text (the model wasn't available then) are read again now.
                if (textReader != null && repo.ocrPendingCount() > 0) {
                    DotLog.i("process: text model ready; re-reading ${repo.requeueOcrPending()} screenshots")
                }
                val pending = repo.pending(limit, since)
                if (pending.isEmpty()) return 0
                DotLog.i("process: ${pending.size} pending in this batch")
                val analyzer = ScreenshotAnalyzer(context, textReader, installed())
                var noText = 0
                for (item in pending) {
                    if (isStopped() || System.currentTimeMillis() > deadline) {
                        DotLog.i("process: stopped early (deadline or worker stopped)")
                        break
                    }
                    currentCoroutineContext().ensureActive()
                    try {
                        val analysis = withContext(WorkDispatcher) { analyzer.analyze(item.uri, item.name) }
                        repo.saveAnalysis(item.id, analysis)
                        recordTime(analysis.durationMs)
                        if (analysis.ocrPending) noText++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        failed++
                        if (failed <= 3) DotLog.e("process: failed on ${item.name} (${item.uri})", e)
                        else DotLog.w("process: failed on ${item.name}: ${e.javaClass.simpleName}: ${e.message}")
                        _lastError.value = errorText(e)
                        repo.markFailed(item.id)
                    }
                    done++
                    hub.touch()
                }
                if (failed == 0) _lastError.value = null
                DotLog.i(
                    "process: ${done - failed} indexed, $failed failed in ${System.currentTimeMillis() - batchStart} ms" +
                        if (noText > 0) " · $noText without text (no text model)" else "",
                )
            } catch (e: CancellationException) {
                DotLog.i("process: cancelled after $done screenshots (app left the screen or work was stopped)")
                throw e
            } catch (e: Throwable) {
                DotLog.e("process: stopped before indexing", e)
                _lastError.value = errorText(e)
            } finally {
                reader?.close()
            }
            // Successes only, so callers looping "while > 0" stop when a whole batch fails.
            done - failed
        }

    /**
     * Describes up to [limit] read screenshots with Florence-2 (taken from [since] on, newest
     * first): a description and the objects in their pictures. Returns how many were done.
     */
    suspend fun describe(limit: Int, since: Long = 0L, deadline: Long = Long.MAX_VALUE, isStopped: () -> Boolean = { false }): Int =
        batchLock.withLock {
            val jobs = repo.describeQueue(limit, since)
            if (jobs.isEmpty()) return 0
            val florence = runCatching { withContext(WorkDispatcher) { hub.florence() } }
                .onFailure {
                    DotLog.e("describe: couldn't load the description model", it)
                    _lastError.value = "Description model: ${errorText(it)}"
                }
                .getOrNull() ?: return 0
            var done = 0
            val started = System.currentTimeMillis()
            for (job in jobs) {
                if (isStopped() || System.currentTimeMillis() > deadline) break
                currentCoroutineContext().ensureActive()
                try {
                    val d = withContext(WorkDispatcher) {
                        val bitmap = BitmapLoader.load(context.contentResolver, job.uri)
                        try {
                            florence.describe(bitmap, job.text)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    repo.saveDescription(job.id, d.text, d.objects, CategoryClassifier.classify(job.text + "\n" + d.text, job.app))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (e is OutOfMemoryError || e.javaClass.name.startsWith("ai.onnxruntime")) {
                        // The model itself failed (memory, a session that wouldn't load): try again later.
                        DotLog.e("describe: the description model failed; trying again later", e)
                        _lastError.value = "Description model: ${errorText(e)}"
                        break
                    }
                    DotLog.w("describe: couldn't describe ${job.uri}: ${e.javaClass.simpleName}: ${e.message}")
                    // This picture can't be read: don't retry forever; it stays findable by its text and keywords.
                    repo.saveDescription(job.id, null, emptyList())
                }
                done++
                hub.touch()
            }
            DotLog.i("describe: described $done screenshots in ${System.currentTimeMillis() - started} ms")
            done
        }

    private fun errorText(e: Throwable): String {
        var root = e
        while (root.cause != null && root.cause !== root) root = root.cause!!
        return "${root.javaClass.simpleName}: ${root.message ?: "no message"}".take(160)
    }

    private fun recordTime(ms: Long) {
        val avg = settings.avgIndexMillis
        settings.avgIndexMillis = if (avg == 0L) ms else (avg * 9 + ms) / 10
    }
}
