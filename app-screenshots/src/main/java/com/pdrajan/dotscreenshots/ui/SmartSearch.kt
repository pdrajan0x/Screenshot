package com.pdrajan.dotscreenshots.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pdrajan.dot.design.DotOutlinedButton
import com.pdrajan.dot.design.DotPrimaryButton
import com.pdrajan.dot.design.DotTheme
import com.pdrajan.dot.design.SettingsRow
import com.pdrajan.dot.design.SettingsSwitchRow
import com.pdrajan.dot.llm.ModelDownloader
import com.pdrajan.dotscreenshots.AppContainer
import com.pdrajan.dotscreenshots.data.SummaryCounts

/** Whether Usage access is on, re-checked whenever the app comes back to the front. */
@Composable
fun rememberUsageAccess(c: AppContainer): Boolean {
    var granted by remember { mutableStateOf(c.engine.foreground.hasAccess()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val now = c.engine.foreground.hasAccess()
        // Just switched on: match recent screenshots to their apps.
        if (now && !granted) c.onForeground()
        granted = now
    }
    return granted
}

fun openUsageAccessSettings(context: Context) {
    val withPackage = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    runCatching { context.startActivity(withPackage.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .recoverCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun mb(bytes: Long) = "%,d MB".format(bytes / 1_000_000)

/** Settings rows: source-app detection and the AI summary model. */
@Composable
fun SmartSearchSettings(c: AppContainer) {
    val ctx = LocalContext.current
    val usage = rememberUsageAccess(c)
    SettingsRow(
        title = "Know which app each screenshot is from",
        subtitle = if (usage) {
            "On · exact for new screenshots and the last week, from Android's usage history. Stays on this phone."
        } else {
            "Off · tap and switch on Usage access for Dot Screenshots. Without it, apps are guessed from how the screen looks."
        },
        icon = Icons.Rounded.Apps,
        onClick = { openUsageAccessSettings(ctx) },
    )
    SummaryModelPanel(c)
}

@Composable
private fun SummaryModelPanel(c: AppContainer) {
    val state by c.modelDownload.state.collectAsStateWithLifecycle()
    val enabled by c.settings.summariesEnabled.collectAsStateWithLifecycle()
    val counts by remember { c.repo.observeSummaryCounts() }.collectAsStateWithLifecycle(SummaryCounts(0, 0))
    val spec = c.modelDownload.spec
    val m = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
    when (val s = state) {
        ModelDownloader.State.Ready -> {
            SettingsSwitchRow(
                title = "AI summaries",
                subtitle = "Titles, summaries and keywords written on this phone by ${spec.label}. " +
                    "${counts.done} done" + if (counts.waiting > 0) ", ${counts.waiting} waiting (mostly while charging)." else ".",
                checked = enabled,
                onCheckedChange = { c.settings.setSummariesEnabled(it); if (it) c.onForeground() },
            )
            TextButton(onClick = { c.deleteModel() }, modifier = Modifier.padding(start = 8.dp)) {
                Text("Delete model (${mb(spec.sizeBytes)})")
            }
        }
        is ModelDownloader.State.Downloading -> Column(m) {
            Text("Downloading ${spec.label}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { s.bytes.toFloat() / s.total },
                modifier = Modifier.fillMaxWidth(),
                color = DotTheme.extra.accent,
                strokeCap = StrokeCap.Round,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${mb(s.bytes)} of ${mb(s.total)} · keep the app open", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { c.pauseDownload() }) { Text("Pause") }
            }
        }
        ModelDownloader.State.Verifying -> Column(m) {
            Text("Checking the download…", style = MaterialTheme.typography.titleMedium)
        }
        is ModelDownloader.State.Failed -> Column(m) {
            Text("AI summaries", style = MaterialTheme.typography.titleMedium)
            Text(
                if (s.bytes > 0) "${s.message} · ${mb(s.bytes)} of ${mb(spec.sizeBytes)} downloaded" else s.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            DotOutlinedButton(if (s.bytes > 0) "Resume download" else "Try again", onClick = { c.downloadModel() }, icon = Icons.Rounded.Download)
        }
        ModelDownloader.State.Missing -> Column(m) {
            Text("AI summaries", style = MaterialTheme.typography.titleMedium)
            Text(
                "Titles, summaries and smarter search, written on this phone by ${spec.label}. " +
                    "One-time download of ${mb(spec.sizeBytes)} (Wi-Fi recommended). Nothing is uploaded.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            DotPrimaryButton("Download model", onClick = { c.downloadModel() }, icon = Icons.Rounded.Download, accent = true)
        }
    }
}

/** Home card nudging towards Usage access and the summary model, until both are set up or dismissed. */
@Composable
fun SmartSearchTip(c: AppContainer, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val dismissed by c.settings.smartTipDismissed.collectAsStateWithLifecycle()
    val usage = rememberUsageAccess(c)
    val model by c.modelDownload.state.collectAsStateWithLifecycle()
    val modelSetUp = model != ModelDownloader.State.Missing
    if (dismissed || (usage && modelSetUp)) return
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = DotTheme.extra.accent)
                Text("Make search smarter", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 10.dp).weight(1f))
                IconButton(onClick = { c.settings.dismissSmartTip() }) { Icon(Icons.Rounded.Close, "Dismiss") }
            }
            Text(
                "Find a WhatsApp chat by searching \"whatsapp\", and get a title and summary for every screenshot — all on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!usage) DotOutlinedButton("Detect apps", onClick = { openUsageAccessSettings(ctx) }, icon = Icons.Rounded.Apps)
                if (!modelSetUp) DotOutlinedButton("Get summaries (${mb(c.modelDownload.spec.sizeBytes)})", onClick = { c.downloadModel() }, icon = Icons.Rounded.Download)
            }
        }
    }
}
