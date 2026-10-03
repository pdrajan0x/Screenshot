package com.pdrajan.dotgallery.index

import android.content.Context
import com.pdrajan.dot.engine.PhotoTagger
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

data class IndexProgress(val running: Boolean = false, val done: Int = 0, val total: Int = 0)

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
    private val lock = Mutex()

    suspend fun sync(): Int {
        if (MediaPermissions.access(context) == MediaAccess.NONE) return 0
        return repo.sync(media.allMedia())
    }

    suspend fun process(limit: Int, deadline: Long = Long.MAX_VALUE, isStopped: () -> Boolean = { false }): Int = lock.withLock {
        val pending = repo.pending(limit)
        if (pending.isEmpty()) return 0
        val clip = hub.clip() ?: return 0
        val tagger = hub.tagger() ?: return 0
        val faces = if (settings.people.value && hub.facesAvailable) runCatching { FaceEngine(context) }.getOrNull() else null
        val reader = if (settings.readText.value) TextReader(hindi = settings.readHindi.value) else null
        val analyzer = GalleryAnalyzer(context, clip, tagger, faces, reader)
        var done = 0
        _progress.value = IndexProgress(true, 0, pending.size)
        try {
            for (item in pending) {
                if (isStopped() || System.currentTimeMillis() > deadline) break
                currentCoroutineContext().ensureActive()
                try {
                    val a = withContext(Dispatchers.Default) { analyzer.analyze(item) }
                    repo.saveAnalysis(item.id, a)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (TextReader.isModelUnavailable(e)) break
                    repo.markFailed(item.id)
                }
                done++
                _progress.value = IndexProgress(true, done, pending.size)
                hub.touch()
            }
        } finally {
            faces?.close()
            reader?.close()
            _progress.value = IndexProgress()
        }
        done
    }
}
