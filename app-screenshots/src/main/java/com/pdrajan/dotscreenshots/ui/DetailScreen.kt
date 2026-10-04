package com.pdrajan.dotscreenshots.ui

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pdrajan.dot.design.DateLabels
import com.pdrajan.dot.design.DotChip
import com.pdrajan.dot.design.DotOutlinedButton
import com.pdrajan.dot.design.KeywordChips
import com.pdrajan.dot.design.DotTheme
import com.pdrajan.dot.engine.Categories
import com.pdrajan.dot.media.MediaActions
import com.pdrajan.dotscreenshots.AppContainer
import com.pdrajan.dotscreenshots.data.IndexState
import com.pdrajan.dotscreenshots.data.Shot
import com.pdrajan.dotscreenshots.data.ShotDetail
import com.pdrajan.dot.engine.AppNames
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.ImageVector
import com.pdrajan.dot.design.Emphasized
import com.pdrajan.dot.design.EmphasizedAccelerate
import com.pdrajan.dot.design.EmphasizedDecelerate
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import com.pdrajan.dot.design.MediaThumbs
import androidx.compose.animation.core.tween
import com.pdrajan.dot.design.glass
import com.pdrajan.dot.design.sharedImage
import coil3.request.ImageRequest
import com.pdrajan.dot.design.glassSource
import com.pdrajan.dot.design.rememberGlass
import com.pdrajan.dot.media.FileDetails
import com.pdrajan.dot.media.MediaInfo

private fun shotUri(id: Long): Uri =
    ContentUris.withAppendedId(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        id,
    )

@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModel(private val c: AppContainer, private val initialId: Long, ctx: String) : ViewModel() {

    private fun idsOf(flow: Flow<List<Shot>>) = flow.map { list -> list.map { it.id } }

    /**
     * The screenshots to swipe through. Known from the first frame (the grid's order, or the
     * search results), so the picture can grow out of its thumbnail right away.
     */
    val ids: StateFlow<List<Long>> = when {
        ctx == ShotContext.ALL -> idsOf(c.repo.observeShots())
        ctx == ShotContext.FAVORITES -> idsOf(c.repo.observeFavorites())
        ctx == ShotContext.SEARCH -> flowOf(c.lastSearchIds)
        ctx.startsWith("cat:") -> idsOf(c.repo.observeCategory(ctx.removePrefix("cat:")))
        ctx.startsWith("col:") -> idsOf(c.repo.observeCollectionShots(ctx.removePrefix("col:").toLongOrNull() ?: -1))
        else -> flowOf(listOf(initialId))
    }.map { list -> if (initialId in list || list.isEmpty()) list.ifEmpty { listOf(initialId) } else list }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            (if (ctx == ShotContext.SEARCH) c.lastSearchIds else c.viewerOrder).takeIf { initialId in it } ?: listOf(initialId),
        )

    val current = MutableStateFlow(initialId)
    val detail: StateFlow<ShotDetail?> =
        current.flatMapLatest { c.repo.observeDetail(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val collections = c.repo.observeCollections().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Settings → "Show descriptions and keywords" (off by default). */
    val showDescriptions: StateFlow<Boolean> = c.settings.showDescriptions

    fun select(id: Long) {
        current.value = id
    }

    /** Each page shows its own screenshot's details. */
    fun detailOf(id: Long): Flow<ShotDetail?> = c.repo.observeDetail(id)

    fun toggleFavorite() = viewModelScope.launch { c.repo.toggleFavorite(current.value) }
    fun saveNote(id: Long, note: String) = viewModelScope.launch { c.repo.setNote(id, note) }
    fun addTo(collectionId: Long) = viewModelScope.launch { c.repo.addToCollection(collectionId, listOf(current.value)) }
    fun createCollection(name: String) = viewModelScope.launch {
        val id = c.repo.createCollection(name)
        c.repo.addToCollection(id, listOf(current.value))
    }

    fun forget(id: Long) = viewModelScope.launch { c.repo.forget(listOf(id)) }

    suspend fun appChoices(): List<AppNames.Choice> = c.appChoices()

    /** The user says which app it's from. */
    fun setApp(id: Long, choice: AppNames.Choice) = c.setApp(id, choice.label, choice.packageName)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    initialId: Long,
    context: String,
    onBack: () -> Unit,
    onOpenCollection: (Long) -> Unit,
    onSearch: (String) -> Unit,
    onEdit: (Long) -> Unit,
) {
    val vm = containerViewModel(key = "detail-$initialId-$context") { DetailViewModel(it, initialId, context) }
    val ids by vm.ids.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    var chrome by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    // Swiping up on a screenshot scrolls its details into view; the buttons ask the current page to.
    var detailsShown by remember { mutableStateOf(false) }
    var pageRequest by remember { mutableStateOf<PageRequest?>(null) }
    fun requestDetails(open: Boolean) {
        pageRequest = PageRequest(vm.current.value, open, (pageRequest?.serial ?: 0) + 1)
    }
    BackHandler(enabled = detailsShown) { requestDetails(open = false) }
    val ctx = LocalContext.current
    val deleter = rememberDeleteLauncher()
    // The screenshot (and its details) blur behind the top and bottom controls so they stay readable.
    val glass = rememberGlass()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val list = ids
        if (list.isNotEmpty()) {
            run {
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
                    modifier = Modifier.fillMaxSize().glassSource(glass),
                ) { page ->
                    val id = list.getOrNull(page) ?: return@HorizontalPager
                    ShotPage(
                        id = id,
                        vm = vm,
                        isCurrent = page == pager.currentPage,
                        request = pageRequest,
                        onToggleChrome = { chrome = !chrome },
                        onDetailsShown = { detailsShown = it },
                        onOpenCollection = onOpenCollection,
                        onSearch = onSearch,
                    )
                }
            }
        }

        // The bars glide a little as they fade, the way Photos does when you tap the picture.
        AnimatedVisibility(
            visible = chrome,
            enter = fadeIn(tween(220)) + slideInVertically(tween(260, easing = EmphasizedDecelerate)) { -it / 3 },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(200, easing = EmphasizedAccelerate)) { -it / 3 },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .glass(glass, Color.Black, tintAlpha = if (detailsShown) 0.72f else 0.4f)
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
                            text = { Text("Edit in another app") },
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

        AnimatedVisibility(
            visible = chrome && !detailsShown,
            enter = fadeIn(tween(220)) + slideInVertically(tween(260, easing = EmphasizedDecelerate)) { it / 3 },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(200, easing = EmphasizedAccelerate)) { it / 3 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .glass(glass, Color.Black, tintAlpha = 0.45f)
                    .navigationBarsPadding(),
            ) {
                Spacer(Modifier.height(8.dp))
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
                    ViewerAction(Icons.Rounded.Edit, "Edit") { d?.let { onEdit(it.shot.id) } }
                    ViewerAction(Icons.Rounded.Info, "Details") { requestDetails(open = true) }
                    ViewerAction(Icons.Rounded.Delete, "Delete") {
                        val shot = d?.shot ?: return@ViewerAction
                        deleter.delete(listOf(shot.uri)) { ok ->
                            if (ok) {
                                vm.forget(shot.id)
                                if (ids.size <= 1) onBack()
                            }
                        }
                    }
                }
            }
        }
    }

}

