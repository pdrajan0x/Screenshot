package com.pdrajan.dotscreenshots.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.runtime.rememberUpdatedState
import com.pdrajan.dot.design.dragToSelect
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pdrajan.dot.design.DotChip
import com.pdrajan.dot.design.DotEmptyState
import com.pdrajan.dot.design.DotLargeTitle
import com.pdrajan.dot.design.DotSearchPill
import com.pdrajan.dot.design.DotTheme
import com.pdrajan.dot.design.MediaThumbnail
import com.pdrajan.dot.design.SectionLabel
import com.pdrajan.dot.design.glassSource
import com.pdrajan.dot.design.rememberGlass
import com.pdrajan.dot.design.pinchToChangeColumns
import com.pdrajan.dot.engine.Categories
import com.pdrajan.dot.media.MediaActions
import com.pdrajan.dotscreenshots.AppContainer
import com.pdrajan.dotscreenshots.data.Shot
import com.pdrajan.dotscreenshots.data.ShotCollection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val started = SharingStarted.WhileSubscribed(5_000)

    val shots: StateFlow<List<Shot>> = c.repo.observeShots().stateIn(viewModelScope, started, emptyList())
    val days: StateFlow<List<Pair<String, List<Shot>>>> =
        shots.map { groupByDay(it) }.flowOn(Dispatchers.Default).stateIn(viewModelScope, started, emptyList())
    val collections = c.repo.observeCollections().stateIn(viewModelScope, started, emptyList())
    val categoryCounts = c.repo.observeCategoryCounts().stateIn(viewModelScope, started, emptyMap())
    val favoriteCount = c.repo.observeFavorites().map { it.size }.stateIn(viewModelScope, started, 0)
    val columns: StateFlow<Int> = c.settings.gridColumns

    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection.asStateFlow()

    /** "Select" was tapped: taps select even before anything is selected. */
    private val _selecting = MutableStateFlow(false)
    val selecting: StateFlow<Boolean> = _selecting.asStateFlow()

    fun toggle(id: Long) {
        setSelection(_selection.value.let { if (id in it) it - id else it + id })
    }

    fun setSelection(ids: Set<Long>) {
        _selection.value = ids
        if (ids.isEmpty()) _selecting.value = false
    }

    fun startSelecting() {
        _selecting.value = true
    }

    fun clearSelection() {
        _selection.value = emptySet()
        _selecting.value = false
    }

    fun selectAll() {
        _selection.value = shots.value.map { it.id }.toSet()
    }

    fun selectedUris(): List<Uri> {
        val sel = _selection.value
        return shots.value.filter { it.id in sel }.map { it.uri }
    }

    fun addSelectionTo(collectionId: Long) {
        val ids = _selection.value
        viewModelScope.launch { c.repo.addToCollection(collectionId, ids) }
        clearSelection()
    }

    fun createCollectionWithSelection(name: String) {
        val ids = _selection.value
        viewModelScope.launch {
            val id = c.repo.createCollection(name)
            c.repo.addToCollection(id, ids)
        }
        clearSelection()
    }

    fun forgetSelection() {
        val ids = _selection.value
        viewModelScope.launch { c.repo.forget(ids) }
        clearSelection()
    }

    fun setColumns(n: Int) = c.settings.setGridColumns(n)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenShot: (Long) -> Unit,
    onSearch: () -> Unit,
    onOpenCollection: (Long) -> Unit,
    onOpenCategory: (String) -> Unit,
    onOpenFavorites: () -> Unit,
    onSettings: () -> Unit,
) {
    val vm = containerViewModel { HomeViewModel(it) }
    val context = LocalContext.current
    val days by vm.days.collectAsStateWithLifecycle()
    val shots by vm.shots.collectAsStateWithLifecycle()
    val collections by vm.collections.collectAsStateWithLifecycle()
    val categoryCounts by vm.categoryCounts.collectAsStateWithLifecycle()
    val favoriteCount by vm.favoriteCount.collectAsStateWithLifecycle()
    val columns by vm.columns.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val selecting by vm.selecting.collectAsStateWithLifecycle()
    val selectionMode = selecting || selection.isNotEmpty()
    val gridState = rememberLazyGridState()
    val currentSelection by rememberUpdatedState(selection)
    val orderedIds = remember(shots) { shots.map { it.id } }
    val currentIds by rememberUpdatedState(orderedIds)
    var showPicker by remember { mutableStateOf(false) }
    val deleter = rememberDeleteLauncher()

    BackHandler(enabled = selectionMode) { vm.clearSelection() }

    if (showPicker) {
        CollectionPickerDialog(
            collections = collections,
            onDismiss = { showPicker = false },
            onPick = { vm.addSelectionTo(it.id); showPicker = false },
            onCreate = { vm.createCollectionWithSelection(it); showPicker = false },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text(if (selection.isEmpty()) "Select screenshots" else "${selection.size} selected", style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = { IconButton(onClick = vm::clearSelection) { Icon(Icons.Rounded.Close, "Clear selection") } },
                    actions = { IconButton(onClick = vm::selectAll) { Icon(Icons.Rounded.SelectAll, "Select all") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            }
        },
        bottomBar = {
            if (selection.isNotEmpty()) {
                SelectionBar(
                    onAdd = { showPicker = true },
                    onShare = { context.startSafely(MediaActions.shareIntent(vm.selectedUris())) },
                    onDelete = { deleter.delete(vm.selectedUris()) { ok -> if (ok) vm.forgetSelection() } },
                )
            }
        },
    ) { padding ->
        val glass = rememberGlass()
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        // Room under the last row for the floating search bar.
        val searchSpace = if (selectionMode) 0.dp else navBottom + 88.dp
        val headerCount = if (selectionMode) 0 else 1 + (if (collections.isNotEmpty() || favoriteCount > 0) 1 else 0) + (if (categoryCounts.isNotEmpty()) 1 else 0)
        val marks = remember(days, headerCount, shots.isEmpty()) {
            var index = headerCount + if (shots.isEmpty()) 1 else 0
            days.map { (_, list) -> ScrubMark(index, list.first().takenAt).also { index += 1 + list.size } }
        }
        Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = maxOf(padding.calculateBottomPadding(), searchSpace) + 16.dp),
            modifier = Modifier.fillMaxSize()
                .glassSource(glass)
                .pinchToChangeColumns(columns, vm::setColumns, min = 2, max = 5)
                .dragToSelect(gridState, { currentSelection }, vm::setSelection, { currentIds }),
        ) {
            if (!selectionMode) {
                fullSpan("header") {
                    Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        DotLargeTitle("SCREENSHOTS", Modifier.weight(1f))
                        if (shots.isNotEmpty()) IconButton(onClick = vm::startSelecting) { Icon(Icons.Rounded.Checklist, "Select") }
                        IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings") }
                    }
                }
                if (collections.isNotEmpty() || favoriteCount > 0) {
                    fullSpan("collections") {
                        CollectionsRow(collections, favoriteCount, shots.firstOrNull { it.favorite }?.uri, onOpenCollection, onOpenFavorites)
                    }
                }
                val categories = categoryCounts.entries.sortedByDescending { it.value }
                if (categories.isNotEmpty()) {
                    fullSpan("categories") {
                        Column {
                            SectionLabel("Categories", Modifier.padding(horizontal = 20.dp))
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(categories, key = { it.key }) { (id, n) ->
                                    DotChip(Categories.byId(id)?.label ?: id, onClick = { onOpenCategory(id) }, count = n)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }

            if (shots.isEmpty()) {
                fullSpan("empty") {
                    DotEmptyState(
                        title = "No screenshots yet",
                        message = "Take a screenshot and it will show up here, ready to search.",
                        icon = Icons.Rounded.PhotoLibrary,
                    )
                }
            }

            days.forEach { (label, list) ->
                fullSpan("day-$label") {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 8.dp),
                    )
                }
                items(list, key = { it.id }) { shot ->
                    ShotThumb(
                        shot = shot,
                        selected = shot.id in selection,
                        selectionMode = selectionMode,
                        onClick = { if (selectionMode) vm.toggle(shot.id) else onOpenShot(shot.id) },
                    )
                }
            }
        }
            if (!selectionMode) {
                DotSearchPill(
                    "Search your screenshots",
                    onClick = onSearch,
                    glass = glass,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                )
                // Its highest position stays clear of the title row (Select and Settings buttons).
                DateScrubber(
                    gridState,
                    marks,
                    Modifier.align(Alignment.CenterEnd),
                    topInset = padding.calculateTopPadding() + 88.dp,
                    bottomInset = searchSpace,
                )
            }
        }
    }
}

