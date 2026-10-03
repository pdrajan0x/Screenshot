package com.pdrajan.dotgallery.data

import android.net.Uri
import com.pdrajan.dot.engine.MatchReason
import com.pdrajan.dot.media.MediaType

/** A photo or video as shown in grids. */
data class Media(
    val id: Long,
    val uri: Uri,
    val type: MediaType,
    val name: String,
    val mime: String?,
    val takenAt: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val durationMs: Long,
    val bucketId: Long,
    val bucket: String?,
    val favorite: Boolean,
    val archived: Boolean,
    /** Keywords the AI wrote for it. */
    val keywords: List<String>,
    /** The AI has described it (a tiny red dot on its thumbnail). */
    val described: Boolean,
) {
    val isVideo get() = type == MediaType.VIDEO
}

data class MediaDetail(
    val media: Media,
    val path: String?,
    val text: String,
    val albums: List<AlbumSummary>,
    val people: List<PersonSummary>,
    /** One-sentence description from the on-device vision model, once written. */
    val caption: String? = null,
)

/** A device folder (MediaStore bucket). */
data class Folder(val id: Long, val name: String, val count: Int, val cover: Uri?, val isVideoCover: Boolean)

data class AlbumSummary(val id: Long, val name: String, val count: Int, val cover: Uri?)

data class PersonSummary(val id: Long, val name: String?, val count: Int, val thumb: String?, val hidden: Boolean)


data class LockedItem(
    val id: Long,
    val file: String,
    val name: String,
    val mime: String,
    val isVideo: Boolean,
    val takenAt: Long,
    val sizeBytes: Long,
)

data class SearchResult(val media: Media, val reasons: Set<MatchReason>)

data class IndexCounts(val total: Int, val indexed: Int, val pending: Int)
