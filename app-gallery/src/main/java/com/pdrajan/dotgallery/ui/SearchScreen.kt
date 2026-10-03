package com.pdrajan.dotgallery.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pdrajan.dot.design.DotChip
import com.pdrajan.dot.design.DotEmptyState
import com.pdrajan.dot.design.DotLoader
import com.pdrajan.dot.design.DotSearchField
import com.pdrajan.dot.design.SectionLabel
import com.pdrajan.dot.engine.DateQueryParser
import com.pdrajan.dot.engine.HybridRanker
import com.pdrajan.dotgallery.GalleryContainer
import com.pdrajan.dotgallery.data.Media
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private class SearchOutcome(val items: List<Media>, val label: String?)

private suspend fun runSearch(c: GalleryContainer, query: String, withVisual: Boolean): SearchOutcome {
    val parsed = DateQueryParser().parse(query)
    val rest = parsed.rest
    val inRange: Set<Long>? = parsed.range?.let { c.repo.idsTakenBetween(it.startMillis, it.endMillis) }
    val text = if (rest.isNotBlank()) c.repo.textSearch(rest) else emptyList()
    // Look-alike matching only fills in for photos the AI hasn't described yet: a described photo
    // is found by what its description and keywords say, so "car" doesn't bring up bikes.
    val visual = if (withVisual && rest.isNotBlank()) {
        val described = c.repo.describedIds()
        runCatching {
            c.hub.clip()?.let { clip -> c.repo.visualSearch(rest, clip) { id -> id !in described && (inRange == null || id in inRange) } }
        }.getOrNull().orEmpty()
    } else {
        emptyList()
    }
    val ranked = if (rest.isBlank() && inRange != null) {
        inRange.toList()
    } else {
        HybridRanker.merge(text, visual, emptyList()).map { it.id }.filter { inRange == null || it in inRange }
    }
    val byId = c.repo.mediaByIds(ranked)
    val items = ranked.mapNotNull { byId[it] }.let { list -> if (rest.isBlank()) list.sortedByDescending { it.takenAt } else list }
    return SearchOutcome(items, parsed.range?.label)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(nav: GalleryNav, bottomBar: @Composable () -> Unit) {
    val c = galleryContainer()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var query by rememberSaveable { mutableStateOf("") }
    var outcome by remember { mutableStateOf<SearchOutcome?>(null) }
    var searching by remember { mutableStateOf(false) }
    var recent by remember { mutableStateOf(emptyList<String>()) }
    var selection by remember { mutableStateOf(emptySet<Long>()) }
    var selecting by remember { mutableStateOf(false) }
    val onSelection: (Set<Long>) -> Unit = { selection = it; if (it.isEmpty()) selecting = false }
    val people by remember { c.repo.observePeople() }.collectAsStateWithLifecycle(emptyList())
    val columns by c.settings.columns.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { recent = c.repo.recentSearches() }
    LaunchedEffect(query) {
        if (query.isBlank()) {
            outcome = null
            return@LaunchedEffect
        }
        delay(300)
        searching = true
        outcome = runSearch(c, query, withVisual = false)
        outcome = runSearch(c, query, withVisual = c.hub.clipAvailable)
        searching = false
    }

    val items = outcome?.items.orEmpty()
    SelectionScaffold(
        items = items,
        selection = selection,
        onSelectionChange = onSelection,
        selecting = selecting,
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                DotSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Search photos, people, places…",
                    onBack = { query = "" },
                    onSearch = {
                        keyboard?.hide()
                        scope.launch { c.repo.addRecentSearch(query); recent = c.repo.recentSearches() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        },
        bottomBar = bottomBar,
    ) { padding ->
        if (query.isBlank()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())
                    .verticalScroll(rememberScrollState()),
            ) {
                if (people.isNotEmpty()) {
                    SectionLabel("People", Modifier.padding(horizontal = 20.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(people.take(15), key = { it.id }) { p ->
                            PersonBubble(p, size = 64) { if (p.name != null) query = p.name else nav.list(ListKind.PERSON, p.id.toString()) }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                if (recent.isNotEmpty()) {
                    SectionLabel("Recent", Modifier.padding(horizontal = 20.dp)) {
                        TextButton(onClick = { scope.launch { c.repo.clearRecentSearches(); recent = emptyList() } }) { Text("Clear") }
                    }
                    recent.forEach { q ->
                        Row(
                            Modifier.fillMaxWidth().clickable { query = q }.padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(16.dp))
                            Text(q, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                SectionLabel("Try", Modifier.padding(horizontal = 20.dp))
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("sunset", "food last month", "dog", "2024", "receipt", "red car").forEach { s -> DotChip(s, onClick = { query = s }) }
                }
            }
        } else {
            MediaGrid(
                items = items,
                columns = columns,
                onColumnsChange = c.settings::setColumns,
                selection = selection,
                onSelectionChange = onSelection,
                selecting = selecting,
                onOpen = { m -> nav.viewer(items.map { it.id }, m.id) },
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
                grouped = false,
            ) {
                item(key = "count", span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            buildString {
                                append(if (searching && items.isEmpty()) "Searching…" else "${items.size} results")
                                outcome?.label?.let { append(" · $it") }
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (searching) DotLoader()
                        if (!searching && items.isNotEmpty() && selection.isEmpty() && !selecting) {
                            TextButton(onClick = { selecting = true }) { Text("Select") }
                        }
                    }
                }
                if (!searching && items.isEmpty()) {
                    item(key = "none", span = { GridItemSpan(maxLineSpan) }) {
                        DotEmptyState("Nothing found", "Try describing the photo — \"birthday cake\", \"mountains 2023\", or a person's name.", icon = Icons.Rounded.SearchOff)
                    }
                }
            }
        }
    }
}
