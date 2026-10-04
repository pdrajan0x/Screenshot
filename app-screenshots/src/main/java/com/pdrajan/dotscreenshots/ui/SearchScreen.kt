package com.pdrajan.dotscreenshots.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pdrajan.dot.design.DotChip
import com.pdrajan.dot.design.DotEmptyState
import com.pdrajan.dot.design.DotLoader
import com.pdrajan.dot.design.DotOutlinedButton
import com.pdrajan.dot.design.DotSearchField
import com.pdrajan.dot.design.DotTag
import com.pdrajan.dot.design.DotTheme
import com.pdrajan.dot.design.SectionLabel
import com.pdrajan.dot.engine.Categories
import com.pdrajan.dot.engine.FtsQuery
import com.pdrajan.dot.engine.DateQueryParser
import com.pdrajan.dot.engine.MatchReason
import com.pdrajan.dot.engine.Snippet
import com.pdrajan.dot.engine.VectorHit
import com.pdrajan.dotscreenshots.AppContainer
import com.pdrajan.dotscreenshots.data.SearchHit
import com.pdrajan.dotscreenshots.data.Shot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import com.pdrajan.dot.design.glassSource
import com.pdrajan.dot.design.rememberGlass

data class SearchUi(
    val query: String = "",
    /** The strong matches, best first. */
    val hits: List<SearchHit> = emptyList(),
    /** Weaker matches (the words only somewhere in the screen text), shown on request. */
    val more: List<SearchHit> = emptyList(),
    val searching: Boolean = false,
    val visualPending: Boolean = false,
    /** For a date in the query ("last week"): the range it means. */
    val dateLabel: String? = null,
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

    /**
     * Precise first: a category asked for by name, then screenshots whose app, headings, picture
     * keywords or note have every word; weaker text matches are kept apart. Look-alike matching
     * (CLIP) only runs when no words match at all, and keeps just the closest few.
     */
    private suspend fun run(q: String) {
        _ui.value = _ui.value.copy(searching = true)
        val parsed = DateQueryParser().parse(q)
        val rest = parsed.rest
        val range = parsed.range
        fun inRange(shot: Shot) = range == null || shot.takenAt in range.startMillis until range.endMillis
        if (rest.isBlank()) {
            val ids = range?.let { c.repo.idsTakenBetween(it.startMillis, it.endMillis) }.orEmpty()
            publish(q, ids.map { it to setOf(MatchReason.TEXT) }, emptyList(), visualPending = false, range?.label, ::inRange)
            return
        }
        val categoryIds = Categories.matchQuery(rest).flatMap { c.repo.categoryShots(it) }.distinct()
        val text = c.repo.textSearch(rest, c.hub.words)
        val best = categoryIds.map { it to setOf(MatchReason.CATEGORY) } + text.best.filter { it !in categoryIds }.map { it to setOf(MatchReason.TEXT) }
        val more = text.more.filter { it !in categoryIds }.map { it to setOf(MatchReason.TEXT) }
        val tryVisual = best.isEmpty() && more.isEmpty() && c.hub.available
        publish(q, best, more, visualPending = tryVisual, range?.label, ::inRange)
        if (!tryVisual) return
        val visual: List<VectorHit> = runCatching {
            val clip = c.hub.clip() ?: return@runCatching emptyList()
            c.repo.visualSearch(rest, clip)
        }.getOrDefault(emptyList())
        publish(q, visual.map { it.id to setOf(MatchReason.VISUAL) }, emptyList(), visualPending = false, range?.label, ::inRange)
    }

    private suspend fun publish(
        q: String,
        best: List<Pair<Long, Set<MatchReason>>>,
        more: List<Pair<Long, Set<MatchReason>>>,
        visualPending: Boolean,
        dateLabel: String?,
        keep: (Shot) -> Boolean,
    ) {
        val all = best + more
        val shots = c.repo.shotsByIds(all.map { it.first })
        val texts = c.repo.textsByIds(all.filter { MatchReason.TEXT in it.second }.map { it.first })
        val terms = FtsQuery.terms(DateQueryParser().parse(q).rest)
        fun hits(list: List<Pair<Long, Set<MatchReason>>>) = list.mapNotNull { (id, reasons) ->
            val shot = shots[id]?.takeIf(keep) ?: return@mapNotNull null
            SearchHit(shot, reasons, texts[id]?.let { Snippet.around(it, terms) })
        }
        val strong = hits(best)
        val weak = hits(more)
        c.lastSearchIds = (strong + weak).map { it.shot.id }
        if (_ui.value.query == q) {
            _ui.value = _ui.value.copy(hits = strong, more = weak, searching = false, visualPending = visualPending, dateLabel = dateLabel)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(onBack: () -> Unit, onOpenShot: (Long) -> Unit, initialQuery: String = "") {
    val vm = containerViewModel(key = "search-$initialQuery") { SearchViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val categoryCounts by vm.categoryCounts.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var showMore by remember(ui.query) { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (initialQuery.isNotBlank()) {
            if (ui.query.isBlank()) vm.onQueryChange(initialQuery)
        } else {
            runCatching { focus.requestFocus() }
        }
    }

    val glass = rememberGlass()
    // Results scroll under the floating search field; keep the last row clear of it.
    val bottomSpace = WindowInsets.ime.union(WindowInsets.navigationBars).asPaddingValues().calculateBottomPadding() + 88.dp
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().statusBarsPadding()) {
            // Results fill the screen; the search field floats over them at the bottom, within thumb reach (it rides above the keyboard).
            Column(Modifier.fillMaxSize().glassSource(glass)) {
                if (ui.query.isBlank()) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
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
                        Spacer(Modifier.height(bottomSpace))
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            when {
                                ui.searching && ui.hits.isEmpty() -> "Searching…"
                                else -> "${ui.hits.size} ${if (ui.hits.size == 1) "result" else "results"}" + (ui.dateLabel?.let { " · $it" } ?: "")
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (ui.searching || ui.visualPending) DotLoader()
                    }
                    if (!ui.searching && !ui.visualPending && ui.hits.isEmpty() && ui.more.isEmpty()) {
                        DotEmptyState(
                            title = "Nothing found",
                            message = "Try other words, or describe what's in the picture — like \"red car\" or \"movie poster\".",
                            icon = Icons.Rounded.SearchOff,
                        )
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            contentPadding = PaddingValues(bottom = bottomSpace),
                            modifier = Modifier.fillMaxSize(),
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
                            val shown = if (showMore) ui.hits + ui.more else ui.hits
                            items(shown, key = { it.shot.id }) { hit ->
                                ShotThumb(
                                    shot = hit.shot,
                                    selected = false,
                                    selectionMode = false,
                                    onClick = {
                                        vm.submit()
                                        onOpenShot(hit.shot.id)
                                    },
                                    overlay = {
                                        val tag = when {
                                            MatchReason.NOTE in hit.reasons -> "note"
                                            MatchReason.TEXT in hit.reasons -> "text"
                                            MatchReason.VISUAL in hit.reasons -> "visual"
                                            else -> null
                                        }
                                        hit.shot.app?.let { title ->
                                            Box(
                                                Modifier
                                                    .align(Alignment.BottomCenter)
                                                    .fillMaxWidth()
                                                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                                                    .padding(start = 8.dp, end = 8.dp, top = 18.dp, bottom = 6.dp),
                                            ) {
                                                Text(title, style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 2)
                                            }
                                        }
                                        if (tag != null) {
                                            DotTag(tag, Modifier.align(Alignment.TopStart).padding(6.dp), accent = tag == "visual")
                                        }
                                    },
                                )
                            }
                            if (ui.more.isNotEmpty() && !showMore) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                        DotOutlinedButton("Show more", onClick = { showMore = true })
                                    }
                                }
                            }
                        }
                    }
                }
            }
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
                glass = glass,
                modifier = Modifier.align(Alignment.BottomCenter).imePadding().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}
