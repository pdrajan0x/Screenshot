package com.pdrajan.dotgallery.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.RotateLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import com.pdrajan.dot.design.DotLoader
import com.pdrajan.dot.design.DotPrimaryButton
import com.pdrajan.dot.design.DotTheme
import com.pdrajan.dot.media.MediaWriter
import com.pdrajan.dot.ml.BitmapLoader
import com.pdrajan.dotgallery.data.Media
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private enum class EditTab(val label: String) { CROP("Crop"), ADJUST("Adjust"), FILTERS("Filters"), MARKUP("Markup") }

private data class Aspect(val label: String, val ratio: Float?)
private val ASPECTS = listOf(Aspect("Free", null), Aspect("Original", -1f), Aspect("1:1", 1f), Aspect("4:3", 4f / 3), Aspect("3:4", 3f / 4), Aspect("16:9", 16f / 9), Aspect("9:16", 9f / 16))

private class Stroke2(val points: List<Offset>, val color: Color, val width: Float, val highlighter: Boolean)

/** 4×5 colour matrix helpers (row-major, same layout as android.graphics.ColorMatrix). */
private object CM {
    fun identity() = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)

    /** a ∘ b: apply b first, then a. */
    fun concat(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(20)
        for (r in 0 until 4) {
            for (c in 0 until 5) {
                var v = if (c == 4) a[r * 5 + 4] else 0f
                for (k in 0 until 4) v += a[r * 5 + k] * b[k * 5 + c]
                out[r * 5 + c] = v
            }
        }
        return out
    }

    fun saturation(s: Float): FloatArray {
        val m = android.graphics.ColorMatrix().apply { setSaturation(s) }
        return m.array.copyOf()
    }

    fun scaleOffset(rs: Float, gs: Float, bs: Float, ro: Float = 0f, go: Float = 0f, bo: Float = 0f) =
        floatArrayOf(rs, 0f, 0f, 0f, ro, 0f, gs, 0f, 0f, go, 0f, 0f, bs, 0f, bo, 0f, 0f, 0f, 1f, 0f)

    fun contrast(c: Float): FloatArray {
        val t = 128f * (1 - c)
        return scaleOffset(c, c, c, t, t, t)
    }

    fun sepia() = floatArrayOf(0.393f, 0.769f, 0.189f, 0f, 0f, 0.349f, 0.686f, 0.168f, 0f, 0f, 0.272f, 0.534f, 0.131f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
}

private data class Filter(val name: String, val matrix: FloatArray)
private val FILTERS = listOf(
    Filter("Original", CM.identity()),
    Filter("Vivid", CM.concat(CM.contrast(1.1f), CM.saturation(1.35f))),
    Filter("Warm", CM.scaleOffset(1.08f, 1.0f, 0.9f, 6f, 0f, -6f)),
    Filter("Cool", CM.scaleOffset(0.92f, 1.0f, 1.08f, -6f, 0f, 8f)),
    Filter("Mono", CM.saturation(0f)),
    Filter("Dot", CM.concat(CM.contrast(1.3f), CM.saturation(0f))),
    Filter("Noir", CM.concat(CM.contrast(1.55f), CM.saturation(0f))),
    Filter("Fade", CM.concat(CM.scaleOffset(1f, 1f, 1f, 18f, 18f, 18f), CM.contrast(0.82f))),
    Filter("Retro", CM.concat(CM.contrast(1.05f), CM.sepia())),
)

