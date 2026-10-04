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
import com.pdrajan.dotscreenshots.index.ModelDownload
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
    val hub = ModelHub(context, scope, ModelDownload(context))
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

    /**
     * App opened: new screenshots are read right away, newest first, a few at a time. The
     * background job is queued up front too, so whatever is left carries on after the app closes.
     */
    fun onForeground() {
        foregroundJob?.cancel()
        // After install (or if it was interrupted): fetch the description model, on Wi-Fi unless chosen otherwise.
        scope.launch { checkModel(start = true) }
        foregroundJob = scope.launch {
            runCatching { engine.sync() }
            scheduleBackgroundWork()
            while (isActive && processStep(userAsked = false, foreground = true) > 0) Unit
            scheduleBackgroundWork()
        }
    }

    /** Queues the background job when anything is left to read or describe (and it's allowed). */
    private suspend fun scheduleBackgroundWork() {
        if (!settings.processing.value.background) return
        val counts = withContext(Dispatchers.IO) { repo.counts() }
        val describing = counts.updating > 0 && hub.download.ready
        if (counts.pending > 0 || describing) scheduler.scheduleBacklog()
    }

    /** The description model finished downloading: describe the library, in the app or in the background. */
    suspend fun onModelReady() {
        DotLog.i("model: ready")
        scheduleBackgroundWork()
    }

    /** Downloads the description model (again), over mobile data too if Settings allows it. */
    fun startModelDownload() {
        scope.launch(Dispatchers.IO) { hub.download.start(mobileData = settings.modelOverMobile.value) }
    }

    /**
     * Where the description model is: finished files are checked and moved into place (in case the
     * app wasn't running when they finished), and with [start] a download that never began (or
     * failed) starts.
     */
    suspend fun checkModel(start: Boolean = false): ModelDownload.State = withContext(Dispatchers.IO) {
        val download = hub.download
        var state = runCatching { download.state() }.getOrElse { return@withContext ModelDownload.State.Failed(it.message ?: "error") }
        if (state is ModelDownload.State.Checking && download.install()) {
            onModelReady()
            state = ModelDownload.State.Ready
        }
        // A failed download (no connection to Hugging Face, full storage) is tried again on the next app open.
        if (start && (state is ModelDownload.State.NotStarted || state is ModelDownload.State.Failed)) {
            download.start(mobileData = settings.modelOverMobile.value)
            state = download.state()
        }
        state
    }

    /**
     * Reads up to [STEP] screenshots (text, headings, app, categories) and describes up to [STEP]
     * read ones (Florence-2: description and objects). New screenshots always; older ones only
     * while charging, or as chosen in Settings → Processing; [userAsked] is "Do it now".
     */
    suspend fun processStep(
        userAsked: Boolean,
        foreground: Boolean,
        deadline: Long = Long.MAX_VALUE,
        isStopped: () -> Boolean = { false },
    ): Int {
        val since = power.since(userAsked)
        val read = engine.process(limit = STEP, deadline = deadline, since = since, isStopped = isStopped)
        val described = engine.describe(limit = STEP, since = since, deadline = deadline, isStopped = isStopped)
        return read + described
    }

    /**
     * Left the app: work started from the screen stops (even "Do it now"), unless the phone is
     * charging, and the background job takes over what's left.
     */
    fun onBackground() {
        if (!_backlogRunning.value || !power.isCharging) foregroundJob?.cancel()
        scope.launch { scheduleBackgroundWork() }
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
