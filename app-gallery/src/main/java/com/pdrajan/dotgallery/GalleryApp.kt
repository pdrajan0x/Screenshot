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
import com.pdrajan.dot.media.MediaStoreSource
import com.pdrajan.dotgallery.data.GalleryDatabase
import com.pdrajan.dotgallery.data.GalleryRepository
import com.pdrajan.dotgallery.data.GallerySettings
import com.pdrajan.dotgallery.index.IndexEngine
import com.pdrajan.dotgallery.index.IndexScheduler
import com.pdrajan.dotgallery.index.ModelHub
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
    val scheduler = IndexScheduler(context) { settings.backlogWhileCharging.value }
    val locked = LockedFolder(context, repo)

    /** Ids the viewer swipes through, set by whichever screen opened it. */
    @Volatile var viewerIds: List<Long> = emptyList()

    private val _processingAll = MutableStateFlow(false)
    val processingAll: StateFlow<Boolean> = _processingAll.asStateFlow()
    private var job: Job? = null

    fun onForeground() {
        job?.cancel()
        job = scope.launch {
            runCatching { engine.sync() }
            // While the app is open, keep going batch after batch if the phone is charging
            // (or the user allowed processing on battery); otherwise just the newest batch.
            while (isActive && engine.process(limit = 80) > 0 && (isCharging() || !settings.backlogWhileCharging.value)) Unit
            if (repo.counts().pending > 0) scheduler.scheduleBacklog()
        }
    }

    private fun isCharging(): Boolean =
        runCatching { context.getSystemService(android.os.BatteryManager::class.java).isCharging }.getOrDefault(false)

    fun onBackground() {
        if (!_processingAll.value) job?.cancel()
    }

    fun processAllNow() {
        if (_processingAll.value) return
        job?.cancel()
        _processingAll.value = true
        job = scope.launch {
            try {
                runCatching { engine.sync() }
                while (isActive && engine.process(limit = 50) > 0) Unit
            } finally {
                _processingAll.value = false
            }
        }
    }

    fun stopProcessing() {
        job?.cancel()
        _processingAll.value = false
    }

    /** Re-reads MediaStore after the user changed files (delete, edit, restore). */
    fun refresh() {
        scope.launch { runCatching { engine.sync() } }
    }
}
