package com.pdrajan.dotgallery.ui

import android.net.Uri
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.AutoDelete
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.pdrajan.dot.design.DotLargeTitle
import com.pdrajan.dot.design.DotTheme
import com.pdrajan.dot.design.MediaThumbnail
import com.pdrajan.dot.design.SectionLabel
import com.pdrajan.dotgallery.data.PersonSummary
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CollectionsScreen(nav: GalleryNav, bottomBar: @Composable () -> Unit) {
    val c = galleryContainer()
    val scope = rememberCoroutineScope()
    val albums by remember { c.repo.observeAlbums() }.collectAsStateWithLifecycle(emptyList())
    val folders by remember { c.repo.observeFolders() }.collectAsStateWithLifecycle(emptyList())
    val people by remember { c.repo.observePeople() }.collectAsStateWithLifecycle(emptyList())
    var newAlbum by remember { mutableStateOf(false) }

    if (newAlbum) {
        TextInputDialog(title = "New album", confirm = "Create", placeholder = "e.g. Goa 2026", onDismiss = { newAlbum = false }) { name ->
            newAlbum = false
            scope.launch {
                val id = c.repo.createAlbum(name)
                nav.list(ListKind.ALBUM, id.toString())
            }
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = bottomBar) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    DotLargeTitle("COLLECTIONS", Modifier.weight(1f))
                    IconButton(onClick = nav::settings) { Icon(Icons.Rounded.Settings, "Settings") }
                }
            }
            item {
                FlowRow(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    maxItemsInEachRow = 2,
                ) {
                    val tile = Modifier.weight(1f)
                    QuickTile(Icons.Rounded.Favorite, "Favourites", tile) { nav.list(ListKind.FAVORITES) }
                    QuickTile(Icons.Rounded.VideoLibrary, "Videos", tile) { nav.list(ListKind.VIDEOS) }
                    QuickTile(Icons.Rounded.Screenshot, "Screenshots", tile) { nav.list(ListKind.SCREENSHOTS) }
                    QuickTile(Icons.Rounded.NewReleases, "Recently added", tile) { nav.list(ListKind.RECENT) }
                    QuickTile(Icons.Rounded.Archive, "Archive", tile) { nav.list(ListKind.ARCHIVE) }
                    QuickTile(Icons.Rounded.Lock, "Locked folder", tile) { nav.locked() }
                    QuickTile(Icons.Rounded.AutoDelete, "Bin", tile) { nav.bin() }
                    QuickTile(Icons.Rounded.CleaningServices, "Utilities", tile) { nav.utilities() }
                }
                Spacer(Modifier.height(16.dp))
            }

            if (people.isNotEmpty()) {
                item {
                    SectionLabel("People", Modifier.padding(horizontal = 20.dp)) {
                        TextButton(onClick = nav::people) { Text("See all") }
                    }
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(people.take(20), key = { "p${it.id}" }) { p -> PersonBubble(p) { nav.list(ListKind.PERSON, p.id.toString()) } }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }

            item {
                SectionLabel("Albums", Modifier.padding(horizontal = 20.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    item(key = "new") { NewAlbumCard { newAlbum = true } }
                    items(albums, key = { it.id }) { a -> CoverCard(a.cover, a.name, "${a.count} items") { nav.list(ListKind.ALBUM, a.id.toString()) } }
                }
                Spacer(Modifier.height(16.dp))
            }

            if (folders.isNotEmpty()) {
                item { SectionLabel("On this device", Modifier.padding(horizontal = 20.dp)) }
                items(folders.chunked(2), key = { row -> row.first().id }) { row ->
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { f ->
                            Box(Modifier.weight(1f)) {
                                CoverCard(f.cover, f.name, "${f.count} items", fill = true) { nav.list(ListKind.FOLDER, f.id.toString()) }
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickTile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun PersonBubble(p: PersonSummary, size: Int = 76, onClick: () -> Unit) {
    Column(Modifier.width(size.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size.dp).clip(CircleShape).background(DotTheme.extra.thumbnailPlaceholder), contentAlignment = Alignment.Center) {
            if (p.thumb != null) {
                AsyncImage(model = File(p.thumb), contentDescription = p.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Rounded.Person, null)
            }
        }
        Text(
            p.name ?: "Add a name",
            style = MaterialTheme.typography.labelMedium,
            color = if (p.name == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun CoverCard(cover: Uri?, title: String, subtitle: String, fill: Boolean = false, onClick: () -> Unit) {
    Column(if (fill) Modifier.fillMaxWidth() else Modifier.width(140.dp)) {
        Box(Modifier.clip(RoundedCornerShape(18.dp))) {
            MediaThumbnail(model = cover, aspectRatio = 1f, cornerRadius = 18.dp, onClick = onClick)
        }
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NewAlbumCard(onClick: () -> Unit) {
    Column(Modifier.width(140.dp)) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, "New album", modifier = Modifier.size(32.dp)) }
        }
        Text("New album", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
        Text(" ", style = MaterialTheme.typography.labelSmall)
    }
}
