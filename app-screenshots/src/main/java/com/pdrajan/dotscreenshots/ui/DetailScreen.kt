package com.pdrajan.dotscreenshots.ui

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pdrajan.dot.design.DateLabels
import com.pdrajan.dot.design.DotChip
import com.pdrajan.dot.design.DotOutlinedButton
import com.pdrajan.dot.design.DotTag
import com.pdrajan.dot.design.DotTheme
import com.pdrajan.dot.design.MediaThumbnail
import com.pdrajan.dot.engine.Categories
import com.pdrajan.dot.media.MediaActions
import com.pdrajan.dotscreenshots.AppContainer
import com.pdrajan.dotscreenshots.data.IndexState
import com.pdrajan.dotscreenshots.data.Shot
import com.pdrajan.dotscreenshots.data.ShotDetail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage

private fun shotUri(id: Long): Uri =
    ContentUris.withAppendedId(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        id,
    )

@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModel(private val c: AppContainer, private val initialId: Long, ctx: String) : ViewModel() {

    private fun idsOf(flow: Flow<List<Shot>>) = flow.map { list -> list.map { it.id } }

    /** Null until loaded, so the pager can start on the right page. */
    val ids: StateFlow<List<Long>?> = when {
        ctx == ShotContext.ALL -> idsOf(c.repo.observeShots())
        ctx == ShotContext.FAVORITES -> idsOf(c.repo.observeFavorites())
        ctx == ShotContext.SEARCH -> flowOf(c.lastSearchIds)
        ctx.startsWith("cat:") -> idsOf(c.repo.observeCategory(ctx.removePrefix("cat:")))
        ctx.startsWith("col:") -> idsOf(c.repo.observeCollectionShots(ctx.removePrefix("col:").toLongOrNull() ?: -1))
        else -> flowOf(listOf(initialId))
    }.map { list -> if (initialId in list || list.isEmpty()) list.ifEmpty { listOf(initialId) } else list }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val current = MutableStateFlow(initialId)
    val detail: StateFlow<ShotDetail?> =
        current.flatMapLatest { c.repo.observeDetail(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val similar: StateFlow<List<Shot>> = current.mapLatest { id ->
        val ids = c.repo.similar(id)
        val byId = c.repo.shotsByIds(ids)
        ids.mapNotNull { byId[it] }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val collections = c.repo.observeCollections().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun select(id: Long) {
        current.value = id
    }

    fun toggleFavorite() = viewModelScope.launch { c.repo.toggleFavorite(current.value) }
    fun saveNote(id: Long, note: String) = viewModelScope.launch { c.repo.setNote(id, note) }
    fun addTo(collectionId: Long) = viewModelScope.launch { c.repo.addToCollection(collectionId, listOf(current.value)) }
    fun createCollection(name: String) = viewModelScope.launch {
        val id = c.repo.createCollection(name)
        c.repo.addToCollection(id, listOf(current.value))
    }

    fun removeFrom(collectionId: Long) = viewModelScope.launch { c.repo.removeFromCollection(collectionId, listOf(current.value)) }

    fun forget(id: Long) = viewModelScope.launch { c.repo.forget(listOf(id)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    initialId: Long,
    context: String,
    onBack: () -> Unit,
    onOpenShot: (Long) -> Unit,
    onOpenCollection: (Long) -> Unit,
) {
    val vm = containerViewModel(key = "detail-$initialId-$context") { DetailViewModel(it, initialId, context) }
    val ids by vm.ids.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    var chrome by remember { mutableStateOf(true) }
    var showSheet by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val deleter = rememberDeleteLauncher()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val list = ids
        if (list != null && list.isNotEmpty()) {
            key(list.isNotEmpty()) {
                val pager = rememberPagerState(initialPage = list.indexOf(initialId).coerceAtLeast(0)) { list.size }
                LaunchedEffect(pager.currentPage, list) {
                    list.getOrNull(pager.currentPage)?.let(vm::select)
                }
                LaunchedEffect(list) {
                    val idx = list.indexOf(vm.current.value)
                    if (idx >= 0 && idx != pager.currentPage) pager.scrollToPage(idx)
                }
                HorizontalPager(
                    state = pager,
                    key = { list.getOrElse(it) { -1L } },
                    beyondViewportPageCount = 1,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    val id = list.getOrNull(page) ?: return@HorizontalPager
                    ZoomableAsyncImage(
                        model = shotUri(id),
                        contentDescription = "Screenshot",
                        modifier = Modifier.fillMaxSize(),
                        onClick = { chrome = !chrome },
                    )
                }
            }
        }

        AnimatedVisibility(visible = chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    val d = detail
                    if (d != null) {
                        Text(d.shot.app ?: "Screenshot", style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1)
                        Text(DateLabels.dateTime(d.shot.takenAt), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = Color.White) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Edit in…") },
                            leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                            onClick = { menu = false; detail?.let { ctx.startSafely(MediaActions.editIntent(it.shot.uri)) } },
                        )
                        DropdownMenuItem(
                            text = { Text("Set as…") },
                            leadingIcon = { Icon(Icons.Rounded.Wallpaper, null) },
                            onClick = { menu = false; detail?.let { ctx.startSafely(MediaActions.setAsIntent(it.shot.uri)) } },
                        )
                    }
                }
            }
        }

        AnimatedVisibility(visible = chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                    .navigationBarsPadding(),
            ) {
                val actions = detail?.entities?.flatMap { entityActions(it) }.orEmpty()
                if (actions.isNotEmpty()) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(actions) { a ->
                            Surface(
                                onClick = { a.run(ctx) },
                                shape = CircleShape,
                                color = Color.White.copy(alpha = 0.14f),
                                contentColor = Color.White,
                            ) {
                                Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(a.icon, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(a.label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    val d = detail
                    ViewerAction(Icons.Rounded.Share, "Share") { d?.let { ctx.startSafely(MediaActions.shareIntent(listOf(it.shot.uri))) } }
                    ViewerAction(
                        if (d?.shot?.favorite == true) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        "Favourite",
                        tint = if (d?.shot?.favorite == true) DotTheme.extra.accent else Color.White,
                    ) { vm.toggleFavorite() }
                    ViewerAction(Icons.Rounded.Info, "Details") { showSheet = true }
                    ViewerAction(Icons.Rounded.Delete, "Delete") {
                        val shot = d?.shot ?: return@ViewerAction
                        deleter.delete(listOf(shot.uri)) { ok ->
                            if (ok) {
                                vm.forget(shot.id)
                                if ((ids?.size ?: 0) <= 1) onBack()
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSheet) {
        val d = detail
        if (d != null) {
            ModalBottomSheet(
                onDismissRequest = { showSheet = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                DetailSheet(d, vm, onOpenShot = { showSheet = false; onOpenShot(it) }, onOpenCollection = { showSheet = false; onOpenCollection(it) })
            }
        }
    }
}

@Composable
private fun ViewerAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Column(
        Modifier.clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = tint)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun DetailSheet(d: ShotDetail, vm: DetailViewModel, onOpenShot: (Long) -> Unit, onOpenCollection: (Long) -> Unit) {
    val ctx = LocalContext.current
    val collections by vm.collections.collectAsStateWithLifecycle()
    val similar by vm.similar.collectAsStateWithLifecycle()
    var note by remember(d.shot.id) { mutableStateOf(d.note) }
    var picker by remember { mutableStateOf(false) }

    LaunchedEffect(note) {
        if (note == d.note) return@LaunchedEffect
        delay(700)
        vm.saveNote(d.shot.id, note)
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            val title = d.shot.title
            Text(title ?: d.shot.app ?: "Screenshot", style = MaterialTheme.typography.headlineSmall)
            Text(
                listOfNotNull(d.shot.app.takeIf { title != null }, DateLabels.dateTime(d.shot.takenAt)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            d.summary?.let { summary ->
                Spacer(Modifier.height(10.dp))
                Text(summary, style = MaterialTheme.typography.bodyMedium)
            }
            d.pageUrl?.let { url ->
                Spacer(Modifier.height(12.dp))
                val host = runCatching { Uri.parse(url).host?.removePrefix("www.") }.getOrNull() ?: "page"
                DotOutlinedButton("Open $host", onClick = { ctx.startSafely(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }, icon = Icons.AutoMirrored.Rounded.OpenInNew)
            }
            if (d.tags.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    d.tags.take(10).forEach { DotTag(it) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        val actions = d.entities.flatMap { entityActions(it) }
        if (actions.isNotEmpty()) {
            SheetSection("Quick actions") {
                Column {
                    actions.forEach { a ->
                        Row(
                            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { a.run(ctx) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(a.icon, null, tint = DotTheme.extra.accent)
                            Spacer(Modifier.width(14.dp))
                            Text(a.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }

        SheetSection("Note") {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                placeholder = { Text("Add a note — it's searchable") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = DotTheme.extra.accent, cursorColor = DotTheme.extra.accent),
            )
        }

        SheetSection("Collections") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                d.collections.forEach { c -> DotChip(c.name, onClick = { onOpenCollection(c.id) }, selected = true) }
                DotChip("Add", onClick = { picker = true }, icon = Icons.Rounded.Add)
            }
        }

        SheetSection("Text in screenshot") {
            when {
                d.text.isNotBlank() -> Column {
                    SelectionContainer {
                        Text(d.text, style = MaterialTheme.typography.bodyMedium)
                    }
                    TextButton(onClick = { ctx.copy("Screenshot text", d.text) }) {
                        Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Copy all text")
                    }
                }
                d.shot.state == IndexState.PENDING -> Text("Not read yet — it'll be processed soon.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Text("No text found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (similar.isNotEmpty()) {
            SheetSection("Similar") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(similar, key = { it.id }) { s ->
                        MediaThumbnail(
                            model = s.uri,
                            aspectRatio = 9f / 16f,
                            cornerRadius = 10.dp,
                            onClick = { onOpenShot(s.id) },
                            modifier = Modifier.width(84.dp),
                        )
                    }
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        SheetSection("Details") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(d.shot.name, style = MaterialTheme.typography.bodyMedium)
                Text("${d.shot.width} × ${d.shot.height} · ${formatBytes(d.shot.sizeBytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (d.shot.categories.isNotEmpty()) {
                    Text(
                        d.shot.categories.joinToString(" · ") { Categories.byId(it)?.label ?: it },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (picker) {
        CollectionPickerDialog(
            collections = collections,
            onDismiss = { picker = false },
            onPick = { vm.addTo(it.id); picker = false },
            onCreate = { vm.createCollection(it); picker = false },
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}
