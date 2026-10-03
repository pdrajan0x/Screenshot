package com.pdrajan.dotgallery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pdrajan.dot.design.pinchToChangeColumns
import com.pdrajan.dot.media.MediaActions
import com.pdrajan.dotgallery.data.Media
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Date-sectioned grid with pinch-to-zoom columns and drag-to-select. */
@Composable
fun MediaGrid(
    items: List<Media>,
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    selection: Set<Long>,
    onSelectionChange: (Set<Long>) -> Unit,
    onOpen: (Media) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    grouped: Boolean = true,
    state: LazyGridState = rememberLazyGridState(),
    header: LazyGridScope.() -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val currentItems by rememberUpdatedState(items)
    val currentSelection by rememberUpdatedState(selection)
    val selectionMode = selection.isNotEmpty()
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = state,
        contentPadding = contentPadding,
        modifier = modifier
            .fillMaxSize()
            .pinchToChangeColumns(columns, onColumnsChange, min = 2, max = 7)
            .dragToSelect(state, { currentSelection }, onSelectionChange, { currentItems.map { it.id } }, scope),
    ) {
        header()
        if (grouped) {
            sections(items, columns).forEach { (label, list) ->
                item(key = "h-$label", span = { GridItemSpan(maxLineSpan) }) {
                    Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 8.dp))
                }
                items(list, key = { it.id }) { m -> Thumb(m, selection, selectionMode, onSelectionChange, onOpen) }
            }
        } else {
            items(items, key = { it.id }) { m -> Thumb(m, selection, selectionMode, onSelectionChange, onOpen) }
        }
    }
}

@Composable
private fun Thumb(m: Media, selection: Set<Long>, selectionMode: Boolean, onSelectionChange: (Set<Long>) -> Unit, onOpen: (Media) -> Unit) {
    MediaThumb(
        media = m,
        selected = m.id in selection,
        selectionMode = selectionMode,
        onClick = {
            if (selectionMode) onSelectionChange(if (m.id in selection) selection - m.id else selection + m.id) else onOpen(m)
        },
    )
}

/**
 * Scaffold that swaps in a selection top bar + action bar, and carries out the actions
 * (album, favourite, archive, locked folder, bin) on the selected items.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionScaffold(
    items: List<Media>,
    selection: Set<Long>,
    onSelectionChange: (Set<Long>) -> Unit,
    topBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    bottomBar: @Composable () -> Unit = {},
    selectionActions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val c = galleryContainer()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val trash = rememberTrasher()
    val delete = rememberDeleter()
    val albums by remember { c.repo.observeAlbums() }.collectAsStateWithLifecycle(emptyList())
    var albumPicker by remember { mutableStateOf(false) }
    val selected = items.filter { it.id in selection }
    val selectionMode = selection.isNotEmpty()

    BackHandler(enabled = selectionMode) { onSelectionChange(emptySet()) }

    if (albumPicker) {
        AlbumPickerDialog(
            albums = albums,
            onDismiss = { albumPicker = false },
            onPick = { a ->
                albumPicker = false
                scope.launch { c.repo.addToAlbum(a.id, selection) }
                onSelectionChange(emptySet())
            },
            onCreate = { name ->
                albumPicker = false
                scope.launch { c.repo.addToAlbum(c.repo.createAlbum(name), selection) }
                onSelectionChange(emptySet())
            },
        )
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("${selection.size} selected", style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = { IconButton(onClick = { onSelectionChange(emptySet()) }) { Icon(Icons.Rounded.Close, "Clear selection") } },
                    actions = {
                        selectionActions()
                        IconButton(onClick = { onSelectionChange(items.map { it.id }.toSet()) }) { Icon(Icons.Rounded.SelectAll, "Select all") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            } else {
                topBar()
            }
        },
        bottomBar = {
            if (selectionMode) {
                SelectionActions(
                    onShare = {
                        val mime = if (selected.all { !it.isVideo }) "image/*" else if (selected.all { it.isVideo }) "video/*" else "*/*"
                        ctx.startSafely(MediaActions.shareIntent(selected.map { it.uri }, mime))
                    },
                    onAddToAlbum = { albumPicker = true },
                    onFavorite = {
                        val fav = !selected.all { it.favorite }
                        scope.launch { c.repo.setFavorite(selection, fav) }
                        onSelectionChange(emptySet())
                    },
                    onArchive = {
                        val archive = !selected.all { it.archived }
                        scope.launch { c.repo.setArchived(selection, archive) }
                        ctx.toast(if (archive) "Archived" else "Unarchived")
                        onSelectionChange(emptySet())
                    },
                    onLock = {
                        val toLock = selected
                        onSelectionChange(emptySet())
                        scope.launch {
                            val copied = c.locked.copyIn(toLock)
                            if (copied.isEmpty()) return@launch
                            delete(copied.map { it.media.uri }) { ok ->
                                scope.launch {
                                    if (ok) {
                                        c.repo.forget(copied.map { it.media.id })
                                        ctx.toast("Moved to Locked folder")
                                    } else {
                                        c.locked.rollback(copied)
                                    }
                                }
                            }
                        }
                    },
                    onDelete = {
                        val ids = selection
                        trash(selected.map { it.uri }) { ok ->
                            if (ok) scope.launch { c.repo.forget(ids) }
                        }
                        onSelectionChange(emptySet())
                    },
                    archived = selected.isNotEmpty() && selected.all { it.archived },
                    allFavorite = selected.isNotEmpty() && selected.all { it.favorite },
                )
            } else {
                bottomBar()
            }
        },
        content = content,
    )
}
