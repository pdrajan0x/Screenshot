package com.pdrajan.dotgallery

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import com.pdrajan.dot.llm.PowerGate
import com.pdrajan.dot.llm.SharedModel
import com.pdrajan.dot.media.MediaStoreSource
import com.pdrajan.dotgallery.data.GalleryDatabase
import com.pdrajan.dotgallery.data.GalleryRepository
import com.pdrajan.dotgallery.data.GallerySettings
import com.pdrajan.dotgallery.index.IndexEngine
import com.pdrajan.dotgallery.index.IndexScheduler
import com.pdrajan.dotgallery.index.ModelHub
import com.pdrajan.dotgallery.index.PhotoDescriber
import com.pdrajan.dotgallery.locked.LockedFolder
import com.pdrajan.dot.design.CrashLog
import com.pdrajan.dot.media.DotLog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class GalleryApp : Application(), SingletonImageLoader.Factory {

    lateinit var container: GalleryContainer
        private set

    override fun onCreate() {
        super.onCreate()
        DotLog.init(this)
        CrashLog.install(this)
        DotLog.i("start: Dot Gallery ${BuildConfig.VERSION_NAME} · Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        container = GalleryContainer(this)
        container.scheduler.watchForNewMedia()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = container.onForeground()
            override fun onStop(owner: LifecycleOwner) = container.onBackground()
        })
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            .crossfade(true)
            .build()
}

class GalleryContainer(val context: Context) {
    // Indexing failures must never take the app down; they're logged and retried later.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> CrashLog.warn(e) })
    val settings = GallerySettings(context)
    val repo = GalleryRepository(GalleryDatabase(context))
    val media = MediaStoreSource(context)
    val hub = ModelHub(context, scope)
    val engine = IndexEngine(context, repo, settings, hub, media)
    val scheduler = IndexScheduler(context) { !settings.processing.value.onBattery }
    val locked = LockedFolder(context, repo)
    val power = PowerGate(context) { settings.processing.value }

    /** The AI model both Dot apps share (Download/AI Models): descriptions and keywords. */
    val describerModel = SharedModel.bundle(context)
    val describer = PhotoDescriber(context, repo, describerModel, power, scope)
    private var downloadJob: Job? = null

    /** Ids the viewer swipes through, set by whichever screen opened it. */
    @Volatile var viewerIds: List<Long> = emptyList()

    private val _processingAll = MutableStateFlow(false)
    val processingAll: StateFlow<Boolean> = _processingAll.asStateFlow()
    private var job: Job? = null

    fun onForeground() {
        job?.cancel()
        job = scope.launch {
            runCatching { engine.sync() }
            while (isActive && processStep(userAsked = false, foreground = true) > 0) Unit
            val left = repo.counts().pending > 0 || (describer.available && repo.captionCounts().second > 0)
            if (left && settings.processing.value.background) scheduler.scheduleBacklog()
        }
    }

    /**
     * Finishes photos a few at a time, newest first: analyses up to [STEP] of them, then writes their
     * descriptions and keywords, so each photo is complete moments after it is picked up. Follows
     * Settings → Processing (battery level, battery saver, heat); [userAsked] is "Do it now".
     * Returns how many photos moved forward.
     */
    suspend fun processStep(
        userAsked: Boolean,
        foreground: Boolean,
        deadline: Long = Long.MAX_VALUE,
        isStopped: () -> Boolean = { false },
    ): Int {
        // New photos always; the older library only while charging (or as chosen in Settings), so
        // a photo is either finished or not started, never half done.
        val since = power.since(userAsked)
        val analysed = engine.process(limit = STEP, deadline = deadline, since = since, isStopped = isStopped)
        val described = describer.process(limit = STEP, since = since, deadline = deadline, userAsked = userAsked, foreground = foreground, isStopped = isStopped)
        return analysed + described
    }

    /** Left the app: work started from the screen stops (even "Do it now"), unless the phone is charging. */
    fun onBackground() {
        if (!_processingAll.value || !power.isCharging) job?.cancel()
    }

    fun processAllNow() {
        if (_processingAll.value) return
        job?.cancel()
        _processingAll.value = true
        job = scope.launch {
            try {
                runCatching { engine.sync() }
                while (isActive && processStep(userAsked = true, foreground = true) > 0) Unit
            } finally {
                _processingAll.value = false
            }
        }
    }

    /** The viewer's "Describe now": this photo, right away, whatever the battery rules say. */
    fun describeNow(id: Long) {
        scope.launch { describer.describeNow(id) }
    }

    /** Downloads the AI model (about 1.3 GB, resumable), then starts describing. */
    fun downloadDescriber() {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            describerModel.download()
            if (describerModel.isReady()) onForeground()
        }
    }

    fun pauseDescriberDownload() {
        downloadJob?.cancel()
    }

    fun stopProcessing() {
        job?.cancel()
        _processingAll.value = false
    }

    /** Re-reads MediaStore after the user changed files (delete, edit, restore). */
    fun refresh() {
        scope.launch { runCatching { engine.sync() } }
    }

    private companion object {
        const val STEP = 4
    }
}
