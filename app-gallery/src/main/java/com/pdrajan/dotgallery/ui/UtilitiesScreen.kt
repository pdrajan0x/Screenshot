package com.pdrajan.dotgallery.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoDelete
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pdrajan.dot.design.DotEmptyState
import com.pdrajan.dot.design.DotLargeTitle
import com.pdrajan.dot.design.DotLoader
import com.pdrajan.dot.design.DotPrimaryButton
import com.pdrajan.dot.design.DotTag
import com.pdrajan.dot.design.MediaThumbnail
import com.pdrajan.dot.design.SectionLabel
import com.pdrajan.dot.design.SettingsRow
import com.pdrajan.dot.engine.ImageQuality
import com.pdrajan.dotgallery.data.Media
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Clean-up tools: duplicates (keep the best copy), blurry photos, large videos, screenshots. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UtilitiesScreen(nav: GalleryNav) {
    val c = galleryContainer()
    val scope = rememberCoroutineScope()
    val trash = rememberTrasher()
    var groups by remember { mutableStateOf<List<List<Media>>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val blurry by remember { c.repo.observeBlurry() }.collectAsStateWithLifecycle(emptyList())
    val large by remember { c.repo.observeLargeVideos() }.collectAsStateWithLifecycle(emptyList())
    val screenshots by remember { c.repo.observeScreenshots() }.collectAsStateWithLifecycle(emptyList())
    val locked by remember { c.repo.observeLocked() }.collectAsStateWithLifecycle(emptyList())

    LaunchedEffect(refresh) {
        groups = withContext(Dispatchers.Default) {
            val found = ImageQuality.duplicateGroups(c.repo.duplicateCandidates(), maxDistance = 4)
            val byId = c.repo.mediaByIds(found.flatten().map { it.id })
            found.map { g -> g.mapNotNull { byId[it.id] } }.filter { it.size > 1 }
        }
    }

    fun trashExtras(extras: List<Media>) {
        if (extras.isEmpty()) return
        trash(extras.map { it.uri }) { ok ->
            if (ok) {
                scope.launch {
                    c.repo.forget(extras.map { it.id })
                    refresh++
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                DotLargeTitle("UTILITIES", Modifier.padding(horizontal = 20.dp))
                Spacer(Modifier.height(12.dp))
                val chevron: @Composable () -> Unit = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) }
                SettingsRow(
                    title = "Blurry photos",
                    subtitle = "${blurry.size} found",
                    icon = Icons.Rounded.BlurOn,
                    onClick = { nav.list(ListKind.BLURRY) },
                    trailing = chevron,
                )
                SettingsRow(
                    title = "Large videos",
                    subtitle = "${large.size} over 50 MB · ${formatBytes(large.sumOf { it.sizeBytes })}",
                    icon = Icons.Rounded.VideoFile,
                    onClick = { nav.list(ListKind.LARGE_VIDEOS) },
                    trailing = chevron,
                )
                SettingsRow(
                    title = "Screenshots",
                    subtitle = "${screenshots.size} · ${formatBytes(screenshots.sumOf { it.sizeBytes })}",
                    icon = Icons.Rounded.Screenshot,
                    onClick = { nav.list(ListKind.SCREENSHOTS) },
                    trailing = chevron,
                )
                SettingsRow(title = "Locked folder", subtitle = "${locked.size} items", icon = Icons.Rounded.Lock, onClick = { nav.locked() }, trailing = chevron)
                SettingsRow(title = "Bin", subtitle = "Restore or delete forever", icon = Icons.Rounded.AutoDelete, onClick = { nav.bin() }, trailing = chevron)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 8.dp))
            }

            val g = groups
            item {
                SectionLabel("Duplicates", Modifier.padding(horizontal = 20.dp))
                Text(
                    "Near-identical photos (re-saves, forwards, bursts). The highest-resolution copy is kept. Only analysed photos are checked.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(12.dp))
                when {
                    g == null -> Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { DotLoader() }
                    g.isEmpty() -> DotEmptyState("No duplicates", "Nothing to clean up right now.", icon = Icons.Rounded.ContentCopy)
                    else -> {
                        val extras = g.flatMap { it.drop(1) }
                        DotPrimaryButton(
                            "Keep best · move ${extras.size} to bin (${formatBytes(extras.sumOf { it.sizeBytes })})",
                            onClick = { trashExtras(extras) },
                            accent = true,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            if (g != null) {
                items(g, key = { it.first().id }) { group -> DuplicateGroup(group, nav, onKeepBest = { trashExtras(group.drop(1)) }) }
            }
        }
    }
}

@Composable
private fun DuplicateGroup(group: List<Media>, nav: GalleryNav, onKeepBest: () -> Unit) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${group.size} copies · ${formatBytes(group.drop(1).sumOf { it.sizeBytes })} to free",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onKeepBest) { Text("Keep best") }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(group, key = { it.id }) { m ->
                MediaThumbnail(
                    model = m.uri,
                    modifier = Modifier.size(104.dp),
                    contentDescription = m.name,
                    cornerRadius = 12.dp,
                    onClick = { nav.viewer(group.map { it.id }, m.id) },
                ) {
                    if (m == group.first()) DotTag("Best", Modifier.align(Alignment.BottomStart).padding(6.dp), accent = true)
                    else DotTag(formatBytes(m.sizeBytes), Modifier.align(Alignment.BottomStart).padding(6.dp))
                }
            }
        }
    }
}