@Composable
fun EditorScreen(id: Long, nav: GalleryNav) {
    val c = galleryContainer()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var media by remember { mutableStateOf<Media?>(null) }
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var base by remember { mutableStateOf<ImageBitmap?>(null) }
    var baseBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var tab by remember { mutableStateOf(EditTab.CROP) }
    var rotation by remember { mutableIntStateOf(0) }
    var flip by remember { mutableStateOf(false) }
    var straighten by remember { mutableFloatStateOf(0f) }
    var crop by remember { mutableStateOf(Rect(0f, 0f, 1f, 1f)) }
    var aspect by remember { mutableStateOf(ASPECTS.first()) }
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(0f) }
    var saturation by remember { mutableFloatStateOf(0f) }
    var warmth by remember { mutableFloatStateOf(0f) }
    var vignette by remember { mutableFloatStateOf(0f) }
    var filter by remember { mutableStateOf(FILTERS.first()) }
    val strokes = remember { mutableStateListOf<Stroke2>() }
    var penColor by remember { mutableStateOf(Color(0xFFD71921)) }
    var highlighter by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        val m = c.repo.mediaByIds(listOf(id))[id] ?: return@LaunchedEffect
        media = m
        source = withContext(Dispatchers.IO) { BitmapLoader.load(ctx.contentResolver, m.uri, maxWidth = 2560, maxPixels = 8_000_000) }
    }

    // Rotate and flip are taps: the base is rebuilt. Straighten and every adjustment are drawn live
    // on top of it and only applied to the full-size image when saving.
    LaunchedEffect(source, rotation, flip) {
        val src = source ?: return@LaunchedEffect
        val b = withContext(Dispatchers.Default) { transformBase(src, rotation, flip, 0f) }
        baseBitmap = b
        base = b.asImageBitmap()
    }

    val edited = rotation != 0 || flip || straighten != 0f || crop != Rect(0f, 0f, 1f, 1f) || brightness != 0f || contrast != 0f ||
        saturation != 0f || warmth != 0f || vignette != 0f || filter != FILTERS.first() || strokes.isNotEmpty()

    fun reset() {
        rotation = 0
        flip = false
        straighten = 0f
        crop = Rect(0f, 0f, 1f, 1f)
        aspect = ASPECTS.first()
        brightness = 0f
        contrast = 0f
        saturation = 0f
        warmth = 0f
        vignette = 0f
        filter = FILTERS.first()
        strokes.clear()
    }

    fun colorMatrix(): FloatArray {
        var m = CM.identity()
        m = CM.concat(CM.scaleOffset(1f, 1f, 1f, brightness * 70f, brightness * 70f, brightness * 70f), m)
        m = CM.concat(CM.contrast(1f + contrast * 0.6f), m)
        m = CM.concat(CM.saturation(1f + saturation), m)
        m = CM.concat(CM.scaleOffset(1f, 1f, 1f, warmth * 25f, 0f, -warmth * 25f), m)
        return CM.concat(filter.matrix, m)
    }

    fun save() {
        val b = baseBitmap ?: return
        val m = media ?: return
        saving = true
        scope.launch {
            val out = withContext(Dispatchers.Default) {
                val straight = if (straighten != 0f) transformBase(b, 0, false, straighten) else b
                render(straight, crop, colorMatrix(), vignette, strokes.toList()).also { if (straight !== b) straight.recycle() }
            }
            val name = m.name.substringBeforeLast('.') + "_edited.jpg"
            val uri = withContext(Dispatchers.IO) {
                val tmp = File(ctx.cacheDir, "edit-${System.currentTimeMillis()}.jpg")
                tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                // Keep the original's capture time so the copy sits next to it in the timeline.
                runCatching {
                    val exif = ExifInterface(tmp)
                    val fmt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                    exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, fmt.format(Date(m.takenAt)))
                    exif.setAttribute(ExifInterface.TAG_DATETIME, fmt.format(Date(m.takenAt)))
                    exif.saveAttributes()
                }
                MediaWriter.saveFile(ctx, tmp, name, "image/jpeg").also { tmp.delete() }
            }
            out.recycle()
            saving = false
            if (uri != null) {
                ctx.toast("Saved a copy to ${MediaWriter.ALBUM_DIR}")
                c.refresh()
                nav.back()
            } else {
                ctx.toast("Couldn't save")
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.back() }) { Icon(Icons.Rounded.Close, "Cancel", tint = Color.White) }
            Spacer(Modifier.weight(1f))
            if (edited && !saving) {
                TextButton(onClick = ::reset) { Text("Reset", color = Color.White) }
            }
            if (saving) DotLoader(Modifier.padding(end = 16.dp))
            else DotPrimaryButton("Save copy", onClick = ::save, accent = true, modifier = Modifier.padding(end = 8.dp))
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            val img = base
            if (img == null) {
                DotLoader()
            } else {
                val showCrop = tab == EditTab.CROP
                val region = if (showCrop) Rect(0f, 0f, 1f, 1f) else crop
                val regionW = img.width * region.width
                val regionH = img.height * region.height
                val scale = min(constraints.maxWidth / regionW, constraints.maxHeight / regionH)
                val w = regionW * scale
                val h = regionH * scale
                val density = androidx.compose.ui.platform.LocalDensity.current
                Box(Modifier.size(with(density) { w.toDp() }, with(density) { h.toDp() })) {
                    Canvas(
                        Modifier.fillMaxSize().then(
                            if (tab == EditTab.MARKUP) {
                                Modifier.pointerInput(penColor, highlighter) {
                                    var current = mutableListOf<Offset>()
                                    detectDragGestures(
                                        onDragStart = { o -> current = mutableListOf(Offset(o.x / size.width, o.y / size.height)) },
                                        onDrag = { change, _ ->
                                            current.add(Offset(change.position.x / size.width, change.position.y / size.height))
                                            if (strokes.isNotEmpty() && strokes.last().points === current) strokes.removeAt(strokes.lastIndex)
                                            strokes.add(Stroke2(current, penColor, if (highlighter) 0.035f else 0.012f, highlighter))
                                        },
                                    )
                                }
                            } else {
                                Modifier
                            },
                        ),
                    ) {
                        // Read here (draw phase), so moving a slider only redraws the picture.
                        val filterColors = androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(colorMatrix()))
                        // The whole (straightened) picture in this view's coordinates; the crop region fills the view.
                        val fullW = size.width / region.width
                        val fullH = size.height / region.height
                        val pivot = Offset(fullW / 2, fullH / 2)
                        val zoom = straightenZoom(fullW, fullH, straighten)
                        clipRect {
                            withTransform({
                                translate(-region.left * fullW, -region.top * fullH)
                                rotate(straighten, pivot)
                                scale(zoom, zoom, pivot)
                            }) {
                                drawImage(
                                    image = img,
                                    dstSize = IntSize(fullW.toInt().coerceAtLeast(1), fullH.toInt().coerceAtLeast(1)),
                                    colorFilter = filterColors,
                                    filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
                                )
                            }
                        }
                        if (vignette > 0f) {
                            drawRect(
                                brush = androidx.compose.ui.graphics.Brush.radialGradient(
                                    0.55f to Color.Transparent,
                                    1f to Color.Black.copy(alpha = 0.75f * vignette),
                                    center = center,
                                    radius = max(size.width, size.height) * 0.75f,
                                ),
                            )
                        }
                        if (!showCrop) {
                            strokes.forEach { s -> drawPath(strokePath(s.points, size), s.color.copy(alpha = if (s.highlighter) 0.4f else 1f), style = Stroke(width = s.width * size.width, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
                        }
                    }
                    if (showCrop) {
                        CropOverlay(crop, aspect.ratio?.let { if (it < 0) img.width.toFloat() / img.height else it }, img.width.toFloat() / img.height) { crop = it }
                    }
                }
            }
        }

        Column(Modifier.fillMaxWidth().background(Color(0xFF0A0A0A)).padding(vertical = 8.dp)) {
            when (tab) {
                EditTab.CROP -> {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ASPECTS.forEach { a ->
                            ToolChip(a.label, selected = a == aspect) {
                                aspect = a
                                crop = fitAspect(crop, a.ratio?.let { r -> if (r < 0) (base?.width ?: 1).toFloat() / (base?.height ?: 1) else r }, (base?.width ?: 1).toFloat() / (base?.height ?: 1))
                            }
                        }
                    }
                    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { rotation = (rotation + 270) % 360; crop = Rect(0f, 0f, 1f, 1f) }) { Icon(Icons.Rounded.RotateLeft, "Rotate", tint = Color.White) }
                        IconButton(onClick = { flip = !flip }) { Icon(Icons.Rounded.Flip, "Flip", tint = Color.White) }
                        Text("Straighten", style = MaterialTheme.typography.labelMedium, color = Color.White, modifier = Modifier.padding(horizontal = 8.dp))
                        Slider(value = straighten, onValueChange = { straighten = it }, valueRange = -45f..45f, modifier = Modifier.weight(1f), colors = sliderColors())
                        Text("${straighten.toInt()}°", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.width(36.dp).clickable { straighten = 0f })
                    }
                }
                EditTab.ADJUST -> Column(Modifier.padding(horizontal = 16.dp)) {
                    AdjustRow("Brightness", brightness) { brightness = it }
                    AdjustRow("Contrast", contrast) { contrast = it }
                    AdjustRow("Saturation", saturation) { saturation = it }
                    AdjustRow("Warmth", warmth) { warmth = it }
                    AdjustRow("Vignette", vignette, 0f..1f) { vignette = it }
                }
                EditTab.FILTERS -> Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FILTERS.forEach { f -> ToolChip(f.name, selected = f == filter) { filter = f } }
                }
                EditTab.MARKUP -> Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(Color(0xFFD71921), Color.White, Color.Black, Color(0xFFFFD60A), Color(0xFF34C759), Color(0xFF0A84FF)).forEach { col ->
                        Box(
                            Modifier.padding(4.dp).size(28.dp).clip(CircleShape).background(col)
                                .border(if (col == penColor) 3.dp else 1.dp, if (col == penColor) Color.White else Color.Gray, CircleShape)
                                .clickable { penColor = col },
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    ToolChip(if (highlighter) "Highlighter" else "Pen", selected = true) { highlighter = !highlighter }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }) { Icon(Icons.AutoMirrored.Rounded.Undo, "Undo", tint = Color.White) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                EditTab.entries.forEach { t ->
                    Column(Modifier.clickable { tab = t }.padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(t.label.uppercase(), style = MaterialTheme.typography.labelMedium, color = if (t == tab) Color.White else Color.Gray)
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.size(4.dp).clip(CircleShape).background(if (t == tab) DotTheme.extra.accent else Color.Transparent))
                    }
                }
            }
        }
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = DotTheme.extra.accent, inactiveTrackColor = Color(0xFF333333))