/** Scroll page [id] to its details ([open]) or back to the picture; [serial] makes each tap a new request. */
private data class PageRequest(val id: Long, val open: Boolean, val serial: Int)

/**
 * One screenshot, full screen, with its details below it (the Details button, or scrolling up,
 * brings them in). Pinch and double-tap zoom the picture.
 */
@Composable
private fun ShotPage(
    id: Long,
    vm: DetailViewModel,
    isCurrent: Boolean,
    request: PageRequest?,
    onToggleChrome: () -> Unit,
    onDetailsShown: (Boolean) -> Unit,
    onOpenCollection: (Long) -> Unit,
    onSearch: (String) -> Unit,
) {
    val detail by remember(id) { vm.detailOf(id) }.collectAsStateWithLifecycle(null)
    val scroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = maxHeight
        val viewportPx = with(LocalDensity.current) { viewport.toPx() }
        // Buttons: bring the details up to just below the middle of the screen, or go back to the picture.
        LaunchedEffect(request) {
            val r = request ?: return@LaunchedEffect
            if (r.id == id) scroll.animateScrollTo(if (r.open) (viewportPx * 0.55f).toInt() else 0, tween(450, easing = Emphasized))
        }
        if (isCurrent) {
            LaunchedEffect(scroll, viewportPx) {
                snapshotFlow { scroll.value > viewportPx * 0.12f }.distinctUntilChanged().collect { onDetailsShown(it) }
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            // The current page's picture flies from and back into its grid thumbnail.
            Box(Modifier.fillMaxWidth().height(viewport).sharedImage(shotKey(id), enabled = isCurrent)) {
                val context = LocalContext.current
                ZoomableAsyncImage(
                    // The grid's thumbnail shows at once while the full picture loads.
                    model = remember(id) {
                        ImageRequest.Builder(context).data(shotUri(id)).placeholderMemoryCacheKey(MediaThumbs.cacheKey(id)).build()
                    },
                    contentDescription = "Screenshot",
                    modifier = Modifier.fillMaxSize(),
                    onClick = { onToggleChrome() },
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = viewport * 0.6f),
            ) {
                val d = detail
                if (d != null) {
                    DetailSheet(d, vm, onOpenCollection = onOpenCollection, onSearch = onSearch)
                } else {
                    Spacer(Modifier.fillMaxWidth().height(160.dp))
                }
            }
        }
    }
}

