package com.pdrajan.dotgallery.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
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
    val describing by c.describer.progress.collectAsStateWithLifecycle()
    val aiError by c.describer.error.collectAsStateWithLifecycle()
    val captions by remember { c.repo.observeCaptionCounts() }.collectAsStateWithLifecycle(0 to 0)
    var selection by remember { mutableStateOf(emptySet<Long>()) }
    var selecting by remember { mutableStateOf(false) }
    val onSelection: (Set<Long>) -> Unit = { selection = it; if (it.isEmpty()) selecting = false }
    var diagnostics by remember { mutableStateOf(false) }

    if (diagnostics) DiagnosticsDialog("DotGallery") { diagnostics = false }

    SelectionScaffold(
        items = items,
        selection = selection,
        onSelectionChange = onSelection,
        topBar = {},
        bottomBar = bottomBar,
        selecting = selecting,
    ) { padding ->
        MediaGrid(
            items = items,
            columns = columns,
            onColumnsChange = c.settings::setColumns,
            selection = selection,
            onSelectionChange = onSelection,
            onOpen = { m -> nav.viewer(items.map { it.id }, m.id) },
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
            selecting = selecting,
        ) {
            if (selection.isEmpty() && !selecting) {
                item(key = "title", span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        DotLargeTitle("PHOTOS", Modifier.weight(1f))
                        if (items.isNotEmpty()) IconButton(onClick = { selecting = true }) { Icon(Icons.Rounded.Checklist, "Select") }
                        IconButton(onClick = nav::settings) { Icon(Icons.Rounded.Settings, "Settings") }
                    }
                }
                item(key = "status", span = { GridItemSpan(maxLineSpan) }) {
                    IndexStatus(
                        progress = progress,
                        counts = counts,
                        describing = describing != null,
                        captions = captions,
                        processingAll = processingAll,
                        lastError = lastError ?: aiError,
                        waitingFor = c.power.blocker() ?: if (!c.power.backlogAllowed()) "older ones are done while charging" else null,
                        onProcessAll = c::processAllNow,
                        onStop = c::stopProcessing,
                        onDetails = { diagnostics = true },
                    )
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

/**
 * One line about the library: organising and describing (how many are left), why it's waiting
 * ([waitingFor]: battery level, battery saver, heat), or what went wrong.
 */
@Composable
fun IndexStatus(
    progress: IndexProgress,
    counts: IndexCounts,
    describing: Boolean,
    /** (described, waiting for a description). */
    captions: Pair<Int, Int>,
    processingAll: Boolean,
    lastError: String?,
    waitingFor: String?,
    onProcessAll: () -> Unit,
    onStop: () -> Unit,
    onDetails: () -> Unit,
) {
    val m = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    val total = counts.total + captions.first + captions.second
    val done = counts.indexed + captions.first
    val left = counts.pending + captions.second
    val stop: @Composable () -> Unit = { if (processingAll) TextButton(onClick = onStop) { Text("Stop", color = DotTheme.extra.accent) } }
    when {
        progress.preparing -> DotProgressStrip("Preparing the AI model… the first time takes a minute", m)
        lastError != null -> DotProgressStrip(
            "$lastError",
            m,
            action = { TextButton(onClick = onDetails) { Text("Details", color = DotTheme.extra.accent) } },
        )
        progress.running || describing -> DotProgressStrip(
            "AI is going through your photos · $left left",
            m,
            progress = if (total > 0) done.toFloat() / total else null,
            action = stop,
        )
        left > 0 -> DotProgressStrip(
            "$left photos waiting" + (waitingFor?.let { " · $it" } ?: ""),
            m,
            action = { TextButton(onClick = onProcessAll) { Text("Do it now", color = DotTheme.extra.accent) } },
        )
    }
}
