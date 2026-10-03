package com.pdrajan.dotgallery.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CallMerge
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pdrajan.dot.design.DotEmptyState
import com.pdrajan.dot.design.DotLargeTitle
import com.pdrajan.dot.engine.PhotoTags
import com.pdrajan.dotgallery.data.Media
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaListScreen(kind: String, arg: String, nav: GalleryNav) {
    val c = galleryContainer()
    val scope = rememberCoroutineScope()
    val id = arg.toLongOrNull() ?: -1L

    val itemsFlow: Flow<List<Media>> = remember(kind, arg) {
        when (kind) {
            ListKind.ALBUM -> c.repo.observeAlbum(id)
            ListKind.FOLDER -> c.repo.observeFolder(id)
            ListKind.TAG -> c.repo.observeTag(arg)
            ListKind.PERSON -> c.repo.observePerson(id)
            ListKind.FAVORITES -> c.repo.observeFavorites()
            ListKind.VIDEOS -> c.repo.observeVideos()
            ListKind.ARCHIVE -> c.repo.observeArchived()
            ListKind.RECENT -> c.repo.observeRecentlyAdded()
            ListKind.SCREENSHOTS -> c.repo.observeScreenshots()
            ListKind.BLURRY -> c.repo.observeBlurry()
            ListKind.LARGE_VIDEOS -> c.repo.observeLargeVideos()
            else -> flowOf(emptyList())
        }
    }
    val titleFlow: Flow<String> = remember(kind, arg) {
        when (kind) {
            ListKind.ALBUM -> c.repo.observeAlbumName(id).map { it ?: "Album" }
            ListKind.FOLDER -> c.repo.observeFolders().map { list -> list.firstOrNull { it.id == id }?.name ?: "Folder" }
            ListKind.TAG -> flowOf(PhotoTags.byId(arg)?.label ?: arg)
            ListKind.PERSON -> c.repo.observePersonSummary(id).map { it?.name ?: "Add a name" }
            ListKind.FAVORITES -> flowOf("Favourites")
            ListKind.VIDEOS -> flowOf("Videos")
            ListKind.ARCHIVE -> flowOf("Archive")
            ListKind.RECENT -> flowOf("Recently added")
            ListKind.SCREENSHOTS -> flowOf("Screenshots")
            ListKind.BLURRY -> flowOf("Blurry photos")
            ListKind.LARGE_VIDEOS -> flowOf("Large videos")
            else -> flowOf("")
        }
    }
    val items by itemsFlow.collectAsStateWithLifecycle(emptyList())
    val title by titleFlow.collectAsStateWithLifecycle("")
    val person by remember(id) { if (kind == ListKind.PERSON) c.repo.observePersonSummary(id) else flowOf(null) }.collectAsStateWithLifecycle(null)
    val people by remember { c.repo.observePeople(includeHidden = true) }.collectAsStateWithLifecycle(emptyList())
    val columns by c.settings.columns.collectAsStateWithLifecycle()
    var selection by remember { mutableStateOf(emptySet<Long>()) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    val grouped = kind !in setOf(ListKind.RECENT, ListKind.BLURRY, ListKind.LARGE_VIDEOS, ListKind.ALBUM)

    SelectionScaffold(
        items = items,
        selection = selection,
        onSelectionChange = { selection = it },
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    when (kind) {
                        ListKind.ALBUM -> {
                            IconButton(onClick = { renaming = true }) { Icon(Icons.Rounded.DriveFileRenameOutline, "Rename album") }
                            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.DeleteOutline, "Delete album") }
                        }
                        ListKind.PERSON -> {
                            IconButton(onClick = { renaming = true }) { Icon(Icons.Rounded.DriveFileRenameOutline, "Name") }
                            IconButton(onClick = { merging = true }) { Icon(Icons.Rounded.CallMerge, "Merge with…") }
                            val hidden = person?.hidden == true
                            IconButton(onClick = { scope.launch { c.repo.setPersonHidden(id, !hidden) } }) {
                                Icon(if (hidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, if (hidden) "Show" else "Hide")
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        selectionActions = {
            if (kind == ListKind.ALBUM) {
                IconButton(onClick = {
                    val ids = selection
                    scope.launch { c.repo.removeFromAlbum(id, ids) }
                    selection = emptySet()
                }) { Icon(Icons.Rounded.RemoveCircleOutline, "Remove from album") }
            }
        },
    ) { padding ->
        MediaGrid(
            items = items,
            columns = columns,
            onColumnsChange = c.settings::setColumns,
            selection = selection,
            onSelectionChange = { selection = it },
            onOpen = { m -> nav.viewer(items.map { it.id }, m.id) },
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
            grouped = grouped,
        ) {
            item(key = "title", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)) {
                    DotLargeTitle(title.uppercase())
                    Text("${items.size} items", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (items.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    DotEmptyState(
                        "Nothing here",
                        when (kind) {
                            ListKind.ALBUM -> "Select photos anywhere and tap Album to add them."
                            ListKind.ARCHIVE -> "Archived photos stay out of your Photos view."
                            ListKind.BLURRY, ListKind.LARGE_VIDEOS -> "Nothing to clean up."
                            else -> "No photos yet."
                        },
                        icon = Icons.Rounded.PhotoLibrary,
                    )
                }
            }
        }
    }

    if (renaming) {
        TextInputDialog(
            title = if (kind == ListKind.PERSON) "Who is this?" else "Rename album",
            initial = if (kind == ListKind.PERSON) person?.name.orEmpty() else title,
            onDismiss = { renaming = false },
        ) { name ->
            renaming = false
            scope.launch { if (kind == ListKind.PERSON) c.repo.renamePerson(id, name) else c.repo.renameAlbum(id, name) }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete album?") },
            text = { Text("Photos stay on your phone; only the album is removed.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { c.repo.deleteAlbum(id) }
                    nav.back()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    if (merging) {
        PeoplePickerDialog(
            people = people.filter { it.id != id },
            title = "Same person as…",
            onDismiss = { merging = false },
        ) { target ->
            merging = false
            scope.launch { c.repo.mergePeople(from = id, into = target.id) }
            nav.back()
        }
    }
}
