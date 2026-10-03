package com.pdrajan.dotgallery.index

import android.content.Context
import com.pdrajan.dot.engine.PhotoTagger
import com.pdrajan.dot.media.DotLog
import com.pdrajan.dot.media.MediaAccess
import com.pdrajan.dot.media.MediaPermissions
import com.pdrajan.dot.media.MediaStoreSource
import com.pdrajan.dot.ml.ClipModel
import com.pdrajan.dot.ml.PromptBank
import com.pdrajan.dot.ml.TextReader
import com.pdrajan.dotgallery.data.GalleryRepository
import com.pdrajan.dotgallery.data.GallerySettings
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

/** [preparing]: models are loading (the first run also embeds the tag prompts, ~a minute). */
data class IndexProgress(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val preparing: Boolean = false)

/** Holds the models for the process; releases them after a period without use. */
class ModelHub(private val context: Context, private val scope: CoroutineScope) {
    val clipAvailable: Boolean by lazy { ClipModel.isAvailable(context) }
    val facesAvailable: Boolean by lazy { FaceEngine.isAvailable(context) }

    private val mutex = Mutex()
    private var clip: ClipModel? = null
    private var tagger: PhotoTagger? = null
    private var releaseJob: Job? = null

    suspend fun clip(): ClipModel? {
        if (!clipAvailable) return null
        val m = mutex.withLock { clip ?: withContext(Dispatchers.IO) { ClipModel.load(context) }.also { clip = it } }
        touch()
        return m
    }

    suspend fun tagger(): PhotoTagger? {
        tagger?.let { return it }
        val c = clip() ?: return null
        return mutex.withLock {
            tagger ?: withContext(Dispatchers.Default) { PromptBank.photoTagger(context, c) }.also {
                tagger = it
                c.releaseText()
            }
        }
    }

    @Synchronized
    fun touch() {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(90_000)
            mutex.withLock { clip?.close() }
        }
    }
}

class IndexEngine(
    private val context: Context,
    private val repo: GalleryRepository,
    private val settings: GallerySettings,
    private val hub: ModelHub,
    private val media: MediaStoreSource,
) {
    private val _progress = MutableStateFlow(IndexProgress())
    val progress: StateFlow<IndexProgress> = _progress.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    /** Why the last batch stopped or what failed in it, for the status strip; null when it went fine. */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    private val lock = Mutex()

    suspend fun sync(): Int {
        if (MediaPermissions.access(context) == MediaAccess.NONE) {
            DotLog.w("sync: no photo access")
            return 0
        }
        val started = System.currentTimeMillis()
        val items = media.allMedia()
        val added = repo.sync(items)
        val counts = repo.counts()
        DotLog.i("sync: ${items.size} on device, $added new · indexed ${counts.indexed}, pending ${counts.pending} (${System.currentTimeMillis() - started} ms)")
        return added
    }

    /** Analyses up to [limit] pending items; returns how many were indexed successfully. */
    suspend fun process(limit: Int, deadline: Long = Long.MAX_VALUE, isStopped: () -> Boolean = { false }): Int = lock.withLock {
        requeueWhenTextModelReady()
        val pending = repo.pending(limit)
        if (pending.isEmpty()) return 0
        DotLog.i("process: ${pending.size} pending in this batch")
        _progress.value = IndexProgress(running = true, total = pending.size, preparing = true)
        var done = 0
        var failed = 0
        val batchStart = System.currentTimeMillis()
        try {
            var t = System.currentTimeMillis()
            val clip = hub.clip()
            if (clip == null) {
                DotLog.e("process: CLIP model missing from the APK")
                _lastError.value = "AI model missing from this build"
                return 0
            }
            DotLog.i("process: CLIP ready in ${System.currentTimeMillis() - t} ms")
            t = System.currentTimeMillis()
            val tagger = hub.tagger() ?: return 0
            DotLog.i("process: tag prompts ready in ${System.currentTimeMillis() - t} ms")
            val faces = if (settings.people.value && hub.facesAvailable) {
                runCatching { FaceEngine(context) }.onFailure { DotLog.e("process: face engine unavailable", it) }.getOrNull()
            } else {
                null
            }
            val readText = settings.readText.value
            val reader = if (readText) {
                runCatching { TextReader(hindi = settings.readHindi.value) }
                    .onFailure { DotLog.e("process: text recognizer unavailable; indexing without text for now", it) }
                    .getOrNull()
            } else {
                null
            }
            val analyzer = GalleryAnalyzer(context, clip, tagger, faces, reader, readText)
            _progress.value = IndexProgress(running = true, total = pending.size)
            try {
                for (item in pending) {
                    if (isStopped() || System.currentTimeMillis() > deadline) {
                        DotLog.i("process: stopped early (deadline or worker stopped)")
                        break
                    }
                    currentCoroutineContext().ensureActive()
                    val itemStart = System.currentTimeMillis()
                    try {
                        val a = withContext(Dispatchers.Default) { analyzer.analyze(item) }
                        repo.saveAnalysis(item.id, a)
                        if (done == 0) DotLog.i("process: first item took ${System.currentTimeMillis() - itemStart} ms (includes loading the image model)")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        if (TextReader.isModelUnavailable(e)) {
                            DotLog.w("process: text model still downloading; retrying later", e)
                            break
                        }
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
            } finally {
                faces?.close()
                reader?.close()
            }
            if (failed == 0) _lastError.value = null
            DotLog.i("process: ${done - failed} indexed, $failed failed in ${System.currentTimeMillis() - batchStart} ms")
        } catch (e: CancellationException) {
            DotLog.i("process: cancelled after $done items (app left the screen or work was stopped)")
            throw e
        } catch (e: Throwable) {
            DotLog.e("process: stopped before indexing", e)
            _lastError.value = describe(e)
        } finally {
            _progress.value = IndexProgress()
        }
        // Successes only, so callers looping "while > 0" stop when a whole batch fails.
        done - failed
    }

    /** Photos indexed while the text model was downloading get read again once it's ready. */
    private suspend fun requeueWhenTextModelReady() {
        if (!settings.readText.value) return
        val waiting = repo.ocrPendingCount()
        if (waiting == 0) return
        val ready = runCatching {
            withContext(Dispatchers.Default) { TextReader(hindi = settings.readHindi.value).use { it.isModelReady() } }
        }.getOrDefault(false)
        if (ready) DotLog.i("process: text model ready; re-reading ${repo.requeueOcrPending()} photos")
        else DotLog.i("process: text model still downloading ($waiting photos waiting for text)")
    }

    private fun describe(e: Throwable): String {
        var root = e
        while (root.cause != null && root.cause !== root) root = root.cause!!
        return "${root.javaClass.simpleName}: ${root.message ?: "no message"}".take(160)
    }
}
