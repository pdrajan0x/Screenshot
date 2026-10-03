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
    /** Written by the on-device summary model; null until then. */
    val title: String? = null,
    /** The AI has summarised it (a tiny red dot on its thumbnail). */
    val summarized: Boolean = false,
)

/** Everything the detail screen shows for one screenshot. */
data class ShotDetail(
    val shot: Shot,
    val text: String,
    val entities: List<Entity>,
    val note: String,
    val collections: List<ShotCollection>,
    val summary: String? = null,
    val tags: List<String> = emptyList(),
    /** For browser screenshots: the page that was open. */
    val pageUrl: String? = null,
    /** How the source app was found: usage, file, user, visual, model or guess. */
    val appSource: String? = null,
    /** For guessed apps: how sure the guess is (0–1). */
    val appConfidence: Float? = null,
) {
    /** The app is a guess rather than known for sure. */
    val appGuessed: Boolean get() = appSource == null || appSource == "guess" || appSource == "model" || appSource == "visual"
}

/** Progress of the summary model over the library. */
data class SummaryCounts(val done: Int, val waiting: Int)

/** A screenshot waiting for its summary. */
data class SummaryJob(val id: Long, val uri: Uri, val app: String?, val text: String, val name: String)

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
