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
import com.pdrajan.dot.ml.InstalledApps
import com.pdrajan.dot.media.DotLog
import com.pdrajan.dot.media.MediaStoreSource
import com.pdrajan.dot.media.PowerGate
import com.pdrajan.dotscreenshots.data.Settings
import com.pdrajan.dotscreenshots.data.ShotsDatabase
import com.pdrajan.dotscreenshots.data.ShotsRepository
import com.pdrajan.dotscreenshots.index.IndexEngine
import com.pdrajan.dotscreenshots.index.IndexScheduler
import com.pdrajan.dotscreenshots.index.ModelHub
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

    val installed = InstalledApps(context)
    val engine = IndexEngine(context, repo, settings, hub, media) { installedChoices() }
    val scheduler = IndexScheduler(context) { !settings.processing.value.onBattery }

    /** Ids of the last search's results, so the viewer can swipe through them. */
    @Volatile var lastSearchIds: List<Long> = emptyList()

    private val _backlogRunning = MutableStateFlow(false)
    /** True while the user asked to process everything now (not just the newest batch). */
    val backlogRunning: StateFlow<Boolean> = _backlogRunning.asStateFlow()

    private var foregroundJob: Job? = null

    /** App opened: new screenshots are read right away, newest first, a few at a time. */
    fun onForeground() {
        foregroundJob?.cancel()
        foregroundJob = scope.launch {
            runCatching { engine.sync() }
            while (isActive && processStep(userAsked = false, foreground = true) > 0) Unit
            val counts = repo.counts()
            if (counts.pending + counts.updating > 0 && settings.processing.value.background) scheduler.scheduleBacklog()
        }
    }

    /**
     * Reads up to [STEP] screenshots (text, headings, picture keywords, app, categories), brings up
     * to [STEP] * 25 older ones up to date from what is already stored (no image is read again), and
     * re-reads the picture of up to [STEP] screenshots indexed with an older image model.
     * New screenshots always; older ones (and re-reading pictures) only while charging, or as chosen
     * in Settings → Processing; [userAsked] is "Do it now".
     */
    suspend fun processStep(
        userAsked: Boolean,
        foreground: Boolean,
        deadline: Long = Long.MAX_VALUE,
        isStopped: () -> Boolean = { false },
    ): Int {
        val since = power.since(userAsked)
        val read = engine.process(limit = STEP, deadline = deadline, since = since, isStopped = isStopped)
        val refreshed = engine.refreshWords(limit = STEP * 25)
        val reread = if (power.backlogAllowed(userAsked)) engine.reembed(limit = STEP, deadline = deadline, isStopped = isStopped) else 0
        return read + refreshed + reread
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

    fun stopProcessing() {
        foregroundJob?.cancel()
        _backlogRunning.value = false
    }

    /** The user picked the screenshot's app. */
    fun setApp(id: Long, label: String, packageName: String?) {
        scope.launch { repo.setAppByUser(id, label, packageName) }
    }

    /** The phone's launchable apps, for naming a recognised app the way the phone does. */
    fun installedChoices(): List<AppNames.Choice> =
        runCatching { installed.launchable() }.getOrDefault(emptyList()).map { AppNames.Choice(it.label, it.packageName) }

    /** Apps to choose from when correcting one: the library's apps first, then the phone's, then the lock / home screen. */
    suspend fun appChoices(): List<AppNames.Choice> = withContext(Dispatchers.IO) {
        val phone = installedChoices()
        val byLabel = phone.associateBy { it.label }
        val inLibrary = repo.appLabels().map { (label, _) -> byLabel[label] ?: AppNames.Choice(label, null) }
        (inLibrary + phone + AppNames.Choice(AppNames.LOCK_SCREEN, null) + AppNames.Choice(AppNames.HOME_SCREEN, null))
            .distinctBy { it.label.lowercase() }
    }

    private companion object {
        const val STEP = 4
    }
}
