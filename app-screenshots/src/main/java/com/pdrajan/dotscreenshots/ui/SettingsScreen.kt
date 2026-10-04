package com.pdrajan.dotscreenshots.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pdrajan.dot.design.DiagnosticsDialog
import com.pdrajan.dot.design.DotChip
import com.pdrajan.dot.design.DotLargeTitle
import com.pdrajan.dot.design.DotProgressStrip
import com.pdrajan.dot.design.DotTheme
import com.pdrajan.dot.design.ProcessingSettings
import com.pdrajan.dot.design.SectionLabel
import com.pdrajan.dot.design.SettingsRow
import com.pdrajan.dot.design.SettingsSwitchRow
import com.pdrajan.dot.design.ThemeMode
import com.pdrajan.dot.media.OldModelFiles
import com.pdrajan.dotscreenshots.BuildConfig
import com.pdrajan.dotscreenshots.data.IndexCounts
import com.pdrajan.dotscreenshots.index.IndexProgress
import com.pdrajan.dotscreenshots.index.ModelDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val c = appContainer()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val theme by c.settings.theme.collectAsStateWithLifecycle()
    val hindi by c.settings.readHindi.collectAsStateWithLifecycle()
    val processing by c.settings.processing.collectAsStateWithLifecycle()
    val countsFlow = remember { c.repo.observeCounts() }
    val counts by countsFlow.collectAsStateWithLifecycle(IndexCounts(0, 0, 0, 0))
    var confirmReindex by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf(false) }
    val progress by c.engine.progress.collectAsStateWithLifecycle()
    val lastError by c.engine.lastError.collectAsStateWithLifecycle()
    val backlogRunning by c.backlogRunning.collectAsStateWithLifecycle()
    val showDescriptions by c.settings.showDescriptions.collectAsStateWithLifecycle()
    val modelOverMobile by c.settings.modelOverMobile.collectAsStateWithLifecycle()
    // The description model's download, followed while Settings is open.
    val model by produceState<ModelDownload.State>(ModelDownload.State.Checking) {
        while (true) {
            value = c.checkModel()
            delay(if (value is ModelDownload.State.Ready) 10_000 else 1_000)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            DotLargeTitle("SETTINGS", Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(16.dp))
            // Processing lives here, not on the home screen: what's left, why it waits, "Do it now".
            StatusStrip(
                progress = progress,
                counts = counts,
                backlogRunning = backlogRunning,
                modelReady = model is ModelDownload.State.Ready,
                lastError = lastError,
                waitingFor = c.power.blocker() ?: if (!c.power.backlogAllowed()) "older ones are done while charging" else null,
                onProcessAll = c::processAllNow,
                onStop = c::stopProcessing,
                onDetails = { diagnostics = true },
            )

            SectionLabel("Appearance", Modifier.padding(horizontal = 20.dp))
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DotChip("System", onClick = { c.settings.setTheme(ThemeMode.SYSTEM) }, selected = theme == ThemeMode.SYSTEM)
                DotChip("Light", onClick = { c.settings.setTheme(ThemeMode.LIGHT) }, selected = theme == ThemeMode.LIGHT)
                DotChip("Dark", onClick = { c.settings.setTheme(ThemeMode.DARK) }, selected = theme == ThemeMode.DARK)
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SectionLabel("Descriptions", Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            ModelRow(model, onDownload = c::startModelDownload)
            SettingsSwitchRow(
                title = "Download over mobile data",
                subtitle = "Otherwise the model (${c.hub.download.totalBytes / 1_000_000} MB, once) waits for Wi-Fi.",
                checked = modelOverMobile,
                onCheckedChange = {
                    c.settings.setModelOverMobile(it)
                    if (model !is ModelDownload.State.Ready) c.startModelDownload()
                },
            )
            SettingsSwitchRow(
                title = "Show descriptions and keywords",
                subtitle = "In each screenshot's details. Search uses them either way.",
                checked = showDescriptions,
                onCheckedChange = c.settings::setShowDescriptions,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SectionLabel("Search", Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            SettingsSwitchRow(
                title = "Read Hindi text",
                subtitle = "Also reads Devanagari script. Applies to new screenshots; re-scan to update old ones.",
                checked = hindi,
                onCheckedChange = { c.settings.setReadHindi(it) },
            )
            SettingsRow(
                title = "Re-scan all screenshots",
                subtitle = "Reads every screenshot again. Notes and collections are kept.",
                icon = Icons.Rounded.Refresh,
                onClick = { confirmReindex = true },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SectionLabel("Processing", Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            ProcessingSettings(
                policy = processing,
                onChange = {
                    c.settings.setProcessing(it)
                    if (it.background) c.scheduler.rescheduleBacklog() else c.scheduler.cancelBacklog()
                },
                appName = "Dot Screenshots",
            )
            // Exempt from battery optimisation, Android lets the background run keep going (see IndexWorker).
            val power = remember { ctx.getSystemService(PowerManager::class.java) }
            var unrestricted by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(ctx.packageName)) }
            LifecycleResumeEffect(Unit) {
                unrestricted = power.isIgnoringBatteryOptimizations(ctx.packageName)
                onPauseOrDispose {}
            }
            val appDetails = { ctx.startSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))) }
            SettingsRow(
                title = if (unrestricted) "Runs in the background" else "Let it run in the background",
                subtitle = if (unrestricted) {
                    "Android won't pause Dot Screenshots while it works. Some phones (Xiaomi, Realme, Vivo…) also need auto-start turned on."
                } else {
                    "Otherwise Android pauses the work soon after you leave. Some phones (Xiaomi, Realme, Vivo…) also need “No restrictions”."
                },
                icon = Icons.Rounded.BatterySaver,
                onClick = {
                    if (unrestricted) {
                        appDetails()
                    } else {
                        @SuppressLint("BatteryLife")
                        val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", ctx.packageName, null))
                        if (runCatching { ctx.startActivity(ask) }.isFailure) appDetails()
                    }
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SectionLabel("Status", Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            val avg = c.settings.avgIndexMillis
            SettingsRow(
                title = "${counts.indexed} of ${counts.total} screenshots searchable",
                subtitle = buildString {
                    if (counts.pending > 0) append("${counts.pending} waiting · ")
                    if (counts.failed > 0) append("${counts.failed} couldn't be read · ")
                    if (avg > 0) append("~${"%.1f".format(avg / 1000.0)} s per screenshot · ")
                    append("index ${c.repo.databaseSizeBytes() / 1_000_000} MB")
                },
                icon = Icons.Rounded.Storage,
                onClick = {},
            )
            var oldModels by remember { mutableStateOf(OldModelFiles.found(ctx)) }
            if (oldModels.isNotEmpty()) {
                val mb = oldModels.sumOf { runCatching { it.length() }.getOrDefault(0L) } / 1_000_000
                SettingsRow(
                    title = "Delete the old AI model",
                    subtitle = "Earlier versions downloaded it ($mb MB); Dot Screenshots no longer uses it. " +
                        "Dot Gallery 0.1.29 or older may still use this copy.",
                    icon = Icons.Rounded.DeleteSweep,
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { OldModelFiles.delete(ctx) }
                            oldModels = OldModelFiles.found(ctx)
                        }
                    },
                )
            }
            SettingsRow(
                title = "Diagnostics",
                subtitle = "See what indexing did, copy the log or save it to Downloads.",
                icon = Icons.Rounded.BugReport,
                onClick = { diagnostics = true },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SectionLabel("About", Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            SettingsRow(
                title = "Dot Screenshots ${BuildConfig.VERSION_NAME}",
                subtitle = "All processing happens on this phone. No account; the internet is only used to download the description model once.",
                icon = Icons.Rounded.Info,
                onClick = { showLicenses = true },
            )
            Spacer(Modifier.height(32.dp))
        }
    }

    if (diagnostics) DiagnosticsDialog("DotScreenshots") { diagnostics = false }

    if (confirmReindex) {
        AlertDialog(
            onDismissRequest = { confirmReindex = false },
            title = { Text("Re-scan everything?") },
            text = { Text("Every screenshot will be read again, following Settings → Processing (or right away with “Do it now” under Settings → Status).") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReindex = false
                    scope.launch(Dispatchers.IO) {
                        c.repo.requeueAll()
                        c.scheduler.scheduleBacklog()
                    }
                }) { Text("Re-scan") }
            },
            dismissButton = { TextButton(onClick = { confirmReindex = false }) { Text("Cancel") } },
        )
    }

    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text("Open-source notices") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Florence-2-base (descriptions) — MIT License, Microsoft.", style = MaterialTheme.typography.bodySmall)
                    Text("ONNX Runtime — MIT License, Microsoft.", style = MaterialTheme.typography.bodySmall)
                    Text("Text recognition — Google ML Kit.", style = MaterialTheme.typography.bodySmall)
                    Text("Fonts: Doto, Space Grotesk, Space Mono — SIL Open Font License 1.1.", style = MaterialTheme.typography.bodySmall)
                    Text("Coil, Telephoto, Haze, AndroidX — Apache License 2.0.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showLicenses = false }) { Text("Close") } },
        )
    }
}

