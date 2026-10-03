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
import com.pdrajan.dot.engine.AppNames
import com.pdrajan.dot.llm.SharedModel
import com.pdrajan.dot.ml.InstalledApps
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
import kotlinx.coroutines.withContext

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
    val power = PowerGate(context) { settings.processing.value }

    val engine = IndexEngine(context, repo, settings, hub, media)
    val scheduler = IndexScheduler(context) { !settings.processing.value.onBattery }
    val installed = InstalledApps(context)

    /** The AI model both Dot apps share (Download/AI Models): titles, summaries, keywords, app names. */
    val model = SharedModel.bundle(context)
    val summaries = SummaryEngine(context, repo, model, power, scope, installed)

    /** Ids of the last search's results, so the viewer can swipe through them. */
    @Volatile var lastSearchIds: List<Long> = emptyList()

    private val _backlogRunning = MutableStateFlow(false)
    /** True while the user asked to process everything now (not just the newest batch). */
    val backlogRunning: StateFlow<Boolean> = _backlogRunning.asStateFlow()

    private var foregroundJob: Job? = null
    private var downloadJob: Job? = null

    /** App opened: every screenshot is read and summarised, newest first, a few at a time. */
    fun onForeground() {
        foregroundJob?.cancel()
        foregroundJob = scope.launch {
            model.refresh()
            runCatching { engine.sync() }
            while (isActive && processStep(userAsked = false, foreground = true) > 0) Unit
            val left = repo.counts().pending > 0 || (summaries.available && repo.summaryCounts().waiting > 0)
            if (left && settings.processing.value.background) scheduler.scheduleBacklog()
        }
    }

    /**
     * Finishes screenshots a few at a time: reads up to [STEP] (text, look, categories), then the AI
     * writes their title, summary and keywords and names the app, so each one is complete moments
     * after it is picked up. The AI part follows Settings → Processing; [userAsked] is "Do it now".
     */
    suspend fun processStep(
        userAsked: Boolean,
        foreground: Boolean,
        deadline: Long = Long.MAX_VALUE,
        isStopped: () -> Boolean = { false },
    ): Int {
        // New screenshots always; older ones only while charging (or as chosen in Settings), so a
        // screenshot is either finished or not started, never half done.
        val since = power.since(userAsked)
        val read = engine.process(limit = STEP, deadline = deadline, since = since, isStopped = isStopped)
        val summarised = summaries.process(limit = STEP, since = since, deadline = deadline, userAsked = userAsked, foreground = foreground, isStopped = isStopped)
        return read + summarised
    }

    /** Left the app: work started from the screen stops (even "Do it now"), unless the phone is charging. */
    fun onBackground() {
        if (!_backlogRunning.value || !power.isCharging) foregroundJob?.cancel()
    }

    /** "Do it now": everything, whatever the battery rules say, while the app stays open. */
    fun processAllNow() {
        if (_backlogRunning.value) return
        foregroundJob?.cancel()
        _backlogRunning.value = true
        foregroundJob = scope.launch {
            try {
                runCatching { engine.sync() }
                while (isActive && processStep(userAsked = true, foreground = true) > 0) Unit
            } finally {
                _backlogRunning.value = false
            }
        }
    }

    /** "Summarise now" in the viewer: this screenshot right away. */
    fun summariseNow(id: Long) {
        scope.launch { summaries.summariseNow(id) }
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

    /** The user picked the screenshot's app. */
    fun setApp(id: Long, label: String, packageName: String?) {
        scope.launch { repo.setAppByUser(id, label, packageName) }
    }

    /** Apps to choose from when correcting one: the library's apps first, then the phone's, then the lock / home screen. */
    suspend fun appChoices(): List<AppNames.Choice> = withContext(Dispatchers.IO) {
        val phone = runCatching { installed.launchable() }.getOrDefault(emptyList()).map { AppNames.Choice(it.label, it.packageName) }
        val byLabel = phone.associateBy { it.label }
        val inLibrary = repo.appLabels().map { (label, _) -> byLabel[label] ?: AppNames.Choice(label, null) }
        (inLibrary + phone + AppNames.Choice(AppNames.LOCK_SCREEN, null) + AppNames.Choice(AppNames.HOME_SCREEN, null))
            .distinctBy { it.label.lowercase() }
    }

    private companion object {
        const val STEP = 4
    }
}
