package com.pdrajan.dot.media

import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Write operations on shared media. On Android 11+ the system asks the user to confirm each
 * batch (unless the app has "Manage media" special access), so these return a PendingIntent the
 * UI launches with `StartIntentSenderForResult`.
 */
object MediaActions {

    /** Move to the system bin (Android 11+; items are kept 30 days). */
    fun trashRequest(context: Context, uris: List<Uri>, trash: Boolean = true): PendingIntent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && uris.isNotEmpty()) {
            MediaStore.createTrashRequest(context.contentResolver, uris, trash)
        } else {
            null
        }

    fun deleteRequest(context: Context, uris: List<Uri>): PendingIntent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && uris.isNotEmpty()) {
            MediaStore.createDeleteRequest(context.contentResolver, uris)
        } else {
            null
        }

    /**
     * Android 10 and older: delete directly. Android 10 may throw RecoverableSecurityException
     * for files another app created; callers fall back to asking the user.
     */
    suspend fun deleteDirect(context: Context, uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        uris.sumOf { runCatching { context.contentResolver.delete(it, null, null) }.getOrDefault(0) }
    }

    /** True when the user granted "Manage media" (Android 12+), so edits skip confirmations. */
    fun canManageMedia(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && MediaStore.canManageMedia(context)

    fun shareIntent(uris: List<Uri>, mimeType: String = "image/*"): Intent {
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris.first()) }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)) }
        }
        send.type = mimeType
        send.clipData = ClipData.newRawUri(null, uris.first()).also { clip ->
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null)
    }

    fun editIntent(uri: Uri, mimeType: String = "image/*"): Intent =
        Intent.createChooser(
            Intent(Intent.ACTION_EDIT).setDataAndType(uri, mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION),
            null,
        )

    fun setAsIntent(uri: Uri, mimeType: String = "image/*"): Intent =
        Intent.createChooser(
            Intent(Intent.ACTION_ATTACH_DATA).setDataAndType(uri, mimeType).putExtra("mimeType", mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            null,
        )
}
