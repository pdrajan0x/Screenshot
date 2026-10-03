package com.pdrajan.dotgallery.ui

import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pdrajan.dot.design.ModelDownloadUi
import com.pdrajan.dot.design.ModelSetupScreen
import com.pdrajan.dot.design.SettingsRow
import com.pdrajan.dot.llm.ModelBundle
import com.pdrajan.dot.llm.ModelDownloader
import com.pdrajan.dot.llm.SharedModel
import com.pdrajan.dotgallery.GalleryContainer

private fun mb(bytes: Long) = "%,d MB".format(bytes / 1_000_000)

fun ModelBundle.downloadUi(state: ModelDownloader.State): ModelDownloadUi = when (state) {
    ModelDownloader.State.Ready -> ModelDownloadUi(true, false, false, sizeBytes, sizeBytes, null)
    is ModelDownloader.State.Downloading -> ModelDownloadUi(false, true, false, state.bytes, state.total, null)
    ModelDownloader.State.Verifying -> ModelDownloadUi(false, false, true, sizeBytes, sizeBytes, null)
    is ModelDownloader.State.Failed -> ModelDownloadUi(false, false, false, state.bytes, sizeBytes, state.message)
    ModelDownloader.State.Missing -> ModelDownloadUi(false, false, false, downloadedBytes(), sizeBytes, null)
}

/** Shown instead of the app until the AI model is on the phone. */
@Composable
fun ModelGate(c: GalleryContainer) {
    val ctx = LocalContext.current
    val model = c.describerModel
    val state by remember { model.state }.collectAsStateWithLifecycle(model.currentState())
    var othersCopy by remember { mutableStateOf(model.othersCopyLocked()) }
    // Back from "All files access" (or the Files app): look for the other app's copy again.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        model.refresh()
        othersCopy = model.othersCopyLocked()
    }
    ModelSetupScreen(
        appName = "Dot Gallery",
        modelName = model.label,
        ui = model.downloadUi(state),
        onDownload = { c.downloadDescriber() },
        onPause = { c.pauseDescriberDownload() },
        onUseOtherAppsCopy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            {
                if (SharedModel.canReadOtherAppsFiles()) {
                    model.refresh()
                    if (!model.isReady()) ctx.toast("Not found in Download/AI Models yet. Finish the download in Dot Screenshots first.")
                } else {
                    ctx.startSafely(SharedModel.allFilesAccessIntent(ctx))
                }
            }
        } else {
            null
        },
        othersCopyFound = othersCopy,
    )
}

/** Settings: what the AI model has done, and old model files that can go. */
@Composable
fun AiModelPanel(c: GalleryContainer) {
    val ctx = LocalContext.current
    val model = c.describerModel
    val counts by remember { c.repo.observeCaptionCounts() }.collectAsStateWithLifecycle(0 to 0)
    var retired by remember { mutableStateOf(SharedModel.retiredFiles(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { retired = SharedModel.retiredFiles(ctx) }
    SettingsRow(
        title = "AI descriptions",
        subtitle = "${counts.first} photos described" + (if (counts.second > 0) ", ${counts.second} waiting" else "") +
            ". ${model.label} runs on this phone; the model is in ${model.location() ?: "app storage"} (${mb(model.sizeBytes)}).",
        icon = Icons.Rounded.AutoAwesome,
        onClick = {},
    )
    if (retired.isNotEmpty()) {
        val bytes = retired.sumOf { it.first.length() }
        SettingsRow(
            title = "Delete old AI models",
            subtitle = "${retired.map { it.second }.distinct().joinToString()} · ${mb(bytes)} no longer used.",
            icon = Icons.Rounded.DeleteSweep,
            onClick = {
                retired.forEach { runCatching { it.first.delete() } }
                retired = SharedModel.retiredFiles(ctx)
                ctx.toast("Deleted")
            },
        )
    }
}
