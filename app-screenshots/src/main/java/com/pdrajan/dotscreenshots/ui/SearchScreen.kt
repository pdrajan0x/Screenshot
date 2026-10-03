package com.pdrajan.dotscreenshots.ui

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pdrajan.dot.design.DotChip
import com.pdrajan.dot.design.DotEmptyState
import com.pdrajan.dot.design.DotLoader
import com.pdrajan.dot.design.DotSearchField
import com.pdrajan.dot.design.DotTag
import com.pdrajan.dot.design.SectionLabel
import com.pdrajan.dot.engine.Categories
import com.pdrajan.dot.engine.FtsQuery
import com.pdrajan.dot.engine.HybridRanker
import com.pdrajan.dot.engine.MatchReason
import com.pdrajan.dot.engine.RankedResult
import com.pdrajan.dot.engine.Snippet
import com.pdrajan.dot.engine.VectorHit
import com.pdrajan.dotscreenshots.AppContainer
import com.pdrajan.dotscreenshots.data.SearchHit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SearchUi(
    val query: String = "",
    val hits: List<SearchHit> = emptyList(),
    val searching: Boolean = false,
    val visualPending: Boolean = false,
)

class SearchViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(SearchUi())
    val ui: StateFlow<SearchUi> = _ui.asStateFlow()

    private val _recent = MutableStateFlow<List<String>>(emptyList())
    val recent: StateFlow<List<String>> = _recent.asStateFlow()

    val categoryCounts = c.repo.observeCategoryCounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private var job: Job? = null

    init {
        viewModelScope.launch { _recent.value = c.repo.recentSearches() }
    }

    fun onQueryChange(q: String) {
        _ui.value = _ui.value.copy(query = q)
        job?.cancel()
        if (q.isBlank()) {
            _ui.value = SearchUi()
            return
        }
        job = viewModelScope.launch {
            delay(250)
            run(q)
        }
    }

    fun submit() {
        val q = _ui.value.query
        viewModelScope.launch {
            c.repo.addRecentSearch(q)
            _recent.value = c.repo.recentSearches()
        }
    }

    fun clearRecent() {
        viewModelScope.launch {
            c.repo.clearRecentSearches()
            _recent.value = emptyList()
        }
    }

    private suspend fun run(q: String) {
        _ui.value = _ui.value.copy(searching = true)
        val text = c.repo.textSearch(q)
        val categoryIds = Categories.matchQuery(q).flatMap { c.repo.categoryShots(it) }.distinct()
        publish(q, HybridRanker.merge(text.ids, emptyList(), categoryIds, text.noteIds), visualPending = c.hub.available)

        if (!c.hub.available) return
        val visual: List<VectorHit> = runCatching {
            val clip = c.hub.clip() ?: return@runCatching emptyList()
            c.repo.visualSearch(q, clip)
        }.getOrDefault(emptyList())
        publish(q, HybridRanker.merge(text.ids, visual, categoryIds, text.noteIds), visualPending = false)
    }

    private suspend fun publish(q: String, ranked: List<RankedResult>, visualPending: Boolean) {
        val ids = ranked.map { it.id }
        val shots = c.repo.shotsByIds(ids)
        val texts = c.repo.textsByIds(ranked.filter { MatchReason.TEXT in it.reasons || MatchReason.NOTE in it.reasons }.map { it.id })
        val terms = FtsQuery.terms(q)
        val hits = ranked.mapNotNull { r ->
            val shot = shots[r.id] ?: return@mapNotNull null
            SearchHit(shot, r.reasons, texts[r.id]?.let { Snippet.around(it, terms) })
        }
        c.lastSearchIds = hits.map { it.shot.id }
        if (_ui.value.query == q) {
            _ui.value = _ui.value.copy(hits = hits, searching = false, visualPending = visualPending)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(onBack: () -> Unit, onOpenShot: (Long) -> Unit) {
    val vm = containerViewModel { SearchViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val categoryCounts by vm.categoryCounts.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            DotSearchField(
                value = ui.query,
                onValueChange = vm::onQueryChange,
                placeholder = "Search screenshots",
                onBack = onBack,
                focusRequester = focus,
                onSearch = {
                    vm.submit()
                    keyboard?.hide()
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )

            if (ui.query.isBlank()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .imePadding()
                        .navigationBarsPadding(),
                ) {
                    if (recent.isNotEmpty()) {
                        SectionLabel("Recent", Modifier.padding(horizontal = 20.dp)) {
                            TextButton(onClick = vm::clearRecent) { Text("Clear") }
                        }
                        recent.forEach { q ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { vm.onQueryChange(q) }
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(16.dp))
                                Text(q, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    SectionLabel("Try", Modifier.padding(horizontal = 20.dp))
                    FlowRow(
                        Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf("car", "movie", "UPI payment", "food", "ticket", "dog").forEach { s ->
                            DotChip(s, onClick = { vm.onQueryChange(s) })
                        }
                    }
                    if (categoryCounts.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        SectionLabel("Categories", Modifier.padding(horizontal = 20.dp))
                        FlowRow(
                            Modifier.padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            categoryCounts.entries.sortedByDescending { it.value }.forEach { (id, n) ->
                                val label = Categories.byId(id)?.label ?: id
                                DotChip(label, onClick = { vm.onQueryChange(Categories.byId(id)?.synonyms?.firstOrNull() ?: label) }, count = n)
                            }
                        }
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when {
                            ui.searching && ui.hits.isEmpty() -> "Searching…"
                            else -> "${ui.hits.size} ${if (ui.hits.size == 1) "result" else "results"}"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (ui.searching || ui.visualPending) DotLoader()
                }
                if (!ui.searching && !ui.visualPending && ui.hits.isEmpty()) {
                    DotEmptyState(
                        title = "Nothing found",
                        message = "Try other words, or describe what's in the picture — like \"red car\" or \"movie poster\".",
                        icon = Icons.Rounded.SearchOff,
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        modifier = Modifier.fillMaxSize().imePadding().navigationBarsPadding(),
                    ) {
                        val snippet = ui.hits.firstOrNull { it.snippet != null }?.snippet
                        if (snippet != null) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    "“$snippet”",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                                )
                            }
                        }
                        items(ui.hits, key = { it.shot.id }) { hit ->
                            ShotThumb(
                                shot = hit.shot,
                                selected = false,
                                selectionMode = false,
                                onClick = {
                                    vm.submit()
                                    onOpenShot(hit.shot.id)
                                },
                                onLongClick = {},
                                overlay = {
                                    val tag = when {
                                        MatchReason.NOTE in hit.reasons -> "note"
                                        MatchReason.TEXT in hit.reasons -> "text"
                                        MatchReason.VISUAL in hit.reasons -> "visual"
                                        else -> null
                                    }
                                    if (tag != null) {
                                        DotTag(tag, Modifier.align(Alignment.BottomStart).padding(6.dp), accent = tag == "visual")
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
