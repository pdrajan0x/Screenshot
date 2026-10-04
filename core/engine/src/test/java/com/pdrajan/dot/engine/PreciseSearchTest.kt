package com.pdrajan.dot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PreciseSearchTest {

    private val words: PictureWords by lazy { PictureWords.parse(File(System.getProperty("clip.pictureWords")!!).readText()) }

    @Test
    fun wholeWordSearchFindsPluralsNotLongerWords() {
        assertEquals("car OR cars", FtsQuery.words("car"))
        assertEquals("cars OR car bike OR bikes", FtsQuery.words("Cars bike"))
        assertEquals(listOf("berries", "berry"), FtsQuery.forms("berries"))
        assertEquals(listOf("box", "boxes"), FtsQuery.forms("box"))
        assertEquals(listOf("glass", "glasses"), FtsQuery.forms("glass"))
        assertEquals(listOf("2026"), FtsQuery.forms("2026"))
        assertEquals(2, FtsQuery.wordHits("A red car next to two cars", FtsQuery.terms("car")))
        assertEquals(0, FtsQuery.wordHits("Oscar rides a motorbike past the cart", FtsQuery.terms("car")))
    }

    @Test
    fun fillerWordsAreDroppedAndSynonymsPointToKeywords() {
        val t = PreciseSearch.terms("show me photos of a motorcycle on the beach", words)
        assertEquals(listOf("motorcycle", "beach"), t.map { it.word })
        assertTrue("bike" in t[0].alternatives)
        assertEquals("motorcycle OR motorcycles OR bike beach OR beaches", PreciseSearch.ftsMatch(t))
        // A multi-word keyword is matched as a phrase; a word on its own stays itself.
        assertEquals("auto OR autos OR \"auto rickshaw\"", PreciseSearch.ftsMatch(PreciseSearch.terms("auto", words)))
        assertEquals(listOf("car", "cars"), PreciseSearch.terms("car", words).single().alternatives)
        // Only filler: keep it rather than search for nothing.
        assertEquals(listOf("photos"), PreciseSearch.terms("photos").map { it.word })
        assertNull(PreciseSearch.ftsMatch(emptyList()))
    }

    @Test
    fun keywordsAreNeverBroad() {
        // Words are never stored as a group ("food", "animal"); searching one finds the group's words.
        val all = words.words.map { it.word }.toSet()
        listOf("food", "animal", "animals", "vehicle").forEach { assertTrue(it !in all) }
        assertTrue(words.meanings("food").containsAll(listOf("pizza", "biryani", "dosa")))
        // Look-alikes are separate words, never synonyms of each other.
        assertTrue(words.meanings("lion").isEmpty() && "lion" in all && "tiger" in all)
        assertTrue("lake" in all && "river" in all && "lake" !in words.meanings("river"))
        assertTrue(words.meanings("bike").isEmpty())
        assertEquals(listOf("bike"), words.meanings("scooty"))
        val prompts = words.prompts(forScreenshots = false)
        assertEquals(words.words.sumOf { it.prompts.size } + words.background.size, prompts.size)
    }

    @Test
    fun strongMatchesComeFirstAndWeakOnesAreKeptApart() {
        val terms = PreciseSearch.terms("zomato")
        val docs = listOf(
            PreciseSearch.SearchDoc(1, strong = "Zomato", body = "Order placed · Biryani", takenAt = 10),
            PreciseSearch.SearchDoc(2, strong = "WhatsApp Rahul", body = "lets order from zomato tonight", takenAt = 30),
            PreciseSearch.SearchDoc(3, strong = "Zomato Gold", body = "zomato zomato", takenAt = 20),
            PreciseSearch.SearchDoc(4, strong = "Chrome", body = "zomatoes are not tomatoes", takenAt = 40),
        )
        val r = PreciseSearch.rank(terms, docs)
        assertEquals(listOf(3L, 1L), r.best)
        assertEquals(listOf(2L), r.more)
    }

    @Test
    fun everyWordMustMatchAndPhrasesCountMore() {
        val terms = PreciseSearch.terms("red john", words)
        val docs = listOf(
            PreciseSearch.SearchDoc(1, strong = "", body = "6x08 - Red John\n5x22 - Red John's Rules"),
            PreciseSearch.SearchDoc(2, strong = "", body = "Red Dawn\nJohn Wick"),
            PreciseSearch.SearchDoc(3, strong = "", body = "The Red Barn"),
        )
        val r = PreciseSearch.rank(terms, docs)
        assertEquals(listOf(1L), r.best)
        assertEquals(listOf(2L), r.more)
        // A picture keyword found through its other name.
        val bike = PreciseSearch.rank(
            PreciseSearch.terms("motorcycle", words),
            listOf(PreciseSearch.SearchDoc(7, strong = "bike street", body = ""), PreciseSearch.SearchDoc(8, strong = "car", body = "")),
        )
        assertEquals(listOf(7L), bike.all)
    }

    @Test
    fun taggerKeepsOnlyClearWinners() {
        // Two-word vocabulary in one group plus a background prompt, in a 3-d toy space.
        val vocab = PictureWords(
            listOf(PictureWords.Group("vehicles", emptyList(), false, listOf(
                PictureWords.Word("car", emptyList(), listOf("a car")),
                PictureWords.Word("bike", emptyList(), listOf("a bike")),
            ))),
            background = listOf("a photo"),
            screenshotBackground = emptyList(),
        )
        fun v(x: Float, y: Float, z: Float) = VectorMath.l2Normalize(floatArrayOf(x, y, z))
        val car = v(1f, 0f, 0.2f)
        val bike = v(0f, 1f, 0.2f)
        val photo = v(0f, 0f, 1f)
        val tagger = PictureTagger(vocab, listOf(car, bike, photo), logitScale = 64f)
        assertEquals(listOf("car"), tagger.keywords(listOf(v(1f, 0.1f, 0.4f))))
        // Halfway between car and bike: neither is a clear winner, so no keyword at all.
        assertEquals(emptyList<String>(), tagger.keywords(listOf(v(1f, 1f, 0.4f))))
        // Closer to "a photo" than to any word: nothing.
        assertEquals(emptyList<String>(), tagger.keywords(listOf(v(0.1f, 0f, 1f))))
    }
}
