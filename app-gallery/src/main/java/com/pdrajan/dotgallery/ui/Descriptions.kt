package com.pdrajan.dotgallery.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.pdrajan.dotgallery.GalleryContainer

private fun mb(bytes: Long) = "%,d MB".format(bytes / 1_000_000)

/** Settings: the photo description model (download, progress, on/off, delete). */
@Composable
fun PhotoDescriptionsPanel(c: GalleryContainer) {
    val model = c.describerModel
    val state by remember { model.state }.collectAsStateWithLifecycle(model.currentState())
    val enabled by c.settings.captionsEnabled.collectAsStateWithLifecycle()
    val counts by remember { c.repo.observeCaptionCounts() }.collectAsStateWithLifecycle(0 to 0)
    val m = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
    when (val s = state) {
        ModelDownloader.State.Ready -> {
            SettingsSwitchRow(
                title = "Photo descriptions",
                subtitle = "A sentence about each photo, written on this phone by ${model.label}, so you can search what's in it. " +
                    "${counts.first} described" + if (counts.second > 0) ", ${counts.second} waiting (described while charging)." else ".",
                checked = enabled,
                onCheckedChange = { c.settings.setCaptionsEnabled(it); if (it) c.onForeground() },
            )
            Text(
                "Model saved in Download/AI Models · other apps can open it from there",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            TextButton(onClick = { c.deleteDescriber() }, modifier = Modifier.padding(start = 8.dp)) {
                Text("Delete model (${mb(model.sizeBytes)})")
            }
        }
        is ModelDownloader.State.Downloading -> Column(m) {
            Text("Downloading ${model.label}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { s.bytes.toFloat() / s.total },
                modifier = Modifier.fillMaxWidth(),
                color = DotTheme.extra.accent,
                strokeCap = StrokeCap.Round,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${mb(s.bytes)} of ${mb(s.total)} · keep the app open",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { c.pauseDescriberDownload() }) { Text("Pause") }
            }
        }
        ModelDownloader.State.Verifying -> Column(m) {
            Text("Checking the download…", style = MaterialTheme.typography.titleMedium)
        }
        is ModelDownloader.State.Failed -> Column(m) {
            Text("Photo descriptions", style = MaterialTheme.typography.titleMedium)
            Text(
                if (s.bytes > 0) "${s.message} · ${mb(s.bytes)} of ${mb(model.sizeBytes)} downloaded" else s.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            DotOutlinedButton(if (s.bytes > 0) "Resume download" else "Try again", onClick = { c.downloadDescriber() }, icon = Icons.Rounded.Download)
        }
        ModelDownloader.State.Missing -> Column(m) {
            Text("Photo descriptions", style = MaterialTheme.typography.titleMedium)
            Text(
                "A sentence about each photo (\"Two people on a beach at sunset\"), written on this phone, so you can search what's " +
                    "in your photos. One-time download of ${mb(model.sizeBytes)} (Wi-Fi recommended), saved in Download/AI Models. " +
                    "Older photos are described while charging. Nothing is uploaded.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            DotPrimaryButton("Download model", onClick = { c.downloadDescriber() }, icon = Icons.Rounded.Download, accent = true)
        }
    }
}
