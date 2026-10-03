package com.pdrajan.dotscreenshots.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pdrajan.dot.design.DotOutlinedButton
import com.pdrajan.dot.design.DotPrimaryButton
import com.pdrajan.dot.design.DotTheme
import com.pdrajan.dot.design.SettingsSwitchRow
import com.pdrajan.dot.llm.ModelDownloader
import com.pdrajan.dot.llm.ModelSource
import com.pdrajan.dotscreenshots.AppContainer
import com.pdrajan.dotscreenshots.data.SummaryCounts

private fun mb(bytes: Long) = "%,d MB".format(bytes / 1_000_000)

/** Settings rows: the AI summary model. */
@Composable
fun SmartSearchSettings(c: AppContainer) {
    SummaryModelPanel(c)
}

@Composable
private fun SummaryModelPanel(c: AppContainer) {
    val state by c.modelDownload.state.collectAsStateWithLifecycle()
    val enabled by c.settings.summariesEnabled.collectAsStateWithLifecycle()
    val counts by remember { c.repo.observeSummaryCounts() }.collectAsStateWithLifecycle(SummaryCounts(0, 0))
    val spec = c.modelDownload.spec
    val shared = c.modelDownload.sharedDir != null
    // A copy already on the phone: one another app downloaded, or this app's own after a reinstall.
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(c::useModelFile) }
    val pickButton = @Composable {
        TextButton(onClick = { pickFile.launch(arrayOf("*/*")) }) {
            Icon(Icons.Rounded.FolderOpen, null, modifier = Modifier.padding(end = 8.dp))
            Text("Use a file I already have")
        }
    }
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
            val picked = c.modelDownload.source() is ModelSource.Picked
            c.modelDownload.describeLocation()?.let { where ->
                Text(
                    if (picked || where == "app storage") "Model: $where" else "Model saved in $where · other apps can open it from there",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            TextButton(onClick = { c.deleteModel() }, modifier = Modifier.padding(start = 8.dp)) {
                Text(if (picked) "Stop using this file" else "Delete model (${mb(spec.sizeBytes)})")
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
            Text("Checking the model file…", style = MaterialTheme.typography.titleMedium)
            Text("Takes a few seconds", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is ModelDownloader.State.Failed -> Column(m) {
            Text("AI summaries", style = MaterialTheme.typography.titleMedium)
            Text(
                if (s.bytes > 0) "${s.message} · ${mb(s.bytes)} of ${mb(spec.sizeBytes)} downloaded" else s.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            DotOutlinedButton(if (s.bytes > 0) "Resume download" else "Download model", onClick = { c.downloadModel() }, icon = Icons.Rounded.Download)
            pickButton()
        }
        ModelDownloader.State.Missing -> Column(m) {
            Text("AI summaries", style = MaterialTheme.typography.titleMedium)
            Text(
                "Titles, summaries and smarter search, written on this phone by ${spec.label}. " +
                    "One-time download of ${mb(spec.sizeBytes)} (Wi-Fi recommended). Nothing is uploaded." +
                    if (shared) " Saved in Download/${ModelDownloader.SHARED_FOLDER} so other apps can use it too." else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            DotPrimaryButton("Download model", onClick = { c.downloadModel() }, icon = Icons.Rounded.Download, accent = true)
            pickButton()
        }
    }
}

/** Home card suggesting the summary model, until it's set up or dismissed. */
@Composable
fun SmartSearchTip(c: AppContainer, modifier: Modifier = Modifier) {
    val dismissed by c.settings.smartTipDismissed.collectAsStateWithLifecycle()
    val model by c.modelDownload.state.collectAsStateWithLifecycle()
    if (dismissed || model != ModelDownloader.State.Missing) return
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = DotTheme.extra.accent)
                Text("Make search smarter", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 10.dp).weight(1f))
                IconButton(onClick = { c.settings.dismissSmartTip() }) { Icon(Icons.Rounded.Close, "Dismiss") }
            }
            Text(
                "Get a title, summary and keywords for every screenshot, written on this phone. Nothing is uploaded.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp),
            )
            Spacer(Modifier.height(10.dp))
            DotOutlinedButton("Get summaries (${mb(c.modelDownload.spec.sizeBytes)})", onClick = { c.downloadModel() }, icon = Icons.Rounded.Download)
        }
    }
}