@Composable
private fun ViewerAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Column(
        Modifier.clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = tint)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun DetailSheet(d: ShotDetail, vm: DetailViewModel, onOpenCollection: (Long) -> Unit, onSearch: (String) -> Unit) {
    val ctx = LocalContext.current
    val collections by vm.collections.collectAsStateWithLifecycle()
    val showDescriptions by vm.showDescriptions.collectAsStateWithLifecycle()
    var note by remember(d.shot.id) { mutableStateOf(d.note) }
    var picker by remember { mutableStateOf(false) }
    var appPicker by remember { mutableStateOf(false) }

    LaunchedEffect(note) {
        if (note == d.note) return@LaunchedEffect
        delay(700)
        vm.saveNote(d.shot.id, note)
    }

    if (appPicker) {
        AppPickerDialog(
            current = d.shot.app.takeIf { !d.appGuessed },
            loadChoices = vm::appChoices,
            onPick = { vm.setApp(d.shot.id, it); appPicker = false },
            onDismiss = { appPicker = false },
        )
    }

    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 12.dp, bottom = 24.dp)) {
        Box(
            Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
        )
        Spacer(Modifier.height(16.dp))
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(d.shot.app ?: "Screenshot", style = MaterialTheme.typography.headlineSmall)
            Text(
                DateLabels.dateTime(d.shot.takenAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AppLine(d, onClick = { appPicker = true })
            d.pageUrl?.let { url ->
                Spacer(Modifier.height(12.dp))
                val host = runCatching { Uri.parse(url).host?.removePrefix("www.") }.getOrNull() ?: "page"
                DotOutlinedButton("Open $host", onClick = { ctx.startSafely(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }, icon = Icons.AutoMirrored.Rounded.OpenInNew)
            }
        }
        Spacer(Modifier.height(8.dp))

        // The description and keywords are for search; shown only if Settings asks for them.
        if (showDescriptions) {
            SheetSection("Description") {
                when {
                    d.description != null -> Text(d.description, style = MaterialTheme.typography.bodyLarge)
                    d.shot.state == IndexState.FAILED -> Text("Couldn't be read.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> Text("Not described yet — it'll be done soon.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        SheetSection("Details") { FileDetailsList(d) }

        // Keywords stay tucked away until asked for.
        var showKeywords by rememberSaveable(d.shot.id) { mutableStateOf(false) }
        if (showDescriptions) Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
            DotOutlinedButton(
                if (showKeywords) "Hide keywords" else "Show keywords",
                onClick = { showKeywords = !showKeywords },
                icon = Icons.Rounded.Tag,
            )
            AnimatedVisibility(showKeywords) {
                Column(Modifier.padding(top = 12.dp)) {
                    when {
                        d.keywords.isNotEmpty() -> KeywordChips(d.keywords, onClick = onSearch)
                        d.shot.state == IndexState.PENDING -> Text(
                            "Not read yet — keywords come after it's processed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> Text(
                            "No keywords for this one; search finds it by its description and the words on screen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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

        // The words read from the screen: hidden until asked for, shown as one running line.
        var showText by rememberSaveable(d.shot.id) { mutableStateOf(false) }
        val screenText = remember(d.text) { d.text.replace(Regex("\\s*\\n\\s*"), " ").trim() }
        SheetSection("Text in screenshot") {
            Column {
                DotOutlinedButton(
                    if (showText) "Hide text" else "Show text",
                    onClick = { showText = !showText },
                    icon = Icons.Rounded.TextFields,
                )
                AnimatedVisibility(showText) {
                    Column(Modifier.padding(top = 12.dp)) {
                        when {
                            screenText.isNotEmpty() -> {
                                SelectionContainer {
                                    Text(screenText, style = MaterialTheme.typography.bodyMedium)
                                }
                                TextButton(onClick = { ctx.copy("Screenshot text", screenText) }) {
                                    Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Copy all text")
                                }
                            }
                            d.shot.state == IndexState.PENDING -> Text("Not read yet — it'll be processed soon.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else -> Text("No text found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
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

/** When it was taken, how big it is, where the file lives and (if the file records it) where it was taken. */
@Composable
private fun FileDetailsList(d: ShotDetail) {
    val ctx = LocalContext.current
    var locationAllowed by remember { mutableStateOf(MediaInfo.hasLocationAccess(ctx)) }
    val allowLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { locationAllowed = it }
    val info by produceState<FileDetails?>(null, d.shot.id, locationAllowed) { value = MediaInfo.read(ctx, d.shot.uri) }
    val shot = d.shot
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        InfoRow(Icons.Rounded.CalendarToday, "Taken", DateLabels.dateTime(shot.takenAt))
        val megapixels = shot.width.toLong() * shot.height / 1_000_000.0
        val format = info?.mimeType?.substringAfter('/')?.uppercase()?.let { "$it · " }.orEmpty()
        InfoRow(
            Icons.Rounded.Image,
            "Size",
            "$format${formatBytes(shot.sizeBytes)} · ${shot.width} × ${shot.height}" + if (megapixels >= 0.1) " · ${"%.1f".format(megapixels)} MP" else "",
        )
        InfoRow(Icons.Rounded.Folder, "File", shot.name + (info?.folder?.let { "\n$it" } ?: ""))
        val lat = info?.latitude
        val lon = info?.longitude
        when {
            lat != null && lon != null -> InfoRow(
                Icons.Rounded.LocationOn, "Location", "%.5f, %.5f".format(lat, lon), action = "Map",
                onAction = { ctx.startSafely(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon"))) },
            )
            !locationAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> InfoRow(
                Icons.Rounded.LocationOn, "Location", "Allow access to photo locations to see it", action = "Allow",
                onAction = { allowLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) },
            )
            info != null -> InfoRow(Icons.Rounded.LocationOff, "Location", "Not saved in this screenshot")
        }
        if (shot.categories.isNotEmpty()) {
            InfoRow(Icons.Rounded.Category, "Categories", shot.categories.joinToString(" · ") { Categories.byId(it)?.label ?: it })
        }
        InfoRow(
            Icons.AutoMirrored.Rounded.ManageSearch,
            "Search",
            when (shot.state) {
                IndexState.INDEXED -> if (d.text.isBlank()) "Searchable" else "Searchable · ${d.text.split(Regex("\\s+")).count { it.isNotBlank() }} words read"
                IndexState.PENDING -> "Waiting to be read"
                IndexState.FAILED -> "Couldn't be read"
            },
        )
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        if (action != null) TextButton(onClick = onAction) { Text(action, color = DotTheme.extra.accent) }
    }
}

/** Which app the screenshot is from (the file name, the user, or the screen's own words); tap to correct it. */
@Composable
private fun AppLine(d: ShotDetail, onClick: () -> Unit) {
    val app = d.shot.app
    val text = when {
        app == null -> "Which app is this from?"
        d.appSource == "visual" -> "$app · from the screen's words"
        else -> app
    }
    Row(
        Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Apps, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(if (app == null) "Set app" else "Change", style = MaterialTheme.typography.labelMedium, color = DotTheme.extra.accent)
    }
}

@Composable
private fun AppPickerDialog(
    current: String?,
    loadChoices: suspend () -> List<AppNames.Choice>,
    onPick: (AppNames.Choice) -> Unit,
    onDismiss: () -> Unit,
) {
    var choices by remember { mutableStateOf<List<AppNames.Choice>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { choices = loadChoices() }
    val q = query.trim()
    val shown = choices.orEmpty().filter { q.isEmpty() || it.label.contains(q, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which app is this from?") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search apps") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = DotTheme.extra.accent, cursorColor = DotTheme.extra.accent),
                )
                Spacer(Modifier.height(4.dp))
                if (choices == null) {
                    Text("Loading apps…", modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    if (q.isNotEmpty() && shown.none { it.label.equals(q, ignoreCase = true) }) {
                        item {
                            Text(
                                "Use “$q”",
                                color = DotTheme.extra.accent,
                                modifier = Modifier.fillMaxWidth().clickable { onPick(AppNames.Choice(q, null)) }.padding(vertical = 12.dp),
                            )
                        }
                    }
                    items(shown, key = { it.label }) { choice ->
                        Text(
                            choice.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (choice.label == current) DotTheme.extra.accent else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(choice) }.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
