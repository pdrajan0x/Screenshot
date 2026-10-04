package com.pdrajan.dotscreenshots

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import okio.Path.Companion.toOkioPath
import coil3.request.crossfade
import com.pdrajan.dot.design.CrashLog
import com.pdrajan.dot.design.MediaThumbs
import com.pdrajan.dot.engine.AppNames
import com.pdrajan.dot.ml.InstalledApps
import com.pdrajan.dot.ml.TextReader
import com.pdrajan.dot.media.DotLog
import com.pdrajan.dot.media.MediaStoreSource
import com.pdrajan.dot.media.PowerGate
import com.pdrajan.dotscreenshots.data.Settings
import com.pdrajan.dotscreenshots.data.ShotsDatabase
import com.pdrajan.dotscreenshots.data.ShotsRepository
import com.pdrajan.dotscreenshots.index.IndexEngine
import com.pdrajan.dotscreenshots.index.IndexScheduler
import com.pdrajan.dotscreenshots.index.TextGate
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            // Sharp grid thumbnails are made once and kept here (see MediaThumbs).
            .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("thumbnails").toOkioPath()).maxSizeBytes(300L * 1024 * 1024).build() }
            .components { MediaThumbs.addTo(this, context) }
            .crossfade(true)
            .build()
}

/** Play services' text model, which every screenshot's words are read with. */
sealed interface TextModelState {
    data object Ready : TextModelState
    data class Fetching(val progress: Float?) : TextModelState
    /** Play services couldn't get it: screenshots are listed without their words for now. */
    data object Unavailable : TextModelState
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
    val engine = IndexEngine(context, repo, settings, hub, media, installed = { installedChoices() }, textGate = { textGate() })
    val scheduler = IndexScheduler(context) { !settings.processing.value.onBattery }

    /** Ids of the last search's results, so the viewer can swipe through them. */
    @Volatile var lastSearchIds: List<Long> = emptyList()

    /** The grid's order when a screenshot was opened from it: the viewer swipes through it from its first frame. */
    @Volatile var viewerOrder: List<Long> = emptyList()

    /** The screenshot the viewer shows (or last showed): the grid brings it into view on the way back. */
    @Volatile var viewerAt: Long? = null

    private val _backlogRunning = MutableStateFlow(false)
    /** True while the user asked to process everything now (not just the newest batch). */
    val backlogRunning: StateFlow<Boolean> = _backlogRunning.asStateFlow()

    private val _activeRuns = MutableStateFlow(0)
    /**
     * How many runs are reading or describing screenshots right now (the app's own and the
     * background job's). Steady for the whole run, unlike each batch of a few screenshots.
     */
    val activeRuns: StateFlow<Int> = _activeRuns.asStateFlow()

    private val _modelState = MutableStateFlow<ModelDownload.State>(ModelDownload.State.Checking)
    /** The description model's download, as last checked ([checkModel]). */
    val modelState: StateFlow<ModelDownload.State> = _modelState.asStateFlow()

    private val _textModel = MutableStateFlow<TextModelState?>(null)
    /** Play services' text model; null until something needed it. */
    val textModel: StateFlow<TextModelState?> = _textModel.asStateFlow()
    private var textModelJob: Job? = null
    private var textModelHindi: Boolean? = null

    @Volatile private var visible = false
    private var foregroundJob: Job? = null

    /** Counts [block] as a run in [activeRuns]. */
    suspend fun <T> running(block: suspend () -> T): T {
        _activeRuns.update { it + 1 }
        try {
            return block()
        } finally {
            _activeRuns.update { it - 1 }
        }
    }

    /**
     * App opened: new screenshots are read right away, newest first, a few at a time. The
     * background job is queued up front too, so whatever is left carries on after the app closes.
     */
    @Synchronized
    fun onForeground() {
        visible = true
        foregroundJob?.cancel()
        // After install (or if it was interrupted): fetch the description model, on Wi-Fi unless chosen otherwise.
        scope.launch { checkModel(start = true) }
        foregroundJob = startRun(userAsked = false, sync = true)
    }

