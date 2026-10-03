package com.pdrajan.dotgallery.ui

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pdrajan.dot.design.DateLabels
import com.pdrajan.dot.design.DotTheme
import com.pdrajan.dot.design.MediaThumbnail
import com.pdrajan.dot.media.MediaActions
import com.pdrajan.dotgallery.GalleryApp
import com.pdrajan.dotgallery.GalleryContainer
import com.pdrajan.dotgallery.data.AlbumSummary
import com.pdrajan.dotgallery.data.Media
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun galleryContainer(): GalleryContainer = (LocalContext.current.applicationContext as GalleryApp).container

@Composable
inline fun <reified VM : ViewModel> galleryViewModel(key: String? = null, crossinline create: (GalleryContainer) -> VM): VM {
    val c = galleryContainer()
    return viewModel(key = key, factory = viewModelFactory { initializer { create(c) } })
}

/** Day sections for grids; month sections when zoomed out (6+ columns), like Google Photos. */
fun sections(items: List<Media>, columns: Int): List<Pair<String, List<Media>>> {
    val today = java.time.LocalDate.now()
    return if (columns >= 6) items.groupBy { DateLabels.month(it.takenAt) }.toList()
    else items.groupBy { DateLabels.day(it.takenAt, today) }.toList()
}

@Composable
fun MediaThumb(
    media: Media,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MediaThumbnail(
        model = media.uri,
        modifier = modifier.padding(1.dp),
        contentDescription = media.name,
        selected = selected,
        selectionMode = selectionMode,
        onClick = onClick,
        onLongClick = null,
        processed = media.described,
    ) {
        if (media.isVideo) {
            Row(
                Modifier.align(Alignment.TopEnd).padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(formatDuration(media.durationMs), style = MaterialTheme.typography.labelSmall, color = Color.White)
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
        if (media.favorite && !selectionMode) {
            Icon(
                Icons.Rounded.Favorite,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).size(14.dp),
            )
        }
    }
}

/** Launches MediaStore confirmation requests (trash, restore, delete forever). */
class MediaRequestLauncher(private val launch: (PendingIntent?, (Boolean) -> Unit) -> Unit) {
    fun run(request: PendingIntent?, onResult: (Boolean) -> Unit) = launch(request, onResult)
}

@Composable
fun rememberMediaRequestLauncher(): MediaRequestLauncher {
    var callback by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        callback?.invoke(result.resultCode == Activity.RESULT_OK)
        callback = null
    }
    return remember(launcher) {
        MediaRequestLauncher { pi, onResult ->
            if (pi == null) {
                onResult(false)
            } else {
                callback = onResult
                launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            }
        }
    }
}

/** Move to bin (Android 11+) or delete (older), with the system's confirmation. */
@Composable
fun rememberTrasher(): (List<Uri>, (Boolean) -> Unit) -> Unit {
    val context = LocalContext.current
    val launcher = rememberMediaRequestLauncher()
    val scope = rememberCoroutineScope()
    return remember(launcher) {
        { uris, onResult ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                launcher.run(MediaActions.trashRequest(context, uris), onResult)
            } else {
                scope.launch { onResult(MediaActions.deleteDirect(context, uris) > 0) }
            }
        }
    }
}

/** Permanently delete, with the system's confirmation. */
@Composable
fun rememberDeleter(): (List<Uri>, (Boolean) -> Unit) -> Unit {
    val context = LocalContext.current
    val launcher = rememberMediaRequestLauncher()
    val scope = rememberCoroutineScope()
    return remember(launcher) {
        { uris, onResult ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                launcher.run(MediaActions.deleteRequest(context, uris), onResult)
            } else {
                scope.launch { onResult(MediaActions.deleteDirect(context, uris) > 0) }
            }
        }
    }
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String = "",
    confirm: String = "Save",
    placeholder: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.headlineMedium) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text(placeholder) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = DotTheme.extra.accent, cursorColor = DotTheme.extra.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun AlbumPickerDialog(
    albums: List<AlbumSummary>,
    onDismiss: () -> Unit,
    onPick: (AlbumSummary) -> Unit,
    onCreate: (String) -> Unit,
) {
    var creating by remember { mutableStateOf(albums.isEmpty()) }
    if (creating) {
        TextInputDialog(title = "New album", confirm = "Create", placeholder = "e.g. Goa 2026", onDismiss = onDismiss, onConfirm = onCreate)
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to album", style = MaterialTheme.typography.headlineMedium) },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                item {
                    PickerRow(Icons.Rounded.Add, "New album") { creating = true }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                items(albums, key = { it.id }) { a -> PickerRow(Icons.Rounded.PhotoAlbum, a.name, "${a.count}") { onPick(a) } }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PickerRow(icon: ImageVector, label: String, trailing: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Bottom action bar shown while items are selected. */
@Composable
fun SelectionActions(
    onShare: () -> Unit,
    onAddToAlbum: () -> Unit,
    onFavorite: () -> Unit,
    onArchive: () -> Unit,
    onLock: () -> Unit,
    onDelete: () -> Unit,
    archived: Boolean = false,
    allFavorite: Boolean = false,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            BarAction(Icons.Rounded.Share, "Share", onShare)
            BarAction(Icons.AutoMirrored.Rounded.PlaylistAdd, "Album", onAddToAlbum)
            BarAction(if (allFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Favourite", onFavorite)
            BarAction(if (archived) Icons.Rounded.Unarchive else Icons.Rounded.Archive, if (archived) "Unarchive" else "Archive", onArchive)
            BarAction(Icons.Rounded.Lock, "Lock", onLock)
            BarAction(Icons.Rounded.Delete, "Delete", onDelete)
        }
    }
}

@Composable
fun BarAction(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = MaterialTheme.colorScheme.onSurface) {
    Box(
        Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, tint = tint)
            Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
        }
    }
}

fun Context.startSafely(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(this, "No app can open this", Toast.LENGTH_SHORT).show() }
}

fun Context.toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}