@Composable
private fun AdjustRow(label: String, value: Float, range: ClosedFloatingPointRange<Float> = -1f..1f, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White, modifier = Modifier.width(92.dp))
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f), colors = sliderColors())
        Text("${(value * 100).toInt()}", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.width(36.dp))
    }
}

@Composable
private fun ToolChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(CircleShape)
            .background(if (selected) Color.White else Color.Transparent)
            .border(1.dp, if (selected) Color.White else Color(0xFF444444), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) Color.Black else Color.White)
    }
}

/** Crop rectangle (normalised) with draggable corners and body. */
@Composable
private fun CropOverlay(rect: Rect, ratio: Float?, imageAspect: Float, onChange: (Rect) -> Unit) {
    Canvas(
        Modifier.fillMaxSize().pointerInput(ratio) {
            var mode = 0 // 0 move, 1 TL, 2 TR, 3 BL, 4 BR
            var r = rect
            detectDragGestures(
                onDragStart = { o ->
                    r = rect
                    val p = Offset(o.x / size.width, o.y / size.height)
                    val corners = listOf(Offset(r.left, r.top), Offset(r.right, r.top), Offset(r.left, r.bottom), Offset(r.right, r.bottom))
                    val near = corners.indexOfFirst { (it - p).getDistance() < 0.08f }
                    mode = if (near >= 0) near + 1 else 0
                },
                onDrag = { change, drag ->
                    val dx = drag.x / size.width
                    val dy = drag.y / size.height
                    r = when (mode) {
                        0 -> {
                            val nx = (r.left + dx).coerceIn(0f, 1f - r.width)
                            val ny = (r.top + dy).coerceIn(0f, 1f - r.height)
                            Rect(nx, ny, nx + r.width, ny + r.height)
                        }
                        1 -> Rect((r.left + dx).coerceIn(0f, r.right - 0.1f), (r.top + dy).coerceIn(0f, r.bottom - 0.1f), r.right, r.bottom)
                        2 -> Rect(r.left, (r.top + dy).coerceIn(0f, r.bottom - 0.1f), (r.right + dx).coerceIn(r.left + 0.1f, 1f), r.bottom)
                        3 -> Rect((r.left + dx).coerceIn(0f, r.right - 0.1f), r.top, r.right, (r.bottom + dy).coerceIn(r.top + 0.1f, 1f))
                        else -> Rect(r.left, r.top, (r.right + dx).coerceIn(r.left + 0.1f, 1f), (r.bottom + dy).coerceIn(r.top + 0.1f, 1f))
                    }
                    if (mode != 0 && ratio != null) r = fitAspect(r, ratio, imageAspect)
                    onChange(r)
                    change.consume()
                },
            )
        },
    ) {
        val l = rect.left * size.width
        val t = rect.top * size.height
        val rr = rect.right * size.width
        val b = rect.bottom * size.height
        val shade = Color.Black.copy(alpha = 0.55f)
        drawRect(shade, Offset.Zero, Size(size.width, t))
        drawRect(shade, Offset(0f, b), Size(size.width, size.height - b))
        drawRect(shade, Offset(0f, t), Size(l, b - t))
        drawRect(shade, Offset(rr, t), Size(size.width - rr, b - t))
        drawRect(Color.White, Offset(l, t), Size(rr - l, b - t), style = Stroke(2.dp.toPx()))
        for (i in 1..2) {
            drawLine(Color.White.copy(alpha = 0.4f), Offset(l + (rr - l) * i / 3, t), Offset(l + (rr - l) * i / 3, b))
            drawLine(Color.White.copy(alpha = 0.4f), Offset(l, t + (b - t) * i / 3), Offset(rr, t + (b - t) * i / 3))
        }
        listOf(Offset(l, t), Offset(rr, t), Offset(l, b), Offset(rr, b)).forEach { drawCircle(Color.White, 7.dp.toPx(), it) }
    }
}

