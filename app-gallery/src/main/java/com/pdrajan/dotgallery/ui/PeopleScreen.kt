package com.pdrajan.dotgallery.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pdrajan.dot.design.DotEmptyState
import com.pdrajan.dot.design.DotLargeTitle
import com.pdrajan.dotgallery.data.PersonSummary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(nav: GalleryNav) {
    val c = galleryContainer()
    var showHidden by remember { mutableStateOf(false) }
    val people by remember { c.repo.observePeople(includeHidden = true) }.collectAsStateWithLifecycle(emptyList())
    val peopleEnabled by c.settings.people.collectAsStateWithLifecycle()
    val shown = people.filter { showHidden || !it.hidden }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { showHidden = !showHidden }) {
                        Icon(if (showHidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (showHidden) "Hide hidden people" else "Show hidden people")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(start = 8.dp, bottom = 12.dp)) {
                    DotLargeTitle("PEOPLE")
                    Text(
                        "Faces are grouped on this phone. Tap someone to name, merge or hide them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (shown.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    DotEmptyState(
                        "No people yet",
                        if (peopleEnabled) "Faces appear here once your photos are organised (usually while charging)." else "Face grouping is off in Settings.",
                        icon = Icons.Rounded.Face,
                    )
                }
            }
            items(shown, key = { it.id }) { p ->
                Box(Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                    PersonBubble(p, size = 84) { nav.list(ListKind.PERSON, p.id.toString()) }
                }
            }
        }
    }
}

@Composable
fun PeoplePickerDialog(people: List<PersonSummary>, title: String, onDismiss: () -> Unit, onPick: (PersonSummary) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.headlineMedium) },
        text = {
            LazyVerticalGrid(columns = GridCells.Adaptive(84.dp), modifier = Modifier.heightIn(max = 420.dp)) {
                items(people, key = { it.id }) { p ->
                    Box(Modifier.padding(6.dp), contentAlignment = Alignment.Center) { PersonBubble(p, size = 64) { onPick(p) } }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
