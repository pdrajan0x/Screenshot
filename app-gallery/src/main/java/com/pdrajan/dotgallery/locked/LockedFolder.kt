package com.pdrajan.dotgallery.locked

import android.content.Context
import com.pdrajan.dot.media.MediaWriter
import com.pdrajan.dotgallery.data.GalleryRepository
import com.pdrajan.dotgallery.data.LockedItem
import com.pdrajan.dotgallery.data.Media
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Locked folder, like Google Photos: files are copied into app-private storage (invisible to
 * other apps and the gallery), then the originals are deleted with the user's confirmation.
 * Opening it requires the phone's screen lock or biometrics.
 */
class LockedFolder(private val context: Context, private val repo: GalleryRepository) {

    private val dir = File(context.filesDir, "locked").apply { mkdirs() }

    fun file(item: LockedItem) = File(dir, item.file)

    /** A copied item: the original media and its new locked-folder row. */
    data class Copied(val media: Media, val lockedId: Long, val file: String)

    /** Copies items in; the caller then deletes the originals (or calls [rollback]). */
    suspend fun copyIn(items: List<Media>): List<Copied> = withContext(Dispatchers.IO) {
        items.mapNotNull { m ->
            runCatching {
                val ext = m.name.substringAfterLast('.', if (m.isVideo) "mp4" else "jpg")
                val name = UUID.randomUUID().toString() + "." + ext
                context.contentResolver.openInputStream(m.uri)!!.use { input ->
                    File(dir, name).outputStream().use { input.copyTo(it) }
                }
                Copied(m, repo.addLocked(name, m), name)
            }.getOrNull()
        }
    }

    /** Copies an item back to shared storage (Pictures/Dot Gallery or Movies/Dot Gallery). */
    suspend fun restore(item: LockedItem): Boolean = withContext(Dispatchers.IO) {
        val f = file(item)
        val uri = MediaWriter.saveFile(context, f, item.name, item.mime) ?: return@withContext false
        f.delete()
        repo.removeLocked(item.id)
        uri.toString().isNotEmpty()
    }

    suspend fun delete(item: LockedItem) = withContext(Dispatchers.IO) {
        file(item).delete()
        repo.removeLocked(item.id)
    }

    /** Undo a copy-in when the user declined deleting the originals. */
    suspend fun rollback(copied: List<Copied>) = withContext(Dispatchers.IO) {
        copied.forEach {
            File(dir, it.file).delete()
            repo.removeLocked(it.lockedId)
        }
    }
}
