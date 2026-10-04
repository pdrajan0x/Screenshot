package com.pdrajan.dotscreenshots.data

import android.net.Uri
import com.pdrajan.dot.engine.Entity
import com.pdrajan.dot.engine.MatchReason

enum class IndexState(val code: Int) {
    PENDING(0), INDEXED(1), FAILED(2);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: PENDING
    }
}

/** A screenshot as shown in grids. */
data class Shot(
    val id: Long,
    val uri: Uri,
    val name: String,
    val takenAt: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val state: IndexState,
    val app: String?,
    val categories: List<String>,
    val favorite: Boolean,
)

/** Everything the detail screen shows for one screenshot. */
data class ShotDetail(
    val shot: Shot,
    val text: String,
    val entities: List<Entity>,
    val note: String,
    val collections: List<ShotCollection>,
    /** What MobileCLIP sees in the picture ("shoes", "beach"); empty for ordinary app screens. */
    val keywords: List<String> = emptyList(),
    /** For browser screenshots: the page that was open. */
    val pageUrl: String? = null,
    /** How the source app was found: file, user or visual (the screen's words; older versions: usage, model, guess). */
    val appSource: String? = null,
) {
    /** The app isn't certain (not from the file name or the user). */
    val appGuessed: Boolean get() = appSource == null || appSource == "guess" || appSource == "model" || appSource == "visual"
}

data class ShotCollection(
    val id: Long,
    val name: String,
    val count: Int,
    val cover: Uri?,
)

data class SearchHit(
    val shot: Shot,
    val reasons: Set<MatchReason>,
    val snippet: String?,
)

data class IndexCounts(val total: Int, val indexed: Int, val pending: Int, val failed: Int)
