package com.pdrajan.dotscreenshots.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pdrajan.dot.design.DotEmptyState
import com.pdrajan.dot.design.DotLargeTitle
import com.pdrajan.dot.design.pinchToChangeColumns
import com.pdrajan.dot.engine.Categories
import com.pdrajan.dot.media.MediaActions
import com.pdrajan.dotscreenshots.AppContainer
import com.pdrajan.dotscreenshots.data.Shot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ShotListViewModel(private val c: AppContainer, val ctx: String) : ViewModel() {
    private val started = SharingStarted.WhileSubscribed(5_000)
    val collectionId: Long? = ctx.takeIf { it.startsWith("col:") }?.removePrefix("col:")?.toLongOrNull()

    val title: StateFlow<String> = when {
        collectionId != null -> c.repo.observeCollection(collectionId).map { it?.name ?: "Collection" }
        ctx.startsWith("cat:") -> flowOf(Categories.byId(ctx.removePrefix("cat:"))?.label ?: "Category")
        ctx == ShotContext.FAVORITES -> flowOf("Favourites")
        else -> flowOf("Screenshots")
    }.stateIn(viewModelScope, started, "")

    val shots: StateFlow<List<Shot>> = when {
        collectionId != null -> c.repo.observeCollectionShots(collectionId)
        ctx.startsWith("cat:") -> c.repo.observeCategory(ctx.removePrefix("cat:"))
        ctx == ShotContext.FAVORITES -> c.repo.observeFavorites()
        else -> c.repo.observeShots()
    }.stateIn(viewModelScope, started, emptyList())

    val collections = c.repo.observeCollections().stateIn(viewModelScope, started, emptyList())
    val columns: StateFlow<Int> = c.settings.gridColumns

    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection.asStateFlow()

    fun toggle(id: Long) {
        _selection.value = _selection.value.let { if (id in it) it - id else it + id }
    }

    fun clear() {
        _selection.value = emptySet()
    }

    fun selectedUris() = shots.value.filter { it.id in _selection.value }.map { it.uri }

    fun addSelectionTo(collectionId: Long) {
        val ids = _selection.value
        viewModelScope.launch { c.repo.addToCollection(collectionId, ids) }
        clear()
    }

    fun createCollectionWithSelection(name: String) {
        val ids = _selection.value
        viewModelScope.launch { c.repo.addToCollection(c.repo.createCollection(name), ids) }
        clear()
    }

    fun removeSelectionFromCollection() {
        val id = collectionId ?: return
        val ids = _selection.value
        viewModelScope.launch { c.repo.removeFromCollection(id, ids) }
        clear()
    }

    fun forgetSelection() {
        val ids = _selection.value
        viewModelScope.launch { c.repo.forget(ids) }
        clear()
    }

    fun rename(name: String) {
        val id = collectionId ?: return
        viewModelScope.launch { c.repo.renameCollection(id, name) }
    }

    fun deleteCollection() {
        val id = collectionId ?: return
        viewModelScope.launch { c.repo.deleteCollection(id) }
    }

    fun setColumns(n: Int) = c.settings.setGridColumns(n)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShotListScreen(context: String, onBack: () -> Unit, onOpenShot: (Long) -> Unit) {
    val vm = containerViewModel(key = "list-$context") { ShotListViewModel(it, context) }
    val title by vm.title.collectAsStateWithLifecycle()
    val shots by vm.shots.collectAsStateWithLifecycle()
    val collections by vm.collections.collectAsStateWithLifecycle()
    val columns by vm.columns.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val selectionMode = selection.isNotEmpty()
    val ctx = LocalContext.current
    val deleter = rememberDeleteLauncher()
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }

    BackHandler(enabled = selectionMode) { vm.clear() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    if (selectionMode) Text("${selection.size} selected", style = MaterialTheme.typography.titleLarge)
                },
                navigationIcon = {
                    if (selectionMode) {
                        IconButton(onClick = vm::clear) { Icon(Icons.Rounded.Close, "Clear selection") }
                    } else {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    }
                },
                actions = {
                    if (!selectionMode && vm.collectionId != null) {
                        IconButton(onClick = { renaming = true }) { Icon(Icons.Rounded.DriveFileRenameOutline, "Rename") }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.DeleteOutline, "Delete collection") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (selectionMode) {
                SelectionBar(
                    onAdd = { if (vm.collectionId != null) vm.removeSelectionFromCollection() else picker = true },
                    onShare = { ctx.startSafely(MediaActions.shareIntent(vm.selectedUris())) },
                    onDelete = { deleter.delete(vm.selectedUris()) { ok -> if (ok) vm.forgetSelection() } },
                    addLabel = if (vm.collectionId != null) "Remove" else "Add to collection",
                )
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            modifier = Modifier.fillMaxSize().pinchToChangeColumns(columns, vm::setColumns, min = 2, max = 5),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
                    DotLargeTitle(title.uppercase())
                    Text("${shots.size} screenshots", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (shots.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    DotEmptyState(
                        title = "Empty",
                        message = if (vm.collectionId != null) "Open a screenshot and tap Details → Collections → Add." else "Nothing here yet.",
                        icon = Icons.Rounded.PhotoLibrary,
                    )
                }
            }
            items(shots, key = { it.id }) { shot ->
                ShotThumb(
                    shot = shot,
                    selected = shot.id in selection,
                    selectionMode = selectionMode,
                    onClick = { if (selectionMode) vm.toggle(shot.id) else onOpenShot(shot.id) },
                    onLongClick = { vm.toggle(shot.id) },
                )
            }
        }
    }

    if (renaming) {
        TextInputDialog(title = "Rename collection", initial = title, onDismiss = { renaming = false }, onConfirm = {
            vm.rename(it)
            renaming = false
        })
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete collection?") },
            text = { Text("The screenshots stay on your phone; only the collection is removed.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteCollection()
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    if (picker) {
        CollectionPickerDialog(
            collections = collections,
            onDismiss = { picker = false },
            onPick = { vm.addSelectionTo(it.id); picker = false },
            onCreate = { vm.createCollectionWithSelection(it); picker = false },
        )
    }
}
