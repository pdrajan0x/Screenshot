package com.pdrajan.dotscreenshots.index

import android.content.Context
import com.pdrajan.dot.engine.AppLookClassifier
import com.pdrajan.dot.engine.CategoryClassifier
import com.pdrajan.dot.ml.ClipModel
import com.pdrajan.dot.ml.PromptBank
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the CLIP model for the whole process. Sessions are released after a minute and a half
 * without use so the ~110 MB of model weights don't sit in RAM while the app is idle.
 */
class ModelHub(private val context: Context, private val scope: CoroutineScope) {

    val available: Boolean by lazy { ClipModel.isAvailable(context) }

    private val mutex = Mutex()
    private var model: ClipModel? = null
    private var classifier: CategoryClassifier? = null
    private var releaseJob: Job? = null

    suspend fun clip(): ClipModel? {
        if (!available) return null
        val m = mutex.withLock {
            model ?: withContext(Dispatchers.IO) { ClipModel.load(context) }.also { model = it }
        }
        touch()
        return m
    }

    suspend fun classifier(): CategoryClassifier? {
        classifier?.let { return it }
        val clip = clip() ?: return null
        return mutex.withLock {
            classifier ?: withContext(Dispatchers.Default) { PromptBank.classifier(context, clip) }.also {
                classifier = it
                // Prompt embeddings are cached on disk; indexing doesn't need the text encoder after this.
                clip.releaseText()
            }
        }
    }

    private var appLook: AppLookClassifier? = null

    /** CLIP prompts for "which app does this look like", embedded once and cached on disk. */
    suspend fun appLook(): AppLookClassifier? {
        appLook?.let { return it }
        val clip = clip() ?: return null
        return mutex.withLock {
            appLook ?: withContext(Dispatchers.Default) { PromptBank.appLook(context, clip) }.also {
                appLook = it
                clip.releaseText()
            }
        }
    }

    /** Postpones releasing the model; call while work is ongoing. */
    @Synchronized
    fun touch() {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(90_000)
            mutex.withLock { model?.close() }
        }
    }
}