private fun LazyGridScope.fullSpan(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun CollectionsRow(
    collections: List<ShotCollection>,
    favoriteCount: Int,
    favoriteCover: Uri?,
    onOpen: (Long) -> Unit,
    onOpenFavorites: () -> Unit,
) {
    Column {
        SectionLabel("Collections", Modifier.padding(horizontal = 20.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (favoriteCount > 0) {
                item(key = "fav") {
                    CollectionCard("Favourites", favoriteCount, favoriteCover, onClick = onOpenFavorites, favorite = true)
                }
            }
            items(collections, key = { it.id }) { c ->
                CollectionCard(c.name, c.count, c.cover, onClick = { onOpen(c.id) })
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun CollectionCard(name: String, count: Int, cover: Uri?, onClick: () -> Unit, favorite: Boolean = false) {
    Column(Modifier.width(128.dp)) {
        Box {
            MediaThumbnail(model = cover, cornerRadius = 18.dp, aspectRatio = 1f, onClick = onClick, modifier = Modifier.clip(RoundedCornerShape(18.dp)))
            if (favorite) {
                Icon(
                    Icons.Rounded.Favorite,
                    contentDescription = null,
                    tint = DotTheme.extra.accent,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(20.dp),
                )
            }
        }
        Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text("$count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SelectionBar(onAdd: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit, addLabel: String = "Add to collection") {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            BarAction(Icons.AutoMirrored.Rounded.PlaylistAdd, addLabel, onAdd)
            BarAction(Icons.Rounded.Share, "Share", onShare)
            BarAction(Icons.Rounded.Delete, "Delete", onDelete)
        }
    }
}

@Composable
fun BarAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
