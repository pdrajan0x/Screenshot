package com.pdrajan.dot.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.pdrajan.dot.media.ProcessingPolicy
import kotlin.math.roundToInt

/** Settings → Processing: background on/off, charging only, or on battery above a charge level. */
@Composable
fun ProcessingSettings(policy: ProcessingPolicy, onChange: (ProcessingPolicy) -> Unit, appName: String) {
    SettingsSwitchRow(
        title = "Process in the background",
        subtitle = if (policy.background) {
            "New items are read and described even when $appName is closed."
        } else {
            "Only while $appName is open."
        },
        checked = policy.background,
        onCheckedChange = { onChange(policy.copy(background = it)) },
    )
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text("When", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DotChip("Only while charging", onClick = { onChange(policy.copy(onBattery = false)) }, selected = !policy.onBattery)
            DotChip("Also on battery", onClick = { onChange(policy.copy(onBattery = true)) }, selected = policy.onBattery)
        }
        if (policy.onBattery) {
            var level by remember(policy.minBattery) { mutableFloatStateOf(policy.minBattery.toFloat()) }
            Spacer(Modifier.height(12.dp))
            Text(
                "On battery, only above ${level.roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = level,
                onValueChange = { level = it },
                onValueChangeFinished = { onChange(policy.copy(minBattery = level.roundToInt())) },
                valueRange = 20f..90f,
                steps = 6,
                colors = SliderDefaults.colors(thumbColor = DotTheme.extra.accent, activeTrackColor = DotTheme.extra.accent),
            )
        }
        Text(
            "Battery saver, a warm phone or low charge always pause the AI. Charging is always allowed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** What the model download screen shows. */
data class ModelDownloadUi(
    val ready: Boolean,
    val downloading: Boolean,
    val verifying: Boolean,
    val bytes: Long,
    val total: Long,
    val error: String?,
)

private fun mb(bytes: Long) = "%,d MB".format(bytes / 1_000_000)

/**
 * First-run (and whenever the model is missing) screen: the app needs the on-device AI model and
 * doesn't go further without it.
 */
@Composable
fun ModelSetupScreen(
    appName: String,
    modelName: String,
    ui: ModelDownloadUi,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    /** Android 11+: reuse a copy the other Dot app downloaded (needs All files access); null to hide. */
    onUseOtherAppsCopy: (() -> Unit)?,
) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        DotLargeTitle("ONE LAST STEP")
        Text(
            "$appName writes descriptions, keywords and summaries with an AI model that runs entirely on this phone. " +
                "It needs to be downloaded once (${mb(ui.total)}, Wi-Fi recommended). Nothing you have is ever uploaded.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "$modelName is saved in Download/AI Models, where the other Dot app can use the same copy.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        when {
            ui.downloading -> {
                LinearProgressIndicator(
                    progress = { if (ui.total > 0) ui.bytes.toFloat() / ui.total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                    color = DotTheme.extra.accent,
                    strokeCap = StrokeCap.Round,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${mb(ui.bytes)} of ${mb(ui.total)} · keep the app open",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onPause) { Text("Pause") }
                }
            }
            ui.verifying -> Text("Checking the download…", style = MaterialTheme.typography.titleMedium)
            else -> {
                ui.error?.let {
                    Text(
                        if (ui.bytes > 0) "$it · ${mb(ui.bytes)} of ${mb(ui.total)} downloaded" else it,
                        style = MaterialTheme.typography.bodySmall,
                        color = DotTheme.extra.accent,
                    )
                }
                DotPrimaryButton(
                    if (ui.bytes > 0) "Resume download" else "Download model",
                    onClick = onDownload,
                    icon = Icons.Rounded.Download,
                    accent = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (onUseOtherAppsCopy != null) {
                    DotOutlinedButton(
                        "Already downloaded in the other Dot app",
                        onClick = onUseOtherAppsCopy,
                        icon = Icons.Rounded.FolderOpen,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Allow “All files access” on the next screen so $appName can read that copy instead of downloading again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** A few keyword chips, wrapped; "Show more" reveals the rest. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeywordChips(keywords: List<String>, onClick: (String) -> Unit, modifier: Modifier = Modifier, initial: Int = 6) {
    if (keywords.isEmpty()) return
    var expanded by remember(keywords) { mutableStateOf(false) }
    val shown = if (expanded) keywords else keywords.take(initial)
    Column(modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            shown.forEach { k -> DotChip(k, onClick = { onClick(k) }) }
        }
        if (keywords.size > initial) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Show less" else "Show ${keywords.size - initial} more")
            }
        }
    }
}

/** The tiny red dot on thumbnails the AI has already described. */
@Composable
fun ProcessedDot(modifier: Modifier = Modifier) {
    Box(modifier.padding(5.dp).size(5.dp).clip(CircleShape).background(DotTheme.extra.accent))
}
