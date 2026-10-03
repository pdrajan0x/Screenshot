package com.pdrajan.dotgallery.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PermMedia
import androidx.compose.material.icons.rounded.PhotoLibrary
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pdrajan.dot.design.DiagnosticsDialog
import com.pdrajan.dot.design.DotChip
import com.pdrajan.dot.design.DotLargeTitle
import com.pdrajan.dot.design.ProcessingSettings
import com.pdrajan.dot.design.SectionLabel
import com.pdrajan.dot.design.SettingsRow
import com.pdrajan.dot.design.SettingsSwitchRow
import com.pdrajan.dot.design.ThemeMode
import com.pdrajan.dot.media.MediaAccess
import com.pdrajan.dot.media.MediaPermissions
import com.pdrajan.dotgallery.BuildConfig
import com.pdrajan.dotgallery.data.IndexCounts
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: GalleryNav) {
    val c = galleryContainer()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val theme by c.settings.theme.collectAsStateWithLifecycle()
    val people by c.settings.people.collectAsStateWithLifecycle()
    val readText by c.settings.readText.collectAsStateWithLifecycle()
    val hindi by c.settings.readHindi.collectAsStateWithLifecycle()
    val processing by c.settings.processing.collectAsStateWithLifecycle()
    val counts by remember { c.repo.observeCounts() }.collectAsStateWithLifecycle(IndexCounts(0, 0, 0))
    var access by remember { mutableStateOf(MediaPermissions.access(ctx)) }
    var canManage by remember { mutableStateOf(canManageMedia(ctx)) }
    var confirmRescan by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        access = MediaPermissions.access(ctx)
        canManage = canManageMedia(ctx)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = MediaPermissions.access(ctx)
        c.refresh()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
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

            SectionLabel("Appearance", Modifier.padding(horizontal = 20.dp))
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DotChip("System", onClick = { c.settings.setTheme(ThemeMode.SYSTEM) }, selected = theme == ThemeMode.SYSTEM)
                DotChip("Light", onClick = { c.settings.setTheme(ThemeMode.LIGHT) }, selected = theme == ThemeMode.LIGHT)
                DotChip("Dark", onClick = { c.settings.setTheme(ThemeMode.DARK) }, selected = theme == ThemeMode.DARK)
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SectionLabel("Smart features", Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            SettingsSwitchRow(
                title = "Group similar faces",
                subtitle = "Finds people in your photos so you can search by name. Face data never leaves this phone.",
                checked = people,
                onCheckedChange = { c.settings.setPeople(it) },
            )
            SettingsSwitchRow(
                title = "Read text in photos",
                subtitle = "Search signs, documents and notes by their words.",
                checked = readText,
                onCheckedChange = { c.settings.setReadText(it) },
            )
            if (readText) {
                SettingsSwitchRow(
                    title = "Read Hindi text",
                    subtitle = "Also reads Devanagari script.",
                    checked = hindi,
                    onCheckedChange = { c.settings.setReadHindi(it) },
                )
            }
            AiModelPanel(c)
            SettingsRow(
                title = "Re-scan library",
                subtitle = "Analyses every photo again with the current settings. Albums, favourites and names are kept.",
                icon = Icons.Rounded.Refresh,
                onClick = { confirmRescan = true },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SectionLabel("Processing", Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            ProcessingSettings(
                policy = processing,
                onChange = {
                    c.settings.setProcessing(it)
                    if (it.background) c.scheduler.rescheduleBacklog() else c.scheduler.cancelBacklog()
                },
                appName = "Dot Gallery",
            )
            SettingsRow(
                title = "Allow background processing",
                subtitle = "Some phones (Xiaomi, Realme, Vivo…) stop background apps. Set Dot Gallery to “No restrictions”.",
                icon = Icons.Rounded.BatterySaver,
                onClick = { ctx.startSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SectionLabel("Access", Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            SettingsRow(
                title = when (access) {
                    MediaAccess.FULL -> "All photos and videos"
                    MediaAccess.PARTIAL -> "Selected photos only"
                    MediaAccess.NONE -> "No access to photos"
                },
                subtitle = if (access == MediaAccess.FULL) "Dot Gallery can see your whole library." else "Tap to allow access to all photos and videos.",
                icon = Icons.Rounded.PhotoLibrary,
                onClick = {
                    if (access == MediaAccess.FULL) {
                        ctx.startSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                    } else {
                        permissionLauncher.launch(galleryPermissions())
                    }
                },
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SettingsRow(
                    title = "Manage media",
                    subtitle = if (canManage) "On — moving to bin and editing skip the extra confirmation." else "Off — Android asks before every delete. Tap to turn on.",
                    icon = Icons.Rounded.PermMedia,
                    onClick = { ctx.startSafely(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.fromParts("package", ctx.packageName, null))) },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SectionLabel("Status", Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            SettingsRow(
                title = "${counts.indexed} of ${counts.total} analysed",
                subtitle = if (counts.pending > 0) "${counts.pending} waiting" else "Everything is searchable.",
                icon = Icons.Rounded.Storage,
                onClick = {},
            )
            SettingsRow(
                title = "Diagnostics",
                subtitle = "See what indexing did, copy the log or save it to Downloads.",
                icon = Icons.Rounded.BugReport,
                onClick = { diagnostics = true },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SectionLabel("About", Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            SettingsRow(
                title = "Dot Gallery ${BuildConfig.VERSION_NAME}",
                subtitle = "All processing happens on this phone. No account, no backup, no internet needed. Tap for notices.",
                icon = Icons.Rounded.Info,
                onClick = { showLicenses = true },
            )
            Spacer(Modifier.height(32.dp))
        }
    }

    if (diagnostics) DiagnosticsDialog("DotGallery") { diagnostics = false }

    if (confirmRescan) {
        AlertDialog(
            onDismissRequest = { confirmRescan = false },
            title = { Text("Re-scan everything?") },
            text = { Text("Every photo will be analysed and described again, following Settings → Processing (or right away with “Do it now” in Photos).") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRescan = false
                    scope.launch {
                        c.repo.requeueAll()
                        c.scheduler.scheduleBacklog()
                    }
                }) { Text("Re-scan") }
            },
            dismissButton = { TextButton(onClick = { confirmRescan = false }) { Text("Cancel") } },
        )
    }

    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text("Open-source notices") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "MobileCLIP2-S0 — Apple Machine Learning Research Model, licensed under the Apple Machine Learning " +
                            "Research Model License Agreement (research / non-commercial use).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Face recognition — InsightFace buffalo_s (MobileFaceNet), released for non-commercial research use only.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("ONNX Runtime — MIT License, Microsoft.", style = MaterialTheme.typography.bodySmall)
                    Text("OpenCLIP tokenizer vocabulary — MIT License, OpenAI.", style = MaterialTheme.typography.bodySmall)
                    Text("Face detection and text recognition — Google ML Kit (Google Play services).", style = MaterialTheme.typography.bodySmall)
                    Text("Fonts: Doto, Space Grotesk, Space Mono — SIL Open Font License 1.1.", style = MaterialTheme.typography.bodySmall)
                    Text("Coil, Telephoto, Media3, AndroidX — Apache License 2.0.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showLicenses = false }) { Text("Close") } },
        )
    }
}

private fun canManageMedia(ctx: android.content.Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && MediaStore.canManageMedia(ctx)

/** Images + video, plus EXIF location on Android 10+ so the info panel can show where a photo was taken. */
fun galleryPermissions(): Array<String> {
    val base = MediaPermissions.required(includeVideo = true)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) base + android.Manifest.permission.ACCESS_MEDIA_LOCATION else base
}
