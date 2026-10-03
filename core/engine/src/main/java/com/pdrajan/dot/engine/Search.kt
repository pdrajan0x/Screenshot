package com.pdrajan.dot.engine

/** Why a result matched; shown as a small badge in the UI. */
enum class MatchReason { TEXT, VISUAL, CATEGORY, NOTE }

data class RankedResult(val id: Long, val score: Float, val reasons: Set<MatchReason>)

object FtsQuery {
    private val WORD = Regex("[\\p{L}\\p{M}\\p{N}]+")

    fun terms(query: String): List<String> =
        WORD.findAll(TextNormalizer.basicClean(query).lowercase()).map { it.value }.toList()

    /**
     * SQLite FTS4 MATCH expression: every term must appear, last term as a prefix (search-as-you-type).
     * Returns null when the query has no searchable words.
     */
    fun build(query: String, allTermsPrefix: Boolean = false): String? {
        val t = terms(query)
        if (t.isEmpty()) return null
        return t.mapIndexed { i, term ->
            val prefix = allTermsPrefix || i == t.lastIndex
            if (prefix) "$term*" else term
        }.joinToString(" ")
    }
}

object HybridRanker {
    private const val K = 60f

    /**
     * Reciprocal-rank fusion of the three result lists. Items found by more than one signal rise
     * to the top; within a list, order is preserved.
     *
     * @param visual similarity hits, already filtered with [filterVisual]
     */
    fun merge(
        text: List<Long>,
        visual: List<VectorHit>,
        category: List<Long>,
        notes: List<Long> = emptyList(),
    ): List<RankedResult> {
        val scores = HashMap<Long, Float>()
        val reasons = HashMap<Long, MutableSet<MatchReason>>()
        fun add(ids: List<Long>, weight: Float, reason: MatchReason) {
            ids.forEachIndexed { rank, id ->
                scores[id] = (scores[id] ?: 0f) + weight / (K + rank + 1)
                reasons.getOrPut(id) { mutableSetOf() }.add(reason)
            }
        }
        add(notes, 1.3f, MatchReason.NOTE)
        add(text, 1.2f, MatchReason.TEXT)
        add(category, 1.0f, MatchReason.CATEGORY)
        add(visual.map { it.id }, 1.0f, MatchReason.VISUAL)
        return scores.entries
            .sortedByDescending { it.value }
            .map { (id, s) -> RankedResult(id, s, reasons.getValue(id)) }
    }

    /**
     * CLIP always returns *something*. Keep hits that clear an absolute floor and stay close to
     * the best match, so a query with no real visual match returns nothing visual.
     */
    fun filterVisual(hits: List<VectorHit>, floor: Float = 0.18f, dropFromTop: Float = 0.06f, max: Int = 60): List<VectorHit> {
        val top = hits.firstOrNull()?.score ?: return emptyList()
        if (top < floor) return emptyList()
        return hits.filter { it.score >= floor && it.score >= top - dropFromTop }.take(max)
    }
}

object Snippet {
    /** A short excerpt of [text] around the first query term, for showing why a result matched. */
    fun around(text: String, terms: List<String>, radius: Int = 48): String? {
        if (text.isBlank() || terms.isEmpty()) return null
        val lower = text.lowercase()
        val hit = terms.map { lower.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: return null
        var start = (hit - radius).coerceAtLeast(0)
        var end = (hit + radius).coerceAtMost(text.length)
        // Don't cut words in half at either edge.
        while (start > 0 && !text[start - 1].isWhitespace() && hit - start < radius + 16) start--
        while (end < text.length && !text[end].isWhitespace() && end - hit < radius + 16) end++
        val core = text.substring(start, end).replace(Regex("\\s+"), " ").trim()
        return (if (start > 0) "…" else "") + core + (if (end < text.length) "…" else "")
    }
}
