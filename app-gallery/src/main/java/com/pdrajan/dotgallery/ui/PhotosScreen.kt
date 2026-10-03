package com.pdrajan.dotgallery.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pdrajan.dot.design.DiagnosticsDialog
import com.pdrajan.dot.design.DotEmptyState
import com.pdrajan.dot.design.DotLargeTitle
import com.pdrajan.dot.design.DotProgressStrip
import com.pdrajan.dot.design.DotTheme
import com.pdrajan.dotgallery.data.IndexCounts
import com.pdrajan.dotgallery.index.IndexProgress

@Composable
fun PhotosScreen(nav: GalleryNav, bottomBar: @Composable () -> Unit) {
    val c = galleryContainer()
    val items by remember { c.repo.observeTimeline() }.collectAsStateWithLifecycle(emptyList())
    val columns by c.settings.columns.collectAsStateWithLifecycle()
    val counts by remember { c.repo.observeCounts() }.collectAsStateWithLifecycle(IndexCounts(0, 0, 0))
    val progress by c.engine.progress.collectAsStateWithLifecycle()
    val processingAll by c.processingAll.collectAsStateWithLifecycle()
    val lastError by c.engine.lastError.collectAsStateWithLifecycle()
    var selection by remember { mutableStateOf(emptySet<Long>()) }
    var diagnostics by remember { mutableStateOf(false) }

    if (diagnostics) DiagnosticsDialog("DotGallery") { diagnostics = false }

    SelectionScaffold(
        items = items,
        selection = selection,
        onSelectionChange = { selection = it },
        topBar = {},
        bottomBar = bottomBar,
    ) { padding ->
        MediaGrid(
            items = items,
            columns = columns,
            onColumnsChange = c.settings::setColumns,
            selection = selection,
            onSelectionChange = { selection = it },
            onOpen = { m -> nav.viewer(items.map { it.id }, m.id) },
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
        ) {
            if (selection.isEmpty()) {
                item(key = "title", span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        DotLargeTitle("PHOTOS", Modifier.weight(1f))
                        IconButton(onClick = nav::settings) { Icon(Icons.Rounded.Settings, "Settings") }
                    }
                }
                item(key = "status", span = { GridItemSpan(maxLineSpan) }) {
                    IndexStatus(progress, counts, processingAll, c.hub.clipAvailable, lastError, c::processAllNow, c::stopProcessing) { diagnostics = true }
                }
            }
            if (items.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    DotEmptyState("No photos yet", "Photos and videos on this phone will appear here.", icon = Icons.Rounded.PhotoLibrary)
                }
            }
        }
    }
}

@Composable
fun IndexStatus(
    progress: IndexProgress,
    counts: IndexCounts,
    processingAll: Boolean,
    modelAvailable: Boolean,
    lastError: String?,
    onProcessAll: () -> Unit,
    onStop: () -> Unit,
    onDetails: () -> Unit,
) {
    val m = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    when {
        !modelAvailable -> Unit
        progress.preparing -> DotProgressStrip("Preparing the AI model… the first time takes a minute", m)
        processingAll -> DotProgressStrip(
            "Organising your library · ${counts.indexed} of ${counts.total}",
            m,
            progress = if (counts.total > 0) counts.indexed.toFloat() / counts.total else null,
            action = { TextButton(onClick = onStop) { Text("Stop", color = DotTheme.extra.accent) } },
        )
        progress.running -> DotProgressStrip(
            "Organising new photos · ${progress.done} of ${progress.total}",
            m,
            progress = if (progress.total > 0) progress.done.toFloat() / progress.total else null,
        )
        lastError != null -> DotProgressStrip(
            "Couldn't organise photos · $lastError",
            m,
            action = { TextButton(onClick = onDetails) { Text("Details", color = DotTheme.extra.accent) } },
        )
        counts.pending > 0 -> DotProgressStrip(
            "${counts.pending} photos will be organised while charging",
            m,
            action = { TextButton(onClick = onProcessAll) { Text("Do it now", color = DotTheme.extra.accent) } },
        )
    }
}