/** One line about the library: how many screenshots are left, why it's waiting, or what went wrong. */
@Composable
private fun StatusStrip(
    progress: IndexProgress,
    counts: IndexCounts,
    backlogRunning: Boolean,
    modelReady: Boolean,
    lastError: String?,
    waitingFor: String?,
    onProcessAll: () -> Unit,
    onStop: () -> Unit,
    onDetails: () -> Unit,
) {
    val modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    // Screenshots still to read, plus read ones still to describe (once the model is here).
    val left = counts.pending + if (modelReady) counts.updating else 0
    val total = counts.total
    val done = (total - left).coerceAtLeast(0)
    val stop: @Composable () -> Unit = { if (backlogRunning) TextButton(onClick = onStop) { Text("Stop", color = DotTheme.extra.accent) } }
    when {
        lastError != null -> DotProgressStrip(
            text = lastError,
            modifier = modifier,
            action = { TextButton(onClick = onDetails) { Text("Details", color = DotTheme.extra.accent) } },
        )
        progress.running -> DotProgressStrip(
            text = "Reading your screenshots · $left left",
            modifier = modifier,
            progress = if (total > 0) done.toFloat() / total else null,
            action = stop,
        )
        left > 0 -> DotProgressStrip(
            text = "$left screenshots waiting" + (waitingFor?.let { " · $it" } ?: ""),
            modifier = modifier,
            action = { TextButton(onClick = onProcessAll) { Text("Do it now", color = DotTheme.extra.accent) } },
        )
    }
}

