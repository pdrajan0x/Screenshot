package com.pdrajan.dotgallery.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.pdrajan.dot.design.DateLabels
import com.pdrajan.dot.design.DotChip
import com.pdrajan.dot.design.DotTheme
import com.pdrajan.dot.design.KeywordChips
import com.pdrajan.dot.media.MediaActions
import com.pdrajan.dot.media.MediaInfo
import com.pdrajan.dot.media.PhotoInfo
import com.pdrajan.dotgallery.data.Media
import com.pdrajan.dotgallery.data.MediaDetail
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(initialId: Long, nav: GalleryNav) {
    val c = galleryContainer()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ids = remember { mutableStateListOf<Long>().apply { addAll(c.viewerIds.ifEmpty { listOf(initialId) }) } }
    val media by produceState(emptyMap<Long, Media>(), ids.size) { value = c.repo.mediaByIds(ids.toList()) }
    val pager = rememberPagerState(initialPage = ids.indexOf(initialId).coerceAtLeast(0)) { ids.size }
    val currentId = ids.getOrNull(pager.currentPage) ?: initialId
    val detail by remember(currentId) { c.repo.observeDetail(currentId) }.collectAsStateWithLifecycle(null)
    val albums by remember { c.repo.observeAlbums() }.collectAsStateWithLifecycle(emptyList())
    var chrome by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var detailsShown by remember { mutableStateOf(false) }
    var pageRequest by remember { mutableStateOf<PageRequest?>(null) }
    var albumPicker by remember { mutableStateOf(false) }
    var slideshow by remember { mutableStateOf(false) }
    val trash = rememberTrasher()
    val delete = rememberDeleter()

    fun removeCurrent() {
        val id = currentId
        ids.remove(id)
        if (ids.isEmpty()) nav.back()
    }

    fun requestDetails(open: Boolean) {
        pageRequest = PageRequest(currentId, open, (pageRequest?.serial ?: 0) + 1)
    }
    BackHandler(enabled = detailsShown) { requestDetails(open = false) }
    fun openKeyword(k: String) { nav.list(ListKind.KEYWORD, k) }

    LaunchedEffect(slideshow) {
        if (!slideshow) return@LaunchedEffect
        chrome = false
        while (slideshow && pager.currentPage < ids.lastIndex) {
            delay(3500)
            pager.animateScrollToPage(pager.currentPage + 1)
        }
        slideshow = false
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, key = { ids.getOrElse(it) { -1L } }, beyondViewportPageCount = 1, modifier = Modifier.fillMaxSize()) { page ->
            val m = media[ids.getOrNull(page)] ?: return@HorizontalPager
            MediaPage(
                m = m,
                active = page == pager.settledPage,
                isCurrent = page == pager.currentPage,
                request = pageRequest,
                onTap = { chrome = !chrome; slideshow = false },
                onDetailsShown = { detailsShown = it },
                onPerson = { nav.list(ListKind.PERSON, it.toString()) },
                onAlbum = { nav.list(ListKind.ALBUM, it.toString()) },
                onKeyword = ::openKeyword,
            )
        }

        AnimatedVisibility(chrome && !detailsShown, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                    .statusBarsPadding().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    val m = detail?.media
                    if (m != null) {
                        Text(DateLabels.day(m.takenAt), style = MaterialTheme.typography.titleMedium, color = Color.White)
                        Text(DateLabels.dateTime(m.takenAt), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = Color.White) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        val m = detail?.media
                        DropdownMenuItem(text = { Text("Add to album") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) }, onClick = { menu = false; albumPicker = true })
                        DropdownMenuItem(
                            text = { Text(if (m?.archived == true) "Unarchive" else "Archive") },
                            leadingIcon = { Icon(if (m?.archived == true) Icons.Rounded.Unarchive else Icons.Rounded.Archive, null) },
                            onClick = {
                                menu = false
                                if (m != null) scope.launch { c.repo.setArchived(listOf(m.id), !m.archived) }
                                ctx.toast(if (m?.archived == true) "Unarchived" else "Archived")
                            },
                        )
                        DropdownMenuItem(text = { Text("Move to Locked folder") }, leadingIcon = { Icon(Icons.Rounded.Lock, null) }, onClick = {
                            menu = false
                            val target = m ?: return@DropdownMenuItem
                            scope.launch {
                                val copied = c.locked.copyIn(listOf(target))
                                if (copied.isEmpty()) return@launch
                                delete(listOf(target.uri)) { ok ->
                                    scope.launch {
                                        if (ok) {
                                            c.repo.forget(listOf(target.id))
                                            removeCurrent()
                                        } else {
                                            c.locked.rollback(copied)
                                        }
                                    }
                                }
                            }
                        })
                        DropdownMenuItem(text = { Text("Slideshow") }, leadingIcon = { Icon(Icons.Rounded.Slideshow, null) }, onClick = { menu = false; slideshow = true })
                        DropdownMenuItem(text = { Text("Use as…") }, leadingIcon = { Icon(Icons.Rounded.Wallpaper, null) }, onClick = {
                            menu = false
                            m?.let { ctx.startSafely(MediaActions.setAsIntent(it.uri, it.mime ?: "image/*")) }
                        })
                        DropdownMenuItem(text = { Text("Open with…") }, leadingIcon = { Icon(Icons.Rounded.OpenInNew, null) }, onClick = {
                            menu = false
                            m?.let {
                                ctx.startSafely(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(it.uri, it.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                            }
                        })
                    }
                }
            }
        }

        AnimatedVisibility(chrome && !detailsShown, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                    .navigationBarsPadding(),
            ) {
            detail?.caption?.let { caption ->
                Text(
                    caption,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clickable { requestDetails(open = true) }.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                val m = detail?.media
                BarAction(Icons.Rounded.Share, "Share", {
                    m?.let { ctx.startSafely(MediaActions.shareIntent(listOf(it.uri), it.mime ?: "image/*")) }
                }, tint = Color.White)
                BarAction(Icons.Rounded.Edit, "Edit", {
                    m?.let { if (it.isVideo) nav.trim(it.id) else nav.edit(it.id) }
                }, tint = Color.White)
                BarAction(
                    if (m?.favorite == true) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    "Favourite",
                    { m?.let { scope.launch { c.repo.setFavorite(listOf(it.id), !it.favorite) } } },
                    tint = if (m?.favorite == true) DotTheme.extra.accent else Color.White,
                )
                BarAction(Icons.Rounded.Info, "Info", { requestDetails(open = true) }, tint = Color.White)
                BarAction(Icons.Rounded.Delete, "Delete", {
                    val target = m ?: return@BarAction
                    trash(listOf(target.uri)) { ok ->
                        if (ok) {
                            scope.launch { c.repo.forget(listOf(target.id)) }
                            removeCurrent()
                        }
                    }
                }, tint = Color.White)
            }
            }
        }
    }

    if (albumPicker) {
        AlbumPickerDialog(
            albums = albums,
            onDismiss = { albumPicker = false },
            onPick = { a -> albumPicker = false; scope.launch { c.repo.addToAlbum(a.id, listOf(currentId)) }; ctx.toast("Added to ${a.name}") },
            onCreate = { name -> albumPicker = false; scope.launch { c.repo.addToAlbum(c.repo.createAlbum(name), listOf(currentId)) } },
        )
    }

}

