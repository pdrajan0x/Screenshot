package com.pdrajan.dotscreenshots

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.crossfade
import com.pdrajan.dot.design.CrashLog
import com.pdrajan.dot.llm.ModelDownloader
import com.pdrajan.dot.llm.Models
import com.pdrajan.dot.media.DotLog
import com.pdrajan.dot.media.MediaStoreSource
import com.pdrajan.dotscreenshots.data.Settings
import com.pdrajan.dotscreenshots.data.ShotsDatabase
import com.pdrajan.dotscreenshots.data.ShotsRepository
import com.pdrajan.dotscreenshots.index.IndexEngine
import com.pdrajan.dotscreenshots.index.IndexScheduler
import com.pdrajan.dotscreenshots.index.ModelHub
import com.pdrajan.dotscreenshots.index.PowerGate
import com.pdrajan.dotscreenshots.index.SummaryEngine
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

class DotScreenshotsApp : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        DotLog.init(this)
        CrashLog.install(this)
        DotLog.i("start: Dot Screenshots ${BuildConfig.VERSION_NAME} · Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        container = AppContainer(this)
        container.scheduler.watchForNewScreenshots()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = container.onForeground()
            override fun onStop(owner: LifecycleOwner) = container.onBackground()
        })
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
            .crossfade(true)
            .build()
}

/** Manual dependency container — the app is small enough not to need a DI framework. */
class AppContainer(val context: Context) {
    // Indexing failures must never take the app down; they're logged and retried later.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> CrashLog.warn(e) })
    val settings = Settings(context)
    val repo = ShotsRepository(ShotsDatabase(context))
    val media = MediaStoreSource(context)
    val hub = ModelHub(context, scope)
    val engine = IndexEngine(context, repo, settings, hub, media)
    val scheduler = IndexScheduler(context) { settings.backlogWhileCharging.value }
    val modelDownload = ModelDownloader(context, Models.SUMMARY)
    val power = PowerGate(context)
    val summaries = SummaryEngine(context, repo, settings, modelDownload, power, scope)

    init {
        // Earlier versions kept the model in app storage; it now lives in Download/AI Models.
        scope.launch { modelDownload.migrateToShared() }
    }

    /** Ids of the last search's results, so the viewer can swipe through them. */
    @Volatile var lastSearchIds: List<Long> = emptyList()

    private val _backlogRunning = MutableStateFlow(false)
    /** True while the user asked to process everything now (not just the newest batch). */
    val backlogRunning: StateFlow<Boolean> = _backlogRunning.asStateFlow()

    private var foregroundJob: Job? = null
    private var downloadJob: Job? = null

    /**
     * App opened: read new screenshots right away. With the phone charging (or processing on
     * battery allowed) keep going through the backlog and the summaries; on battery only the
     * newest screenshots get summaries.
     */
    fun onForeground() {
        foregroundJob?.cancel()
        foregroundJob = scope.launch {
            modelDownload.refresh()
            runCatching { engine.sync() }
            while (isActive && engine.process(limit = FOREGROUND_BATCH) > 0 && unrestricted()) Unit
            if (unrestricted()) {
                while (isActive && summaries.process(limit = SUMMARY_BATCH) > 0 && unrestricted()) Unit
            } else {
                // On battery only the newest few, and only when PowerGate allows.
                summaries.process(limit = 3, since = System.currentTimeMillis() - RECENT_MILLIS)
            }
            if (repo.counts().pending > 0 || (summaries.available && repo.summaryCounts().waiting > 0)) scheduler.scheduleBacklog()
        }
    }

    private fun unrestricted() = power.isCharging || !settings.backlogWhileCharging.value

    fun onBackground() {
        if (!_backlogRunning.value) foregroundJob?.cancel()
    }

    /** "Process now": read every pending screenshot, then write every summary, while the app stays open. */
    fun processAllNow() {
        if (_backlogRunning.value) return
        foregroundJob?.cancel()
        _backlogRunning.value = true
        foregroundJob = scope.launch {
            try {
                runCatching { engine.sync() }
                while (isActive && engine.process(limit = 50) > 0) Unit
                while (isActive && summaries.process(limit = SUMMARY_BATCH, userAsked = true) > 0) Unit
            } finally {
                _backlogRunning.value = false
            }
        }
    }

    fun stopProcessing() {
        foregroundJob?.cancel()
        _backlogRunning.value = false
    }

    /** Downloads the summary model (about 1 GB, resumable), then starts summarising. */
    fun downloadModel() {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            modelDownload.download()
            if (modelDownload.isReady()) onForeground()
        }
    }

    fun pauseDownload() {
        downloadJob?.cancel()
    }

    /** The user set a screenshot's app; guesses for the rest are redone with it as an example. */
    fun setApp(id: Long, label: String, packageName: String?) {
        scope.launch {
            repo.setAppByUser(id, label, packageName)
            engine.apps.run(relearn = true)
        }
    }

    /** Uses a copy of the model that is already on the phone (picked in the file picker). */
    fun useModelFile(uri: Uri) {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            modelDownload.adopt(uri)
            if (modelDownload.isReady()) onForeground()
        }
    }

    fun deleteModel() {
        downloadJob?.cancel()
        scope.launch {
            summaries.unload()
            modelDownload.delete()
        }
    }

    private companion object {
        const val FOREGROUND_BATCH = 60
        const val SUMMARY_BATCH = 10
        const val RECENT_MILLIS = 2L * 24 * 60 * 60_000
    }
}
