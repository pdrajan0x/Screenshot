package com.pdrajan.dotscreenshots.index

import android.content.Context
import com.pdrajan.dot.media.MediaAccess
import com.pdrajan.dot.media.MediaPermissions
import com.pdrajan.dot.media.MediaStoreSource
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

data class IndexProgress(val running: Boolean = false, val done: Int = 0, val total: Int = 0)

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

    private val batchLock = Mutex()

    suspend fun sync(): ShotsRepository.SyncResult {
        if (MediaPermissions.access(context) == MediaAccess.NONE) return ShotsRepository.SyncResult(0, 0)
        return repo.sync(media.screenshots())
    }

    /**
     * Analyses up to [limit] pending screenshots, newest first. Stops early at [deadline]
     * (epoch millis) or when [isStopped] says so. Returns how many were processed.
     */
    suspend fun process(limit: Int, deadline: Long = Long.MAX_VALUE, isStopped: () -> Boolean = { false }): Int =
        batchLock.withLock {
            val pending = repo.pending(limit)
            if (pending.isEmpty()) return 0
            val clip = hub.clip() ?: return 0
            val classifier = hub.classifier() ?: return 0
            val reader = TextReader(hindi = settings.readHindi.value)
            val analyzer = ScreenshotAnalyzer(context, clip, reader, classifier)
            var done = 0
            _progress.value = IndexProgress(true, 0, pending.size)
            try {
                for (item in pending) {
                    if (isStopped() || System.currentTimeMillis() > deadline) break
                    currentCoroutineContext().ensureActive()
                    try {
                        val analysis = withContext(Dispatchers.Default) { analyzer.analyze(item.uri, item.name) }
                        repo.saveAnalysis(item.id, analysis)
                        recordTime(analysis.durationMs)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // OCR model still downloading: leave everything pending and try again later.
                        if (TextReader.isModelUnavailable(e)) break
                        repo.markFailed(item.id)
                    }
                    done++
                    _progress.value = IndexProgress(true, done, pending.size)
                    hub.touch()
                }
            } finally {
                reader.close()
                _progress.value = IndexProgress()
            }
            done
        }

    private fun recordTime(ms: Long) {
        val avg = settings.avgIndexMillis
        settings.avgIndexMillis = if (avg == 0L) ms else (avg * 9 + ms) / 10
    }
}
