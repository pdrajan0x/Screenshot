package com.pdrajan.dotgallery.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoDelete
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pdrajan.dot.design.DotEmptyState
import com.pdrajan.dot.design.DotLargeTitle
import com.pdrajan.dot.design.DotTag
import com.pdrajan.dot.design.MediaThumbnail
import com.pdrajan.dot.media.MediaActions
import com.pdrajan.dot.media.MediaItem

/** The system bin (Android 11+): restore or delete forever. Items expire after 30 days. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BinScreen(nav: GalleryNav) {
    val c = galleryContainer()
    val ctx = LocalContext.current
    val launcher = rememberMediaRequestLauncher()
    var items by remember { mutableStateOf(emptyList<MediaItem>()) }
    var refresh by remember { mutableIntStateOf(0) }
    var selection by remember { mutableStateOf(emptySet<Long>()) }

    LaunchedEffect(refresh) { items = c.media.trashed() }
    BackHandler(enabled = selection.isNotEmpty()) { selection = emptySet() }

    fun targets() = items.filter { it.id in selection }.ifEmpty { items }.map { it.uri }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { if (selection.isNotEmpty()) Text("${selection.size} selected") },
                navigationIcon = {
                    if (selection.isNotEmpty()) IconButton(onClick = { selection = emptySet() }) { Icon(Icons.Rounded.Close, "Clear selection") }
                    else IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (items.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        BarAction(Icons.Rounded.Restore, if (selection.isEmpty()) "Restore all" else "Restore", {
                            launcher.run(MediaActions.trashRequest(ctx, targets(), trash = false)) { ok ->
                                if (ok) { selection = emptySet(); refresh++; c.refresh() }
                            }
                        })
                        BarAction(Icons.Rounded.DeleteForever, if (selection.isEmpty()) "Empty bin" else "Delete forever", {
                            launcher.run(MediaActions.deleteRequest(ctx, targets())) { ok ->
                                if (ok) { selection = emptySet(); refresh++ }
                            }
                        })
                    }
                }
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
                    DotLargeTitle("BIN")
                    Text(
                        "Items are deleted forever after 30 days.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    DotEmptyState(
                        "Bin is empty",
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) "This Android version deletes items straight away." else "Deleted photos and videos land here.",
                        icon = Icons.Rounded.AutoDelete,
                    )
                }
            }
            items(items, key = { it.id }) { item ->
                val daysLeft = item.expiresAt?.let { ((it - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0) }
                MediaThumbnail(
                    model = item.uri,
                    modifier = Modifier.padding(1.dp),
                    selected = item.id in selection,
                    selectionMode = selection.isNotEmpty(),
                    onClick = { selection = if (item.id in selection) selection - item.id else selection + item.id },
                ) {
                    if (daysLeft != null) DotTag("${daysLeft}d", Modifier.align(Alignment.BottomEnd).padding(6.dp))
                }
            }
        }
    }
}
