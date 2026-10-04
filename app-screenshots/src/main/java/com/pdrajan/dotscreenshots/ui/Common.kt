package com.pdrajan.dotscreenshots.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CurrencyRupee
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.pdrajan.dot.design.DotTheme
import com.pdrajan.dot.design.MediaThumb
import androidx.compose.ui.platform.LocalWindowInfo
import com.pdrajan.dot.design.MediaThumbnail
import com.pdrajan.dot.design.MediaThumbs
import com.pdrajan.dot.engine.Entity
import com.pdrajan.dot.engine.EntityType
import com.pdrajan.dot.media.MediaActions
import com.pdrajan.dotscreenshots.data.IndexState
import com.pdrajan.dotscreenshots.data.Shot
import com.pdrajan.dotscreenshots.data.ShotCollection
import kotlinx.coroutines.launch

/** Groups shots under Google Photos–style day headers. */
fun groupByDay(shots: List<Shot>): List<Pair<String, List<Shot>>> {
    val today = java.time.LocalDate.now()
    return shots.groupBy { com.pdrajan.dot.design.DateLabels.day(it.takenAt, today) }.toList()
}

/** Key of a screenshot's picture shared between the grids and the viewer. */
fun shotKey(id: Long) = "shot-$id"

@Composable
fun ShotThumb(
    shot: Shot,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Null where the grid's drag-to-select handles the long press. */
    onLongClick: (() -> Unit)? = null,
    /** The grid's column count: the thumbnail is made at the cell's real size, so it's sharp. */
    columns: Int = 3,
    overlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
) {
    val cellPx = LocalWindowInfo.current.containerSize.width / columns.coerceAtLeast(1)
    MediaThumbnail(
        sizePx = cellPx.coerceAtLeast(64),
        model = MediaThumb(shot.uri),
        memoryCacheKey = MediaThumbs.cacheKey(shot.id),
        sharedKey = shotKey(shot.id),
        modifier = modifier.padding(1.dp),
        contentDescription = shot.app ?: "Screenshot",
        selected = selected,
        selectionMode = selectionMode,
        aspectRatio = 9f / 16f,
        cornerRadius = 4.dp,
        onClick = onClick,
        onLongClick = onLongClick,
        pending = shot.state == IndexState.PENDING,
        overlay = overlay,
    )
}

/** Launches the system delete/trash confirmation and reports whether the user agreed. */
class DeleteLauncher(private val launch: (List<Uri>, (Boolean) -> Unit) -> Unit) {
    fun delete(uris: List<Uri>, onResult: (Boolean) -> Unit) = launch(uris, onResult)
}

@Composable
fun rememberDeleteLauncher(): DeleteLauncher {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var callback by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        callback?.invoke(result.resultCode == Activity.RESULT_OK)
        callback = null
    }
    return remember(launcher) {
        DeleteLauncher { uris, onResult ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Moves to the system bin (restorable for 30 days) rather than deleting outright.
                val pi = MediaActions.trashRequest(context, uris) ?: return@DeleteLauncher onResult(false)
                callback = onResult
                launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
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
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = DotTheme.extra.accent,
                    cursorColor = DotTheme.extra.accent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun CollectionPickerDialog(
    collections: List<ShotCollection>,
    onDismiss: () -> Unit,
    onPick: (ShotCollection) -> Unit,
    onCreate: (String) -> Unit,
) {
    var creating by remember { mutableStateOf(collections.isEmpty()) }
    if (creating) {
        TextInputDialog(
            title = "New collection",
            confirm = "Create",
            placeholder = "e.g. Movies to watch",
            onDismiss = onDismiss,
            onConfirm = onCreate,
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to collection", style = MaterialTheme.typography.headlineMedium) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item {
                    PickerRow(Icons.Rounded.Add, "New collection") { creating = true }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                items(collections, key = { it.id }) { c ->
                    PickerRow(Icons.Rounded.Folder, c.name, "${c.count}") { onPick(c) }
                }
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
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- entity actions

data class EntityAction(val icon: ImageVector, val label: String, val run: (Context) -> Unit)

fun entityActions(e: Entity): List<EntityAction> = when (e.type) {
    EntityType.URL -> listOf(
        EntityAction(Icons.Rounded.Link, "Open ${shorten(e.text)}") { it.startSafely(Intent(Intent.ACTION_VIEW, Uri.parse(e.value))) },
    )
    EntityType.EMAIL -> listOf(
        EntityAction(Icons.Rounded.Email, "Email ${e.value}") { it.startSafely(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${e.value}"))) },
    )
    EntityType.PHONE -> listOf(
        EntityAction(Icons.Rounded.Call, "Call ${e.text}") { it.startSafely(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${e.value}"))) },
    )
    EntityType.UPI -> listOf(
        EntityAction(Icons.Rounded.Payments, "Pay ${e.value}") {
            it.startSafely(Intent(Intent.ACTION_VIEW, Uri.parse("upi://pay?pa=${Uri.encode(e.value)}&cu=INR")))
        },
        EntityAction(Icons.Rounded.ContentCopy, "Copy UPI ID") { it.copy("UPI ID", e.value) },
    )
    EntityType.AMOUNT -> listOf(
        EntityAction(Icons.Rounded.CurrencyRupee, "Copy ${e.value}") { it.copy("Amount", e.value) },
    )
    EntityType.DATE -> listOf(
        EntityAction(Icons.Rounded.ContentCopy, "Copy ${e.text}") { it.copy("Date", e.text) },
    )
    EntityType.CODE -> listOf(
        EntityAction(Icons.Rounded.Password, "Copy code ${e.value}") { it.copy("Code", e.value) },
    )
}

private fun shorten(s: String) = s.removePrefix("https://").removePrefix("http://").removePrefix("www.").take(28)

fun Context.copy(label: String, text: String) {
    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
}

fun Context.startSafely(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(this, "No app can open this", Toast.LENGTH_SHORT).show() }
}

@Composable
fun SheetSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.padding(top = 8.dp)) { content() }
    }
}
