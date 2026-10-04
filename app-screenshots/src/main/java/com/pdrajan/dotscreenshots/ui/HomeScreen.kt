package com.pdrajan.dotscreenshots.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.pdrajan.dot.design.DiagnosticsDialog
import com.pdrajan.dot.design.DotChip
import com.pdrajan.dot.design.DotEmptyState
import com.pdrajan.dot.design.DotLargeTitle
import com.pdrajan.dot.design.DotProgressStrip
import com.pdrajan.dot.design.DotSearchPill
import com.pdrajan.dot.design.DotTheme
import com.pdrajan.dot.design.MediaThumbnail
import com.pdrajan.dot.design.SectionLabel
import com.pdrajan.dot.design.pinchToChangeColumns
import com.pdrajan.dot.engine.Categories
import com.pdrajan.dot.media.MediaActions
import com.pdrajan.dotscreenshots.AppContainer
import com.pdrajan.dotscreenshots.data.IndexCounts
import com.pdrajan.dotscreenshots.data.Shot
import com.pdrajan.dotscreenshots.data.ShotCollection
import com.pdrajan.dotscreenshots.index.IndexProgress
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
    val counts = c.repo.observeCounts().stateIn(viewModelScope, started, IndexCounts(0, 0, 0, 0))
    val favoriteCount = c.repo.observeFavorites().map { it.size }.stateIn(viewModelScope, started, 0)
    val progress: StateFlow<IndexProgress> = c.engine.progress
    val lastError: StateFlow<String?> = c.engine.lastError
    fun waitingFor(): String? = c.power.blocker() ?: if (!c.power.backlogAllowed()) "older ones are done while charging" else null
    val backlogRunning: StateFlow<Boolean> = c.backlogRunning
    val columns: StateFlow<Int> = c.settings.gridColumns
    val modelAvailable: Boolean get() = c.hub.available

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

    fun processAll() = c.processAllNow()
    fun stopProcessing() = c.stopProcessing()
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
    val counts by vm.counts.collectAsStateWithLifecycle()
    val favoriteCount by vm.favoriteCount.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val lastError by vm.lastError.collectAsStateWithLifecycle()
    var diagnostics by remember { mutableStateOf(false) }
    val backlogRunning by vm.backlogRunning.collectAsStateWithLifecycle()
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

    if (diagnostics) DiagnosticsDialog("DotScreenshots") { diagnostics = false }

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
            } else if (!selectionMode) {
                // Search sits at the bottom, within thumb reach.
                Surface(color = MaterialTheme.colorScheme.background) {
                    DotSearchPill(
                        "Search your screenshots",
                        onClick = onSearch,
                        modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            modifier = Modifier.fillMaxSize()
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
                fullSpan("status") {
                    StatusStrip(
                        progress = progress,
                        counts = counts,
                        backlogRunning = backlogRunning,
                        modelAvailable = vm.modelAvailable,
                        lastError = lastError,
                        waitingFor = vm.waitingFor(),
                        onProcessAll = vm::processAll,
                        onStop = vm::stopProcessing,
                        onDetails = { diagnostics = true },
                    )
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
    }
}

private fun LazyGridScope.fullSpan(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

/** One line about the library: how many screenshots are left, why it's waiting, or what went wrong. */
@Composable
private fun StatusStrip(
    progress: IndexProgress,
    counts: IndexCounts,
    backlogRunning: Boolean,
    modelAvailable: Boolean,
    lastError: String?,
    waitingFor: String?,
    onProcessAll: () -> Unit,
    onStop: () -> Unit,
    onDetails: () -> Unit,
) {
    val modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    // Screenshots still to read, plus those whose picture is read again for the newer image model.
    val left = counts.pending + counts.updating
    val total = counts.total
    val done = (total - left).coerceAtLeast(0)
    val stop: @Composable () -> Unit = { if (backlogRunning) TextButton(onClick = onStop) { Text("Stop", color = DotTheme.extra.accent) } }
    when {
        !modelAvailable -> DotProgressStrip("This build has no image model; only basic listing works.", modifier)
        progress.preparing -> DotProgressStrip("Getting ready… the first time takes a minute", modifier)
        lastError != null -> DotProgressStrip(
            text = lastError,
            modifier = modifier,
            action = { TextButton(onClick = onDetails) { Text("Details", color = DotTheme.extra.accent) } },
        )
        progress.running -> DotProgressStrip(
            text = "Reading your screenshots · $left left",
            modifier = modifier,
            progress = if (total > 0) done.toFloat() / total else null,
            action = stop,
        )
        left > 0 -> DotProgressStrip(
            text = "$left screenshots waiting" + (waitingFor?.let { " · $it" } ?: ""),
            modifier = modifier,
            action = { TextButton(onClick = onProcessAll) { Text("Do it now", color = DotTheme.extra.accent) } },
        )
    }
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