/** The description model: downloading, waiting for Wi-Fi, ready, or a button to (re)start it. */
@Composable
private fun ModelRow(state: ModelDownload.State, onDownload: () -> Unit) {
    val modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    fun mb(bytes: Long) = bytes / 1_000_000
    when (state) {
        ModelDownload.State.Ready -> SettingsRow(
            title = "Description model ready",
            subtitle = "Florence-2-base, on this phone. Describes what's in each screenshot for search.",
            icon = Icons.Rounded.CheckCircle,
            onClick = {},
        )
        ModelDownload.State.Checking -> DotProgressStrip("Checking the description model…", modifier)
        is ModelDownload.State.Downloading -> DotProgressStrip(
            text = "Downloading the description model · ${mb(state.bytes)} of ${mb(state.total)} MB",
            modifier = modifier,
            progress = if (state.total > 0) state.bytes.toFloat() / state.total else null,
        )
        is ModelDownload.State.Waiting -> DotProgressStrip(
            text = "Description model · ${state.reason} · ${mb(state.bytes)} of ${mb(state.total)} MB",
            modifier = modifier,
            progress = if (state.total > 0) state.bytes.toFloat() / state.total else null,
        )
        is ModelDownload.State.Failed -> SettingsRow(
            title = "Description model: ${state.reason}",
            subtitle = "Screenshots are still searchable by their text. Tap to try again.",
            icon = Icons.Rounded.Download,
            onClick = onDownload,
            trailing = { TextButton(onClick = onDownload) { Text("Retry", color = DotTheme.extra.accent) } },
        )
        ModelDownload.State.NotStarted -> SettingsRow(
            title = "Download the description model",
            subtitle = "Describes what's in each screenshot for search. Until then, search uses the screen's text.",
            icon = Icons.Rounded.Download,
            onClick = onDownload,
            trailing = { TextButton(onClick = onDownload) { Text("Download", color = DotTheme.extra.accent) } },
        )
    }
}