private data class PageRequest(val id: Long, val open: Boolean, val serial: Int)

/**
 * One photo or video, full screen, with its details below: swipe up for the date, file, size,
 * camera and place, then the AI description and keywords, people, albums and text in the photo.
 */
@Composable
private fun MediaPage(
    m: Media,
    active: Boolean,
    isCurrent: Boolean,
    request: PageRequest?,
    onTap: () -> Unit,
    onDetailsShown: (Boolean) -> Unit,
    onPerson: (Long) -> Unit,
    onAlbum: (Long) -> Unit,
    onKeyword: (String) -> Unit,
) {
    val c = galleryContainer()
    val detail by remember(m.id) { c.repo.observeDetail(m.id) }.collectAsStateWithLifecycle(null)
    val scroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = maxHeight
        val viewportPx = with(LocalDensity.current) { viewport.toPx() }
        LaunchedEffect(request) {
            val r = request ?: return@LaunchedEffect
            if (r.id == m.id) scroll.animateScrollTo(if (r.open) (viewportPx * 0.55f).toInt() else 0)
        }
        if (isCurrent) {
            LaunchedEffect(scroll, viewportPx) {
                snapshotFlow { scroll.value > viewportPx * 0.12f }.distinctUntilChanged().collect { onDetailsShown(it) }
            }
        }
        // Back on the picture when swiped away.
        LaunchedEffect(isCurrent) { if (!isCurrent && scroll.value > 0) scroll.scrollTo(0) }
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Box(Modifier.fillMaxWidth().height(viewport)) {
                if (m.isVideo) {
                    VideoPage(m.uri, active = active, onTap = onTap)
                } else {
                    ZoomableAsyncImage(model = m.uri, contentDescription = m.name, modifier = Modifier.fillMaxSize(), onClick = { onTap() })
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = viewport * 0.6f),
            ) {
                val d = detail
                if (d != null) {
                    InfoSheet(d, onPerson = onPerson, onAlbum = onAlbum, onKeyword = onKeyword)
                } else {
                    Spacer(Modifier.fillMaxWidth().height(160.dp))
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPage(uri: Uri, active: Boolean, onTap: () -> Unit) {
    if (!active) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(56.dp))
        }
        return
    }
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                this.player = player
                setShowNextButton(false)
                setShowPreviousButton(false)
                setOnClickListener { onTap() }
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InfoSheet(d: MediaDetail, onPerson: (Long) -> Unit, onAlbum: (Long) -> Unit, onKeyword: (String) -> Unit) {
    val ctx = LocalContext.current
    val m = d.media
    val photoInfo by produceState(PhotoInfo(), m.id) { if (!m.isVideo) value = MediaInfo.read(ctx, m.uri) }
    val divider = @Composable { HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant) }
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 24.dp)) {
        Box(
            Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
        )
        Spacer(Modifier.height(16.dp))
        Text(DateLabels.dateTime(m.takenAt), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        InfoRow("File", m.name)
        d.path?.let { InfoRow("Folder", it.trimEnd('/')) }
        InfoRow("Size", "${m.width} × ${m.height} · ${formatBytes(m.sizeBytes)}" + if (m.isVideo) " · ${formatDuration(m.durationMs)}" else "")
        photoInfo.camera?.let { InfoRow("Camera", it) }
        listOfNotNull(photoInfo.aperture, photoInfo.exposure, photoInfo.iso, photoInfo.focalLength).takeIf { it.isNotEmpty() }?.let {
            InfoRow("Exposure", it.joinToString(" · "))
        }
        photoInfo.lens?.let { InfoRow("Lens", it) }
        val lat = photoInfo.latitude
        val lon = photoInfo.longitude
        if (lat != null && lon != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InfoRow("Location", "%.5f, %.5f".format(lat, lon), Modifier.weight(1f))
                TextButton(onClick = { ctx.startSafely(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon"))) }) {
                    Icon(Icons.Rounded.Map, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Map")
                }
            }
        }
        if (d.caption != null || m.keywords.isNotEmpty()) {
            divider()
            Text("DESCRIPTION", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            d.caption?.let { caption ->
                Spacer(Modifier.height(6.dp))
                Text(caption, style = MaterialTheme.typography.bodyLarge)
            }
            if (m.keywords.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                KeywordChips(m.keywords, onClick = onKeyword)
            }
        } else if (!m.isVideo) {
            divider()
            val c = galleryContainer()
            var asked by remember(m.id) { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (asked) "Describing… a few seconds" else "Not described yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (!asked) {
                    TextButton(onClick = { asked = true; c.describeNow(m.id) }) { Text("Describe now", color = DotTheme.extra.accent) }
                }
            }
        }
        if (d.people.isNotEmpty()) {
            divider()
            Text("PEOPLE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                d.people.forEach { p -> PersonBubble(p, size = 56) { onPerson(p.id) } }
            }
        }
        if (d.albums.isNotEmpty()) {
            divider()
            Text("ALBUMS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                d.albums.forEach { a -> DotChip(a.name, onClick = { onAlbum(a.id) }, selected = true) }
            }
        }
        if (d.text.isNotBlank()) {
            divider()
            Text("TEXT IN PHOTO", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            SelectionContainer { Text(d.text, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 6.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Opened from another app ("Open with Dot Gallery"): just this one item. */
@Composable
fun ExternalViewerScreen(uri: Uri, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val mime = remember(uri) { ctx.contentResolver.getType(uri) ?: "" }
    var chrome by remember { mutableStateOf(true) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (mime.startsWith("video/")) {
            VideoPage(uri, active = true, onTap = { chrome = !chrome })
        } else {
            ZoomableAsyncImage(model = uri, contentDescription = null, modifier = Modifier.fillMaxSize(), onClick = { chrome = !chrome })
        }
        AnimatedVisibility(chrome, modifier = Modifier.align(Alignment.TopStart)) {
            Row(Modifier.statusBarsPadding().padding(4.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
            }
        }
        AnimatedVisibility(chrome, modifier = Modifier.align(Alignment.BottomCenter)) {
            Row(Modifier.navigationBarsPadding().padding(8.dp)) {
                BarAction(Icons.Rounded.Share, "Share", { ctx.startSafely(MediaActions.shareIntent(listOf(uri), mime.ifEmpty { "image/*" })) }, tint = Color.White)
            }
        }
    }
}
