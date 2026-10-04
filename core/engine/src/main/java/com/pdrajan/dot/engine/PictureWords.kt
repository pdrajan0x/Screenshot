package com.pdrajan.dot.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * Words for things in pictures ("car", "beach", "pizza") and their other names, from
 * assets/words/picture_words.json, so a search finds them by any name: [Word.also] holds other
 * names for the same thing ("motorcycle" → bike), [Group.kind] words name a whole group ("food").
 */
class PictureWords(val groups: List<Group>) {

    data class Word(val word: String, val also: List<String>)

    /** [kind]: words that search the whole group ("food", "animals"). */
    data class Group(val id: String, val kind: List<String>, val words: List<Word>)

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
        /** Reads assets/words/picture_words.json. */
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
                        words = List(wa.length()) { wi ->
                            val w = wa.getJSONObject(wi)
                            Word(w.getString("word"), strings(w.optJSONArray("also")))
                        },
                    )
                }
            }
            return PictureWords(groups)
        }
    }
}
