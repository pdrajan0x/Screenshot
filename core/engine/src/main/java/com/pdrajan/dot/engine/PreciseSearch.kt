package com.pdrajan.dot.engine

/**
 * Search that returns the few right items instead of everything that mentions a word.
 *
 * - Every word of the query must match: as a whole word, its plural, or a picture keyword it
 *   stands for ("motorcycle" finds photos tagged bike). Filler words ("show my photos of…") are
 *   dropped first.
 * - Where it matches decides how much it counts: a picture keyword, the app, a heading or the
 *   user's note ([SearchDoc.strong]) far more than a word somewhere in the screen text.
 * - Results close to the best one come first; the rest are kept apart as weaker matches.
 */
object PreciseSearch {

    /** One query word and everything it may match. Multi-word entries ("ice cream") match as a phrase. */
    data class Term(val word: String, val alternatives: List<String>)

    /** Candidate text, split by how much a match there counts. */
    class SearchDoc(val id: Long, val strong: String, val body: String, val takenAt: Long = 0L)

    data class Ranked(val best: List<Long>, val more: List<Long>) {
        val isEmpty: Boolean get() = best.isEmpty() && more.isEmpty()
        val all: List<Long> get() = best + more
    }

    private val FILLER = setOf(
        "a", "an", "the", "of", "on", "in", "at", "with", "and", "or", "my", "me", "i", "to", "for", "from",
        "show", "find", "search", "all", "some", "any", "that", "this", "which", "where", "when", "is", "are",
        "was", "were", "photo", "photos", "pic", "pics", "picture", "pictures", "image", "images",
    )

    /** The words that matter, each with its plural and the keywords it stands for. */
    fun terms(query: String, words: PictureWords? = null): List<Term> {
        val all = FtsQuery.terms(query)
        val kept = all.filter { it !in FILLER }.ifEmpty { all }
        return kept.distinct().map { w -> Term(w, (FtsQuery.forms(w) + words?.meanings(w).orEmpty()).distinct()) }
    }

    /** FTS4 MATCH expression requiring every term (any of its alternatives); null when there are none. */
    fun ftsMatch(terms: List<Term>): String? {
        if (terms.isEmpty()) return null
        return terms.joinToString(" ") { t ->
            t.alternatives.joinToString(" OR ") { a -> if (a.contains(' ')) "\"${a.replace("\"", "")}\"" else a.replace("\"", "") }
        }
    }

    /**
     * Scores [docs] (usually the FTS candidates) and splits them: [Ranked.best] holds those scoring
     * at least [keep] of the top score, best first, newer first among equals.
     */
    fun rank(terms: List<Term>, docs: List<SearchDoc>, keep: Float = KEEP): Ranked {
        if (terms.isEmpty() || docs.isEmpty()) return Ranked(emptyList(), emptyList())
        val phrase = if (terms.size >= 2) terms.joinToString(" ") { it.word } else null
        val scored = docs.mapNotNull { d ->
            val strong = normalize(d.strong)
            val body = normalize(d.body)
            var score = 0f
            for (t in terms) {
                val s = t.alternatives.maxOf { a -> if (has(strong, a)) STRONG else 0f }
                val b = t.alternatives.maxOf { a -> count(body, a) }
                val termScore = maxOf(s, if (b > 0) BODY + REPEAT * (minOf(b, 4) - 1) else 0f)
                if (termScore == 0f) return@mapNotNull null
                score += termScore
            }
            if (phrase != null && (has(strong, phrase) || has(body, phrase))) score += PHRASE
            d to score
        }
        if (scored.isEmpty()) return Ranked(emptyList(), emptyList())
        val sorted = scored.sortedWith(compareByDescending<Pair<SearchDoc, Float>> { it.second }.thenByDescending { it.first.takenAt })
        val bar = sorted.first().second * keep
        val (best, more) = sorted.partition { it.second >= bar }
        return Ranked(best.map { it.first.id }, more.map { it.first.id })
    }

    private const val STRONG = 3f
    private const val BODY = 1f
    private const val REPEAT = 0.2f
    private const val PHRASE = 1.5f
    const val KEEP = 0.6f

    private val NON_WORD = Regex("[^\\p{L}\\p{M}\\p{N}]+")

    private fun normalize(s: String) = " " + s.lowercase().replace(NON_WORD, " ").trim() + " "

    private fun has(text: String, phrase: String) = text.contains(" ${normalize(phrase).trim()} ")

    private fun count(text: String, phrase: String): Int {
        val needle = " ${normalize(phrase).trim()} "
        var n = 0
        var from = 0
        while (true) {
            val i = text.indexOf(needle, from)
            if (i < 0) return n
            n++
            from = i + needle.length - 1
        }
    }
}