    private fun startRun(userAsked: Boolean, sync: Boolean): Job = scope.launch {
        running {
            if (sync) runCatching { engine.sync() }
            scheduleBackgroundWork()
            while (isActive && processStep(userAsked = userAsked, foreground = true) > 0) Unit
        }
        scheduleBackgroundWork()
    }

    /**
     * Something that held work back is ready (the text or description model arrived): carry on
     * now, in the app if it's open, else in the background job.
     */
    @Synchronized
    fun kick() {
        if (visible) {
            if (foregroundJob?.isActive != true) foregroundJob = startRun(userAsked = false, sync = false)
        } else {
            scope.launch { scheduleBackgroundWork() }
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
        _modelState.value = ModelDownload.State.Ready
        kick()
    }

    /** Downloads the description model (again), over mobile data too if Settings allows it. */
    fun startModelDownload() {
        scope.launch(Dispatchers.IO) {
            hub.download.start(mobileData = settings.modelOverMobile.value)
            checkModel()
        }
    }

    /** Where the description model is; with [start], a download that never began (or failed) starts. */
    suspend fun checkModel(start: Boolean = false): ModelDownload.State = withContext(Dispatchers.IO) {
        val download = hub.download
        var state = runCatching { download.state() }.getOrElse { ModelDownload.State.Failed(it.message ?: "error") }
        // A failed download (no connection to Hugging Face, full storage) is tried again on the next app open.
        if (start && (state is ModelDownload.State.NotStarted || state is ModelDownload.State.Failed)) {
            download.start(mobileData = settings.modelOverMobile.value)
            state = download.state()
        }
        _modelState.value = state
        state
    }

    /**
     * Whether screenshots can be read with their words now. When Play services doesn't have the
     * text model yet, it's asked for it right away and reading waits (instead of indexing the
     * library without words); without Play services, they're read without words.
     */
    private suspend fun textGate(): TextGate {
        val hindi = settings.readHindi.value
        if (textModelHindi != hindi) {
            textModelJob?.cancel()
            _textModel.value = null
            textModelHindi = hindi
        }
        when (_textModel.value) {
            TextModelState.Ready -> return TextGate.READY
            TextModelState.Unavailable -> return TextGate.WITHOUT
            is TextModelState.Fetching -> return TextGate.WAIT
            null -> Unit
        }
        return when (withContext(Dispatchers.IO) { TextReader.modelInstalled(context, hindi) }) {
            true -> {
                _textModel.value = TextModelState.Ready
                TextGate.READY
            }
            false -> {
                fetchTextModel(hindi)
                TextGate.WAIT
            }
            null -> {
                DotLog.w("text: Play services can't tell whether the text model is here; reading without it")
                _textModel.value = TextModelState.Unavailable
                TextGate.WITHOUT
            }
        }
    }

    private fun fetchTextModel(hindi: Boolean) {
        if (textModelJob?.isActive == true) return
        _textModel.value = TextModelState.Fetching(null)
        DotLog.i("text: asking Play services for the text model")
        textModelJob = scope.launch {
            val ok = withTimeoutOrNull(10 * 60_000L) {
                TextReader.installModel(context, hindi) { p -> _textModel.value = TextModelState.Fetching(p) }
            } ?: false
            DotLog.i(if (ok) "text: model ready" else "text: Play services couldn't get the text model; reading without words for now")
            _textModel.value = if (ok) TextModelState.Ready else TextModelState.Unavailable
            kick()
        }
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
    @Synchronized
    fun onBackground() {
        visible = false
        if (!_backlogRunning.value || !power.isCharging) foregroundJob?.cancel()
        scope.launch { scheduleBackgroundWork() }
    }

    /** "Do it now": everything, whatever the battery rules say, while the app stays open. */
    @Synchronized
    fun processAllNow() {
        if (_backlogRunning.value) return
        foregroundJob?.cancel()
        _backlogRunning.value = true
        foregroundJob = scope.launch {
            try {
                running {
                    runCatching { engine.sync() }
                    while (isActive && processStep(userAsked = true, foreground = true) > 0) Unit
                }
            } finally {
                _backlogRunning.value = false
            }
        }
    }

    @Synchronized
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
