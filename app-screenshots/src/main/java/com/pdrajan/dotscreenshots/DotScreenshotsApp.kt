package com.pdrajan.dotscreenshots

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
import com.pdrajan.dot.design.CrashLog
import com.pdrajan.dot.llm.SharedModel
import com.pdrajan.dot.media.DotLog
import com.pdrajan.dot.media.MediaStoreSource
import com.pdrajan.dotscreenshots.data.Settings
import com.pdrajan.dotscreenshots.data.ShotsDatabase
import com.pdrajan.dotscreenshots.data.ShotsRepository
import com.pdrajan.dotscreenshots.index.IndexEngine
import com.pdrajan.dotscreenshots.index.IndexScheduler
import com.pdrajan.dotscreenshots.index.ModelHub
import com.pdrajan.dot.llm.PowerGate
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
            override fun onStart(owner: LifecycleOwner) {
                container.visible = true
                container.onForeground()
            }

            override fun onStop(owner: LifecycleOwner) {
                container.visible = false
                container.onBackground()
            }
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
    val power = PowerGate(context) { settings.processing.value }

    /** The app is on screen (between onStart and onStop). */
    @Volatile var visible = false

    // Library-wide passes (re-guessing every screenshot's app) wait until the app is open or charging.
    val engine = IndexEngine(context, repo, settings, hub, media) { visible || power.isCharging }
    val scheduler = IndexScheduler(context) { !settings.processing.value.onBattery }

    /** The AI model both Dot apps share (Download/AI Models): titles, summaries, keywords, app names. */
    val model = SharedModel.bundle(context)
    val summaries = SummaryEngine(context, repo, model, power, scope)

    /** Ids of the last search's results, so the viewer can swipe through them. */
    @Volatile var lastSearchIds: List<Long> = emptyList()

    private val _backlogRunning = MutableStateFlow(false)
    /** True while the user asked to process everything now (not just the newest batch). */
    val backlogRunning: StateFlow<Boolean> = _backlogRunning.asStateFlow()

    private var foregroundJob: Job? = null
    private var downloadJob: Job? = null

    /**
     * App opened: read new screenshots right away (quick). The AI summaries, and the older
     * screenshots, follow Settings → Processing.
     */
    fun onForeground() {
        foregroundJob?.cancel()
        foregroundJob = scope.launch {
            model.refresh()
            runCatching { engine.sync() }
            engine.process(limit = FOREGROUND_BATCH)
            while (isActive && allowed() && engine.process(limit = FOREGROUND_BATCH) > 0) Unit
            var summarised = 0
            while (isActive) {
                val n = summaries.process(limit = SUMMARY_BATCH)
                if (n == 0) break
                summarised += n
            }
            if (summarised > 0) engine.apps.run()
            val left = repo.counts().pending > 0 || (summaries.available && repo.summaryCounts().waiting > 0)
            if (left && settings.processing.value.background) scheduler.scheduleBacklog()
        }
    }

    private fun allowed() = power.blocker() == null

    /** Left the app: work started from the screen stops (even "Process now"), unless the phone is charging. */
    fun onBackground() {
        if (!_backlogRunning.value || !power.isCharging) foregroundJob?.cancel()
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
                engine.apps.run()
            } finally {
                _backlogRunning.value = false
            }
        }
    }

    fun stopProcessing() {
        foregroundJob?.cancel()
        _backlogRunning.value = false
    }

    /** Downloads the AI model (about 1.3 GB, resumable), then starts summarising. */
    fun downloadModel() {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            model.download()
            if (model.isReady()) onForeground()
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

    private companion object {
        const val FOREGROUND_BATCH = 60
        const val SUMMARY_BATCH = 10
    }
}
