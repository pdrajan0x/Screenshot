package com.pdrajan.dotgallery.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView
import com.pdrajan.dot.design.DotLoader
import com.pdrajan.dot.design.DotPrimaryButton
import com.pdrajan.dot.design.DotTheme
import com.pdrajan.dot.media.MediaWriter
import com.pdrajan.dotgallery.data.Media
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Trim, mute and rotate a video; exports a new copy with Media3 Transformer. */
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@OptIn(UnstableApi::class)
@Composable
fun VideoTrimScreen(id: Long, nav: GalleryNav) {
    val c = galleryContainer()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var media by remember { mutableStateOf<Media?>(null) }
    var range by remember { mutableStateOf(0f..1f) }
    var mute by remember { mutableStateOf(false) }
    var rotation by remember { mutableIntStateOf(0) }
    var exporting by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(id) { media = c.repo.mediaByIds(listOf(id))[id] }
    val m = media
    if (m == null) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) { DotLoader() }
        return
    }
    val duration = m.durationMs.coerceAtLeast(1)
    val player = remember(m.id) { ExoPlayer.Builder(ctx).build().apply { setMediaItem(MediaItem.fromUri(m.uri)); prepare() } }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(range.start) { player.seekTo((range.start * duration).toLong()) }
    LaunchedEffect(mute) { player.volume = if (mute) 0f else 1f }

    fun export() {
        exporting = true
        val out = File(ctx.cacheDir, "trim-${System.currentTimeMillis()}.mp4")
        val clipped = MediaItem.Builder()
            .setUri(m.uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs((range.start * duration).toLong())
                    .setEndPositionMs((range.endInclusive * duration).toLong())
                    .build(),
            )
            .build()
        val effects = if (rotation != 0) listOf(ScaleAndRotateTransformation.Builder().setRotationDegrees(rotation.toFloat()).build()) else emptyList()
        val edited = EditedMediaItem.Builder(clipped).setRemoveAudio(mute).setEffects(Effects(emptyList(), effects)).build()
        val transformer = Transformer.Builder(ctx)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    scope.launch {
                        val uri = MediaWriter.saveFile(ctx, out, m.name.substringBeforeLast('.') + "_trimmed.mp4", "video/mp4")
                        out.delete()
                        exporting = false
                        ctx.toast(if (uri != null) "Saved to ${MediaWriter.ALBUM_DIR}" else "Couldn't save")
                        c.refresh()
                        if (uri != null) nav.back()
                    }
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    exporting = false
                    out.delete()
                    ctx.toast("Export failed")
                }
            })
            .build()
        transformer.start(edited, out.absolutePath)
        scope.launch {
            val holder = ProgressHolder()
            while (exporting) {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress = holder.progress / 100f
                delay(250)
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.back() }) { Icon(Icons.Rounded.Close, "Cancel", tint = Color.White) }
            Spacer(Modifier.weight(1f))
            if (!exporting) DotPrimaryButton("Save copy", onClick = ::export, accent = true, modifier = Modifier.padding(end = 8.dp))
        }
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player; useController = true } },
            modifier = Modifier.weight(1f).fillMaxWidth().rotate(rotation.toFloat()),
        )
        Column(Modifier.fillMaxWidth().background(Color(0xFF0A0A0A)).padding(16.dp)) {
            if (exporting) {
                Text("Exporting…", style = MaterialTheme.typography.labelMedium, color = Color.White)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), color = DotTheme.extra.accent)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatDuration((range.start * duration).toLong()), style = MaterialTheme.typography.labelMedium, color = Color.White)
                    Text("${formatDuration(((range.endInclusive - range.start) * duration).toLong())} selected", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                    Text(formatDuration((range.endInclusive * duration).toLong()), style = MaterialTheme.typography.labelMedium, color = Color.White)
                }
                RangeSlider(
                    value = range,
                    onValueChange = { if (it.endInclusive - it.start > 0.01f) range = it },
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = DotTheme.extra.accent, inactiveTrackColor = Color(0xFF333333)),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { mute = !mute }) {
                        Icon(if (mute) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, if (mute) "Unmute" else "Mute", tint = Color.White)
                    }
                    Text(if (mute) "Sound off" else "Sound on", style = MaterialTheme.typography.labelMedium, color = Color.White)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { rotation = (rotation + 90) % 360 }) { Icon(Icons.Rounded.RotateRight, "Rotate", tint = Color.White) }
                }
            }
        }
    }
}
