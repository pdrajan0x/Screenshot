package com.pdrajan.dot.engine

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp

/**
 * The keywords MobileCLIP may give a picture ("car", "beach", "pizza"), from
 * assets/clip/picture_words.json. Words are precise: [Word.also] holds only other names for the
 * same thing, and look-alikes (car / bike, lake / river) are separate words in one [Group] that
 * compete, so only a clear winner is kept.
 */
class PictureWords(val groups: List<Group>, val background: List<String>, val screenshotBackground: List<String>) {

    data class Word(val word: String, val also: List<String>, val prompts: List<String>)

    /** [kind]: words that search the whole group ("food", "animals"); never stored as keywords. */
    data class Group(val id: String, val kind: List<String>, val multi: Boolean, val words: List<Word>)

    val words: List<Word> = groups.flatMap { it.words }

    private val byName: Map<String, List<String>> = buildMap<String, MutableList<String>> {
        for (g in groups) {
            for (w in g.words) for (name in w.also) getOrPut(name.lowercase()) { mutableListOf() } += w.word
            for (k in g.kind) getOrPut(k.lowercase()) { mutableListOf() } += g.words.map { it.word }
        }
    }

    /**
     * Keywords a search term also stands for: "motorcycle" → bike, "chai" → tea, "food" → every
     * food word. Empty for a keyword itself and for ordinary words.
     */
    fun meanings(term: String): List<String> = byName[term.lowercase()].orEmpty().distinct()

    companion object {
        /** Reads assets/clip/picture_words.json. */
        fun parse(json: String): PictureWords {
            val root = JSONObject(json)
            fun strings(a: JSONArray?) = if (a == null) emptyList() else List(a.length()) { a.getString(it) }
            val groups = root.getJSONArray("groups").let { ga ->
                List(ga.length()) { gi ->
                    val g = ga.getJSONObject(gi)
                    val wa = g.getJSONArray("words")
                    Group(
                        id = g.getString("id"),
                        kind = strings(g.optJSONArray("kind")),
                        multi = g.optBoolean("multi", false),
                        words = List(wa.length()) { wi ->
                            val w = wa.getJSONObject(wi)
                            Word(w.getString("word"), strings(w.optJSONArray("also")), strings(w.getJSONArray("prompts")))
                        },
                    )
                }
            }
            return PictureWords(groups, strings(root.getJSONArray("background")), strings(root.optJSONArray("screenshot_background")))
        }
    }

    /** All prompts, word by word in order, then [background] (and, for screenshots, [screenshotBackground]). */
    fun prompts(forScreenshots: Boolean): List<String> =
        words.flatMap { it.prompts } + background + if (forScreenshots) screenshotBackground else emptyList()
}

/**
 * Picks precise keywords for a picture from its CLIP crop embeddings.
 *
 * A word is kept only when it clearly beats generic "a photo" prompts *and* wins its group by a
 * wide margin (car vs bike vs bus…). The first keyword needs [MIN_LEAD] over the background;
 * any further ones [MIN_LEAD_MORE], so weak extras never ride along. Tuned for MobileCLIP2-S2 on 86
 * labelled photos (tools/model/compare_clip.py: 71 right, 1 wrong, the rest without a keyword), on a
 * plateau of the threshold grid rather than its single best cell; app screens get no keywords.
 *
 * @param embeddings one per prompt, in [PictureWords.prompts] order
 */
class PictureTagger(
    private val vocabulary: PictureWords,
    embeddings: List<FloatArray>,
    private val logitScale: Float,
    forScreenshots: Boolean = false,
) {
    private val wordPrompts: List<List<FloatArray>>
    private val background: List<FloatArray>
    /** Screenshots are mostly app screens: only pictures inside them (a photo, a product) get keywords. */
    private val skipGroups: Set<String> = if (forScreenshots) setOf("text", "screens") else emptySet()

    init {
        var i = 0
        wordPrompts = vocabulary.words.map { w -> List(w.prompts.size) { embeddings[i++] } }
        background = embeddings.subList(i, embeddings.size).toList()
        require(background.isNotEmpty()) { "no background prompts" }
    }

    fun keywords(crops: List<FloatArray>, max: Int = 5): List<String> {
        if (crops.isEmpty()) return emptyList()
        fun best(prompts: List<FloatArray>): Float {
            var b = Float.NEGATIVE_INFINITY
            for (c in crops) for (p in prompts) b = maxOf(b, VectorMath.dot(c, p))
            return b
        }
        val base = best(background)
        val scores = wordPrompts.map(::best)
        val picked = ArrayList<Pair<String, Float>>()
        var index = 0
        for (group in vocabulary.groups) {
            val range = index until index + group.words.size
            index += group.words.size
            if (group.id in skipGroups) continue
            if (group.multi) {
                for (i in range) if (scores[i] - base >= MIN_LEAD_MULTI) picked += vocabulary.words[i].word to scores[i] - base
                continue
            }
            val top = range.maxBy { scores[it] }
            // Softmax within the group: the winner's share of the group.
            var sum = 0.0
            for (i in range) sum += exp(((scores[i] - scores[top]) * logitScale).toDouble())
            val share = (1.0 / sum).toFloat()
            if (share >= MIN_SHARE && scores[top] - base >= MIN_LEAD) picked += vocabulary.words[top].word to scores[top] - base
        }
        picked.sortByDescending { it.second }
        return picked.filterIndexed { i, (_, lead) -> i == 0 || lead >= MIN_LEAD_MORE }.take(max).map { it.first }
    }

    companion object {
        const val MIN_LEAD = 0.01f
        const val MIN_LEAD_MORE = 0.035f
        const val MIN_LEAD_MULTI = 0.05f
        const val MIN_SHARE = 0.7f
    }
}
