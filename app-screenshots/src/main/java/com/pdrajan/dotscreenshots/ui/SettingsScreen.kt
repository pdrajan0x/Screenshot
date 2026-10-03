package com.pdrajan.dotscreenshots.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pdrajan.dot.design.DotChip
import com.pdrajan.dot.design.DotLargeTitle
import com.pdrajan.dot.design.SectionLabel
import com.pdrajan.dot.design.SettingsRow
import com.pdrajan.dot.design.SettingsSwitchRow
import com.pdrajan.dot.design.ThemeMode
import com.pdrajan.dotscreenshots.BuildConfig
import com.pdrajan.dotscreenshots.data.IndexCounts
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val c = appContainer()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val theme by c.settings.theme.collectAsStateWithLifecycle()
    val hindi by c.settings.readHindi.collectAsStateWithLifecycle()
    val charging by c.settings.backlogWhileCharging.collectAsStateWithLifecycle()
    val countsFlow = remember { c.repo.observeCounts() }
    val counts by countsFlow.collectAsStateWithLifecycle(IndexCounts(0, 0, 0, 0))
    var confirmReindex by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }

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

            SectionLabel("Appearance", Modifier.padding(horizontal = 20.dp))
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DotChip("System", onClick = { c.settings.setTheme(ThemeMode.SYSTEM) }, selected = theme == ThemeMode.SYSTEM)
                DotChip("Light", onClick = { c.settings.setTheme(ThemeMode.LIGHT) }, selected = theme == ThemeMode.LIGHT)
                DotChip("Dark", onClick = { c.settings.setTheme(ThemeMode.DARK) }, selected = theme == ThemeMode.DARK)
            }
            Spacer(Modifier.height(12.dp))
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

            SectionLabel("Battery", Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            SettingsSwitchRow(
                title = "Process older screenshots only while charging",
                subtitle = "New screenshots are always read right away.",
                checked = charging,
                onCheckedChange = {
                    c.settings.setBacklogWhileCharging(it)
                    c.scheduler.rescheduleBacklog()
                },
            )
            SettingsRow(
                title = "Allow background processing",
                subtitle = "Some phones (Xiaomi, Realme, Vivo…) stop background apps. Set Dot Screenshots to “No restrictions”.",
                icon = Icons.Rounded.BatterySaver,
                onClick = {
                    ctx.startSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
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
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SectionLabel("About", Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            SettingsRow(
                title = "Dot Screenshots ${BuildConfig.VERSION_NAME}",
                subtitle = "All processing happens on this phone. No account, no internet needed.",
                icon = Icons.Rounded.Info,
                onClick = { showLicenses = true },
            )
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmReindex) {
        AlertDialog(
            onDismissRequest = { confirmReindex = false },
            title = { Text("Re-scan everything?") },
            text = { Text("Every screenshot will be read again — while charging, unless you choose “Do it now” on the home screen.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReindex = false
                    scope.launch {
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
                    Text(
                        "MobileCLIP2-S0 — Apple Machine Learning Research Model is licensed under the Apple Machine Learning " +
                            "Research Model License Agreement (research / non-commercial use).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("ONNX Runtime — MIT License, Microsoft.", style = MaterialTheme.typography.bodySmall)
                    Text("OpenCLIP tokenizer vocabulary — MIT License, OpenAI.", style = MaterialTheme.typography.bodySmall)
                    Text("Text recognition — Google ML Kit (Google Play services).", style = MaterialTheme.typography.bodySmall)
                    Text("Fonts: Doto, Space Grotesk, Space Mono — SIL Open Font License 1.1.", style = MaterialTheme.typography.bodySmall)
                    Text("Coil, Telephoto, AndroidX — Apache License 2.0.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showLicenses = false }) { Text("Close") } },
        )
    }
}