/** Shrinks [r] around its centre to match [ratio] (width/height in pixels). */
private fun fitAspect(r: Rect, ratio: Float?, imageAspect: Float): Rect {
    if (ratio == null) return r
    val target = ratio / imageAspect // in normalised units
    var w = r.width
    var h = r.height
    if (w / h > target) w = h * target else h = w / target
    val cx = r.center.x
    val cy = r.center.y
    val left = (cx - w / 2).coerceIn(0f, 1f - w)
    val top = (cy - h / 2).coerceIn(0f, 1f - h)
    return Rect(left, top, left + w, top + h)
}

private fun strokePath(points: List<Offset>, size: Size): Path = Path().apply {
    points.firstOrNull()?.let { moveTo(it.x * size.width, it.y * size.height) }
    points.drop(1).forEach { lineTo(it.x * size.width, it.y * size.height) }
}

/** How much a picture of [w]×[h] must grow, rotated by [degrees], to leave no empty corners. */
private fun straightenZoom(w: Float, h: Float, degrees: Float): Float {
    if (degrees == 0f || w <= 0f || h <= 0f) return 1f
    val rad = Math.toRadians(abs(degrees).toDouble())
    return max((w * cos(rad) + h * sin(rad)) / w, (w * sin(rad) + h * cos(rad)) / h).toFloat()
}

