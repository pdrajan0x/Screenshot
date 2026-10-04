package com.pdrajan.dotscreenshots.index

import android.content.Context
import com.pdrajan.dot.engine.PictureWords
import com.pdrajan.dot.ml.FlorenceDescriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns Florence-2 (descriptions) for the whole process, once its files are downloaded
 * ([ModelDownload]). Sessions are released after a minute and a half without use so the model
 * doesn't sit in RAM while the app is idle.
 */
class ModelHub(private val context: Context, private val scope: CoroutineScope, val download: ModelDownload) {

    private val mutex = Mutex()
    private var florence: FlorenceDescriber? = null
    private var releaseJob: Job? = null

    /** Picture words and their other names, for search ("motorcycle" → bike). */
    val words: PictureWords? by lazy {
        runCatching { context.assets.open("words/picture_words.json").bufferedReader().use { PictureWords.parse(it.readText()) } }.getOrNull()
    }

    /** The description model, or null until it's downloaded and checked. */
    suspend fun florence(): FlorenceDescriber? {
        val dir = download.installedDir() ?: return null
        val d = mutex.withLock {
            florence ?: withContext(Dispatchers.IO) { FlorenceDescriber.load(context, dir) }.also { florence = it }
        }
        touch()
        return d
    }

    /** Postpones releasing the model; call while work is ongoing. */
    @Synchronized
    fun touch() {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(90_000)
            mutex.withLock { florence?.close() }
        }
    }
}
