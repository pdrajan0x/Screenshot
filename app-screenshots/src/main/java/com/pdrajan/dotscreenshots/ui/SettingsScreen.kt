package com.pdrajan.dotscreenshots.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.StrokeCap
import com.pdrajan.dot.design.SettingsDivider
import com.pdrajan.dot.design.SettingsGroup
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
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.LaunchedEffect
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
import com.pdrajan.dot.design.SettingsRow
import com.pdrajan.dot.design.SettingsSwitchRow
import com.pdrajan.dot.design.ThemeMode
import com.pdrajan.dot.media.OldModelFiles
import com.pdrajan.dotscreenshots.BuildConfig
import com.pdrajan.dotscreenshots.data.IndexCounts
import com.pdrajan.dotscreenshots.AppContainer
import com.pdrajan.dotscreenshots.TextModelState
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
    var confirmReindex by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf(false) }
    val showDescriptions by c.settings.showDescriptions.collectAsStateWithLifecycle()
    val modelOverMobile by c.settings.modelOverMobile.collectAsStateWithLifecycle()
    // Follows the description model's download while Settings is open. The rows below read
    // c.modelState themselves, so only they update, not the whole screen.
    LaunchedEffect(Unit) {
        while (true) {
            val state = c.checkModel()
            delay(if (state is ModelDownload.State.Ready) 15_000 else 1_000)
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
            Spacer(Modifier.height(12.dp))
            // Processing lives here, not on the home screen: what's left, why it waits, "Do it now".
            StatusStrip(c, onDetails = { diagnostics = true })

            SettingsGroup("Descriptions") {
                ModelRow(c)
                SettingsDivider()
                SettingsSwitchRow(
                    title = "Download over mobile data",
                    subtitle = "Otherwise the model (${c.hub.download.totalBytes / 1_000_000} MB, once) waits for Wi-Fi.",
                    checked = modelOverMobile,
                    icon = Icons.Rounded.SignalCellularAlt,
                    onCheckedChange = {
                        c.settings.setModelOverMobile(it)
                        if (c.modelState.value !is ModelDownload.State.Ready) c.startModelDownload()
                    },
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = "Show description and keywords",
                    subtitle = "When a screenshot is open. Off: they're never shown anywhere, but search still uses them.",
                    checked = showDescriptions,
                    icon = Icons.Rounded.Visibility,
                    onCheckedChange = c.settings::setShowDescriptions,
                )
            }

            SettingsGroup("Search") {
                SettingsSwitchRow(
                    title = "Read Hindi text",
                    subtitle = "Also reads Devanagari script. Applies to new screenshots; re-scan to update old ones.",
                    checked = hindi,
                    icon = Icons.Rounded.Translate,
                    onCheckedChange = { c.settings.setReadHindi(it) },
                )
                SettingsDivider()
                SettingsRow(
                    title = "Re-scan all screenshots",
                    subtitle = "Reads every screenshot again. Notes and collections are kept.",
                    icon = Icons.Rounded.Refresh,
                    onClick = { confirmReindex = true },
                )
            }

            SettingsGroup("Processing") {
                ProcessingSettings(
                    policy = processing,
                    onChange = {
                        c.settings.setProcessing(it)
                        if (it.background) c.scheduler.rescheduleBacklog() else c.scheduler.cancelBacklog()
                    },
                    appName = "Dot Screenshots",
                )
                SettingsDivider()
                // Exempt from battery optimisation, Android lets the background run keep going (see IndexWorker).
                var unrestricted by remember { mutableStateOf(BackgroundAccess.granted(ctx)) }
                LifecycleResumeEffect(Unit) {
                    unrestricted = BackgroundAccess.granted(ctx)
                    onPauseOrDispose {}
                }
                SettingsRow(
                    title = if (unrestricted) "Runs in the background" else "Let it run in the background",
                    subtitle = if (unrestricted) {
                        "Android won't pause Dot Screenshots while it works. Some phones (Xiaomi, Realme, Vivo…) also need auto-start turned on."
                    } else {
                        "Tap to allow. Otherwise Android pauses the work soon after you leave."
                    },
                    icon = Icons.Rounded.BatterySaver,
                    // Not allowed yet: Android's one-tap prompt (App info where the phone has none). Allowed: App info.
                    onClick = {
                        if (unrestricted || runCatching { ctx.startActivity(BackgroundAccess.prompt(ctx)) }.isFailure) {
                            ctx.startSafely(BackgroundAccess.appInfo(ctx))
                        }
                    },
                )
            }

            SettingsGroup("Appearance") {
                Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(16.dp))
                    Text("Theme", style = MaterialTheme.typography.titleMedium)
                }
                Row(Modifier.padding(start = 60.dp, end = 20.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DotChip("System", onClick = { c.settings.setTheme(ThemeMode.SYSTEM) }, selected = theme == ThemeMode.SYSTEM)
                    DotChip("Light", onClick = { c.settings.setTheme(ThemeMode.LIGHT) }, selected = theme == ThemeMode.LIGHT)
                    DotChip("Dark", onClick = { c.settings.setTheme(ThemeMode.DARK) }, selected = theme == ThemeMode.DARK)
                }
            }

            SettingsGroup("Library") {
                LibraryRow(c)
                var oldModels by remember { mutableStateOf(OldModelFiles.found(ctx)) }
                if (oldModels.isNotEmpty()) {
                    val mb = oldModels.sumOf { runCatching { it.length() }.getOrDefault(0L) } / 1_000_000
                    SettingsDivider()
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
                SettingsDivider()
                SettingsRow(
                    title = "Diagnostics",
                    subtitle = "See what indexing did, copy the log or save it to Downloads.",
                    icon = Icons.Rounded.BugReport,
                    onClick = { diagnostics = true },
                )
            }

            SettingsGroup("About") {
                SettingsRow(
                    title = "Dot Screenshots ${BuildConfig.VERSION_NAME}",
                    subtitle = "Open-source notices",
                    icon = Icons.Rounded.Info,
                    onClick = { showLicenses = true },
                )
                SettingsDivider()
                SettingsRow(
                    title = "Private by design",
                    subtitle = "Everything is read and described on this phone. No account; the internet is only used once, to download the description model.",
                    icon = Icons.Rounded.Lock,
                    onClick = {},
                )
            }
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

/** What the status strip says; [kind] changes only when it says something else (not each count). */
private sealed class Status(val kind: String) {
    data class Text(val progress: Float?) : Status("text")
    data class Working(val reading: Boolean, val left: Int, val fraction: Float?) : Status(if (reading) "reading" else "describing")
    data class Error(val message: String) : Status("error")
    data class Waiting(val left: Int, val reason: String?) : Status("waiting")
    data class NoText(val count: Int) : Status("no-text")
    data object Idle : Status("idle")
}

/** One line about the library: how many screenshots are left, why it's waiting, or what went wrong. */
@Composable
private fun StatusStrip(c: AppContainer, onDetails: () -> Unit) {
    val counts by remember { c.repo.observeCounts() }.collectAsStateWithLifecycle(IndexCounts(0, 0, 0, 0))
    val runs by c.activeRuns.collectAsStateWithLifecycle()
    val backlogRunning by c.backlogRunning.collectAsStateWithLifecycle()
    val model by c.modelState.collectAsStateWithLifecycle()
    val text by c.textModel.collectAsStateWithLifecycle()
    val lastError by c.engine.lastError.collectAsStateWithLifecycle()
    // Read ones are described once the model is here; until then the model row says why.
    val toDescribe = if (model is ModelDownload.State.Ready) counts.updating else 0
    val total = counts.total.coerceAtLeast(1)
    val fetching = text as? TextModelState.Fetching
    val status = when {
        counts.pending > 0 && fetching != null -> Status.Text(fetching.progress)
        // Busy for the whole run (not per batch), so the line doesn't flicker between batches.
        runs > 0 && counts.pending > 0 -> Status.Working(true, counts.pending, (total - counts.pending).toFloat() / total)
        runs > 0 && toDescribe > 0 -> Status.Working(false, toDescribe, (total - toDescribe).toFloat() / total)
        lastError != null -> Status.Error(lastError!!)
        counts.pending + toDescribe > 0 -> Status.Waiting(
            counts.pending + toDescribe,
            c.power.blocker() ?: if (!c.power.backlogAllowed()) "older ones are done while charging" else null,
        )
        // Not an error the user can fix here, but they should know why words aren't found.
        counts.withoutText > 0 && text is TextModelState.Unavailable -> Status.NoText(counts.withoutText)
        else -> Status.Idle
    }
    val modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    AnimatedContent(
        targetState = status,
        contentKey = { it.kind },
        transitionSpec = { fadeIn(tween(220, 60)) togetherWith fadeOut(tween(120)) using SizeTransform(clip = false) },
        label = "status",
    ) { s ->
        when (s) {
            is Status.Text -> DotProgressStrip(
                text = "Getting the text reader from Google Play services" + (s.progress?.let { " · ${(it * 100).toInt()}%" } ?: "…"),
                modifier = modifier,
                progress = s.progress,
            )
            is Status.Working -> DotProgressStrip(
                text = (if (s.reading) "Reading screenshots" else "Describing screenshots") + " · ${s.left} left",
                modifier = modifier,
                progress = s.fraction,
                action = { if (backlogRunning) TextButton(onClick = c::stopProcessing) { Text("Stop", color = DotTheme.extra.accent) } },
            )
            is Status.Error -> DotProgressStrip(
                text = s.message,
                modifier = modifier,
                working = false,
                action = { TextButton(onClick = onDetails) { Text("Details", color = DotTheme.extra.accent) } },
            )
            is Status.Waiting -> DotProgressStrip(
                text = "${s.left} screenshots waiting" + (s.reason?.let { " · $it" } ?: ""),
                modifier = modifier,
                action = { TextButton(onClick = c::processAllNow) { Text("Do it now", color = DotTheme.extra.accent) } },
            )
            is Status.NoText -> DotProgressStrip(
                text = "Google Play services couldn't get the text reader: ${s.count} screenshots are searchable by their " +
                    "description only. They're read again when it's available.",
                modifier = modifier,
                working = false,
            )
            Status.Idle -> Spacer(Modifier.height(0.dp))
        }
    }
}

/** How much of the library is searchable, and how the index is doing. */
@Composable
private fun LibraryRow(c: AppContainer) {
    val counts by remember { c.repo.observeCounts() }.collectAsStateWithLifecycle(IndexCounts(0, 0, 0, 0))
    val avg = c.settings.avgIndexMillis
    val indexMb = remember(counts.total, counts.indexed) { c.repo.databaseSizeBytes() / 1_000_000 }
    SettingsRow(
        title = "${counts.indexed} of ${counts.total} screenshots searchable",
        subtitle = buildString {
            if (counts.pending > 0) append("${counts.pending} waiting · ")
            if (counts.failed > 0) append("${counts.failed} couldn't be read · ")
            if (avg > 0) append("~${"%.1f".format(avg / 1000.0)} s per screenshot · ")
            append("index $indexMb MB")
        },
        icon = Icons.Rounded.Storage,
        onClick = {},
    )
}

/** The description model, as a row of its group: ready, downloading (with a bar), waiting, or a button to (re)start it. */
@Composable
private fun ModelRow(c: AppContainer) {
    val state by c.modelState.collectAsStateWithLifecycle()
    fun mb(bytes: Long) = bytes / 1_000_000
    val title: String
    val subtitle: String
    var progress: Float? = null
    var action: String? = null
    when (val s = state) {
        ModelDownload.State.Ready -> {
            title = "Description model ready"
            subtitle = "Florence-2-base, on this phone. Describes what's in each screenshot for search."
        }
        ModelDownload.State.Checking -> {
            title = "Checking the description model"
            subtitle = "Making sure the download is complete and intact…"
        }
        is ModelDownload.State.Downloading -> {
            title = "Downloading the description model"
            subtitle = if (s.bytes == 0L) "Connecting…" else "${mb(s.bytes)} of ${mb(s.total)} MB"
            progress = if (s.total > 0) s.bytes.toFloat() / s.total else null
        }
        is ModelDownload.State.Waiting -> {
            title = "Description model: ${s.reason}"
            subtitle = "${mb(s.bytes)} of ${mb(s.total)} MB · it carries on by itself"
            progress = if (s.total > 0) s.bytes.toFloat() / s.total else null
        }
        is ModelDownload.State.Failed -> {
            title = "Description model: ${s.reason}"
            subtitle = "Screenshots are still searchable by their text."
            action = "Retry"
        }
        ModelDownload.State.NotStarted -> {
            title = "Download the description model"
            subtitle = "Describes what's in each screenshot for search. Until then, search uses the screen's text."
            action = "Download"
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = action != null) { c.startModelDownload() }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (state is ModelDownload.State.Ready) Icons.Rounded.CheckCircle else Icons.Rounded.AutoAwesome,
            contentDescription = null,
            tint = if (state is ModelDownload.State.Ready) DotTheme.extra.accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).animateContentSize()) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val p = progress
            if (p != null) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { p.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = DotTheme.extra.accent,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
        }
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = c::startModelDownload) { Text(action, color = DotTheme.extra.accent) }
        }
    }
}
