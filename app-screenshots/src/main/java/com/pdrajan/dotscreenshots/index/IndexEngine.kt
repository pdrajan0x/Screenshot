package com.pdrajan.dotscreenshots.index

import android.content.Context
import com.pdrajan.dot.media.DotLog
import com.pdrajan.dot.media.MediaAccess
import com.pdrajan.dot.media.MediaPermissions
import com.pdrajan.dot.media.MediaStoreSource
import com.pdrajan.dot.ml.ForegroundAppResolver
import com.pdrajan.dot.ml.ScreenshotAnalyzer
import com.pdrajan.dot.ml.TextReader
import com.pdrajan.dotscreenshots.data.Settings
import com.pdrajan.dotscreenshots.data.ShotsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** [preparing]: models are loading (the first run also embeds the category prompts). */
data class IndexProgress(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val preparing: Boolean = false)

/** Syncs MediaStore → database and analyses pending screenshots, one batch at a time. */
class IndexEngine(
    private val context: Context,
    private val repo: ShotsRepository,
    private val settings: Settings,
    private val hub: ModelHub,
    private val media: MediaStoreSource,
) {
    private val _progress = MutableStateFlow(IndexProgress())
    val progress: StateFlow<IndexProgress> = _progress.asStateFlow()
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
        apps.run()
        val counts = repo.counts()
        DotLog.i(
            "sync: ${shots.size} screenshots on device, ${result.added} new, ${result.removed} removed · " +
                "indexed ${counts.indexed}, pending ${counts.pending}, failed ${counts.failed} (${System.currentTimeMillis() - started} ms)",
        )
        return result
    }

    /** Which app each screenshot came from, from the system's usage history (needs Usage access). */
    val foreground = ForegroundAppResolver(context)

    /** A source app for every screenshot: exact from usage history, or the best guess. */
    val apps = AppIdentification(context, repo, hub, foreground)

    /**
     * Analyses up to [limit] pending screenshots, newest first. Stops early at [deadline]
     * (epoch millis) or when [isStopped] says so. Returns how many were indexed successfully.
     */
    suspend fun process(limit: Int, deadline: Long = Long.MAX_VALUE, isStopped: () -> Boolean = { false }): Int =
        batchLock.withLock {
            var done = 0
            var failed = 0
            val batchStart = System.currentTimeMillis()
            var reader: TextReader? = null
            try {
                val textReader = runCatching { TextReader(hindi = settings.readHindi.value) }
                    .onFailure { DotLog.e("process: text recognizer unavailable; indexing without text for now", it) }
                    .getOrNull()
                reader = textReader
                // Screenshots indexed while the text model was downloading get read again once it's ready.
                val waitingForText = repo.ocrPendingCount()
                if (waitingForText > 0 && textReader != null) {
                    if (withContext(Dispatchers.Default) { textReader.isModelReady() }) {
                        DotLog.i("process: text model ready; re-reading ${repo.requeueOcrPending()} screenshots")
                    } else {
                        DotLog.i("process: text model still downloading ($waitingForText screenshots waiting for text)")
                    }
                }
                val pending = repo.pending(limit)
                if (pending.isEmpty()) return 0
                DotLog.i("process: ${pending.size} pending in this batch")
                _progress.value = IndexProgress(running = true, total = pending.size, preparing = true)
                var t = System.currentTimeMillis()
                val clip = hub.clip()
                if (clip == null) {
                    DotLog.e("process: CLIP model missing from the APK")
                    _lastError.value = "AI model missing from this build"
                    return 0
                }
                DotLog.i("process: CLIP ready in ${System.currentTimeMillis() - t} ms")
                t = System.currentTimeMillis()
                val classifier = hub.classifier() ?: return 0
                DotLog.i("process: category prompts ready in ${System.currentTimeMillis() - t} ms")
                val appLook = runCatching { hub.appLook() }.onFailure { DotLog.e("process: app look prompts unavailable", it) }.getOrNull()
                val analyzer = ScreenshotAnalyzer(context, clip, textReader, classifier, appLook, foreground.takeIf { it.hasAccess() })
                _progress.value = IndexProgress(running = true, total = pending.size)
                var noText = 0
                for (item in pending) {
                    if (isStopped() || System.currentTimeMillis() > deadline) {
                        DotLog.i("process: stopped early (deadline or worker stopped)")
                        break
                    }
                    currentCoroutineContext().ensureActive()
                    try {
                        val analysis = withContext(Dispatchers.Default) { analyzer.analyze(item.uri, item.name, item.takenAt) }
                        repo.saveAnalysis(item.id, analysis)
                        recordTime(analysis.durationMs)
                        if (analysis.ocrPending) noText++
                        if (done == 0) DotLog.i("process: first screenshot took ${analysis.durationMs} ms (includes loading the image model)")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        failed++
                        if (failed <= 3) DotLog.e("process: failed on ${item.name} (${item.uri})", e)
                        else DotLog.w("process: failed on ${item.name}: ${e.javaClass.simpleName}: ${e.message}")
                        _lastError.value = describe(e)
                        repo.markFailed(item.id)
                    }
                    done++
                    _progress.value = IndexProgress(true, done, pending.size)
                    hub.touch()
                }
                if (failed == 0) _lastError.value = null
                DotLog.i(
                    "process: ${done - failed} indexed, $failed failed in ${System.currentTimeMillis() - batchStart} ms" +
                        if (noText > 0) " · $noText without text (text model still downloading)" else "",
                )
            } catch (e: CancellationException) {
                DotLog.i("process: cancelled after $done screenshots (app left the screen or work was stopped)")
                throw e
            } catch (e: Throwable) {
                DotLog.e("process: stopped before indexing", e)
                _lastError.value = describe(e)
            } finally {
                reader?.close()
                _progress.value = IndexProgress()
            }
            // New screenshots without an exact app get their best guess.
            if (done - failed > 0) apps.run()
            // Successes only, so callers looping "while > 0" stop when a whole batch fails.
            done - failed
        }

    private fun describe(e: Throwable): String {
        var root = e
        while (root.cause != null && root.cause !== root) root = root.cause!!
        return "${root.javaClass.simpleName}: ${root.message ?: "no message"}".take(160)
    }

    private fun recordTime(ms: Long) {
        val avg = settings.avgIndexMillis
        settings.avgIndexMillis = if (avg == 0L) ms else (avg * 9 + ms) / 10
    }
}
