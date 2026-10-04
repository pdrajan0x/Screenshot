package com.pdrajan.dot.design

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sync
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pdrajan.dot.media.ProcessingPolicy
import kotlin.math.roundToInt

/**
 * Settings → Processing. New items are always finished right away (on battery only above the chosen
 * level); the older library waits for the charger unless "also on battery" is chosen.
 */
@Composable
fun ProcessingSettings(policy: ProcessingPolicy, onChange: (ProcessingPolicy) -> Unit, appName: String) {
    SettingsSwitchRow(
        title = "Process in the background",
        subtitle = if (policy.background) {
            "New items are read even when $appName is closed."
        } else {
            "Only while $appName is open."
        },
        checked = policy.background,
        onCheckedChange = { onChange(policy.copy(background = it)) },
        icon = Icons.Rounded.Sync,
    )
    // Lined up with the rows' text (after their icons).
    Column(Modifier.padding(start = 60.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)) {
        Text("Older items", style = MaterialTheme.typography.titleMedium)
        Text(
            "New ones are always done right away. Going through everything from before takes a lot of power.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DotChip("Only while charging", onClick = { onChange(policy.copy(onBattery = false)) }, selected = !policy.onBattery)
            DotChip("Also on battery", onClick = { onChange(policy.copy(onBattery = true)) }, selected = policy.onBattery)
        }
        var level by remember(policy.minBattery) { mutableFloatStateOf(policy.minBattery.toFloat()) }
        Spacer(Modifier.height(12.dp))
        Text("On battery, only above ${level.roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = level,
            onValueChange = { level = it },
            onValueChangeFinished = { onChange(policy.copy(minBattery = level.roundToInt())) },
            valueRange = 20f..90f,
            steps = 6,
            colors = SliderDefaults.colors(thumbColor = DotTheme.extra.accent, activeTrackColor = DotTheme.extra.accent),
        )
        Text(
            "In the background, battery saver or a warm phone pause it. Charging is always allowed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A few keyword chips, wrapped; "Show more" reveals the rest. Tap one to search it, long-press to copy it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeywordChips(keywords: List<String>, onClick: (String) -> Unit, modifier: Modifier = Modifier, initial: Int = 6) {
    if (keywords.isEmpty()) return
    val context = LocalContext.current
    var expanded by remember(keywords) { mutableStateOf(false) }
    val shown = if (expanded) keywords else keywords.take(initial)
    Column(modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            shown.forEach { k -> KeywordChip(k, onClick = { onClick(k) }, onLongClick = { copyText(context, k) }) }
        }
        if (keywords.size > initial) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Show less" else "Show ${keywords.size - initial} more")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeywordChip(label: String, onClick: () -> Unit, onLongClick: () -> Unit) {
    Box(
        Modifier.height(36.dp).clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** Puts [text] on the clipboard (Android 13+ shows its own confirmation). */
fun copyText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Keyword", text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "Copied “$text”", Toast.LENGTH_SHORT).show()
}

/** The tiny red dot on thumbnails the AI hasn't processed yet. */
@Composable
fun PendingDot(modifier: Modifier = Modifier) {
    Box(modifier.padding(4.dp).size(4.dp).clip(CircleShape).background(DotTheme.extra.accent))
}