private fun transformBase(src: Bitmap, rotation: Int, flip: Boolean, straighten: Float): Bitmap {
    val m = Matrix()
    if (flip) m.postScale(-1f, 1f)
    m.postRotate(rotation.toFloat())
    var out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    if (straighten != 0f) {
        val w = out.width.toFloat()
        val h = out.height.toFloat()
        val s = straightenZoom(w, h, straighten)
        val dst = Bitmap.createBitmap(out.width, out.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dst)
        canvas.translate(w / 2, h / 2)
        canvas.rotate(straighten)
        canvas.scale(s, s)
        canvas.translate(-w / 2, -h / 2)
        canvas.drawBitmap(out, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        if (out !== src) out.recycle()
        out = dst
    }
    return out
}

private fun render(base: Bitmap, crop: Rect, matrix: FloatArray, vignette: Float, strokes: List<Stroke2>): Bitmap {
    val x = (crop.left * base.width).toInt().coerceIn(0, base.width - 1)
    val y = (crop.top * base.height).toInt().coerceIn(0, base.height - 1)
    val w = (crop.width * base.width).toInt().coerceIn(1, base.width - x)
    val h = (crop.height * base.height).toInt().coerceIn(1, base.height - y)
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) }
    canvas.drawBitmap(base, android.graphics.Rect(x, y, x + w, y + h), android.graphics.Rect(0, 0, w, h), paint)
    if (vignette > 0f) {
        val radius = max(w, h) * 0.75f
        val shader = RadialGradient(w / 2f, h / 2f, radius, intArrayOf(0, 0, Color.Black.copy(alpha = 0.75f * vignette).toArgb()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { this.shader = shader })
    }
    strokes.forEach { s ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = s.color.copy(alpha = if (s.highlighter) 0.4f else 1f).toArgb()
            style = Paint.Style.STROKE
            strokeWidth = s.width * w
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = android.graphics.Path()
        s.points.firstOrNull()?.let { path.moveTo(it.x * w, it.y * h) }
        s.points.drop(1).forEach { path.lineTo(it.x * w, it.y * h) }
        canvas.drawPath(path, p)
    }
    return out
}
