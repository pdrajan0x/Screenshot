package com.pdrajan.dot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SourceAppTest {
    @Test
    fun samsungAndMotorolaNames() {
        assertEquals("YouTube", SourceApp.fromFileName("Screenshot_20240229_103000_YouTube.jpg")?.label)
        assertEquals("Chrome", SourceApp.fromFileName("Screenshot_20240229-103000_Chrome.png")?.label)
        assertEquals("Samsung Experience Home", SourceApp.fromFileName("Screenshot_20240229_103000_Samsung Experience Home.jpg")?.label)
    }

    @Test
    fun xiaomiPackageNames() {
        val hint = SourceApp.fromFileName("Screenshot_2024-02-29-10-30-00-123_com.google.android.youtube.jpg")
        assertEquals("com.google.android.youtube", hint?.packageName)
        assertEquals("YouTube", SourceApp.labelFromPackage(hint!!.packageName!!))
        assertEquals("Example", SourceApp.labelFromPackage("com.example.android"))
    }

    @Test
    fun pixelNamesHaveNoApp() {
        assertNull(SourceApp.fromFileName("Screenshot_20240229-103000.png"))
        assertNull(SourceApp.fromFileName("IMG_20240229_103000.jpg"))
    }
}

class CropPlannerTest {
    @Test
    fun tallScreenshotGetsThreeCrops() {
        val crops = CropPlanner.squareCrops(1080, 2400)
        assertEquals(listOf(CropRect(0, 0, 1080), CropRect(0, 660, 1080), CropRect(0, 1320, 1080)), crops)
    }

    @Test
    fun squareishGetsCentreCrop() {
        assertEquals(listOf(CropRect(160, 0, 960)), CropPlanner.squareCrops(1280, 960))
    }

    @Test
    fun landscape16x9GetsTwoCrops() {
        assertEquals(listOf(CropRect(0, 0, 1080), CropRect(840, 0, 1080)), CropPlanner.squareCrops(1920, 1080))
    }
}

class VectorIndexTest {
    private fun randomUnit(rng: Random, dim: Int = 512) =
        VectorMath.l2Normalize(FloatArray(dim) { rng.nextFloat() * 2 - 1 })

    @Test
    fun quantizedDotStaysClose() {
        val rng = Random(7)
        repeat(20) {
            val a = randomUnit(rng)
            val b = randomUnit(rng)
            val q = QuantizedVector.fromBlob(QuantizedVector.of(b).toBlob())
            assertEquals(VectorMath.dot(a, b), q.dot(a), 0.01f)
        }
    }

    @Test
    fun bestCropWins() {
        val rng = Random(1)
        val target = randomUnit(rng)
        val index = VectorIndex()
        repeat(200) { i -> index.put(i.toLong(), listOf(QuantizedVector.of(randomUnit(rng)))) }
        index.put(999L, listOf(QuantizedVector.of(randomUnit(rng)), QuantizedVector.of(target)))
        val hits = index.search(target, 5)
        assertEquals(999L, hits.first().id)
        assertTrue(hits.first().score > 0.95f)
        assertEquals(5, hits.size)
        assertTrue(hits.zipWithNext().all { (a, b) -> a.score >= b.score })
    }
}

class SearchTest {
    @Test
    fun ftsQueryUsesPrefixOnLastTerm() {
        assertEquals("movie tick*", FtsQuery.build("Movie Tick"))
        assertEquals("नमस्ते*", FtsQuery.build("नमस्ते"))
        assertNull(FtsQuery.build("  ?? "))
    }

    @Test
    fun itemsFoundTwiceRankFirst() {
        val merged = HybridRanker.merge(
            text = listOf(1L, 2L),
            visual = listOf(VectorHit(3L, 0.3f), VectorHit(2L, 0.29f)),
            category = emptyList(),
        )
        assertEquals(2L, merged.first().id)
        assertEquals(setOf(MatchReason.TEXT, MatchReason.VISUAL), merged.first().reasons)
    }

    @Test
    fun weakVisualMatchesAreDropped() {
        assertTrue(HybridRanker.filterVisual(listOf(VectorHit(1, 0.12f))).isEmpty())
        val kept = HybridRanker.filterVisual(listOf(VectorHit(1, 0.30f), VectorHit(2, 0.27f), VectorHit(3, 0.20f)))
        assertEquals(listOf(1L, 2L), kept.map { it.id })
    }

    @Test
    fun categoryIntent() {
        assertEquals(listOf("movies"), Categories.matchQuery("movies"))
        assertEquals(listOf("payments"), Categories.matchQuery("upi receipt"))
        assertTrue(Categories.matchQuery("blue car parked near a mountain at sunset").isEmpty())
    }

    @Test
    fun shortKeywordsMatchWholeWords() {
        assertTrue(CategoryClassifier.containsKeyword("new ott release", "ott"))
        assertTrue(!CategoryClassifier.containsKeyword("water bottle", "ott"))
        assertTrue(CategoryClassifier.containsKeyword("add to cart now", "add to cart"))
    }

    @Test
    fun snippetAroundMatch() {
        val text = "Your order of Margherita Pizza from Domino's has been delivered. Rate your experience."
        assertEquals("…order of Margherita Pizza from Domino's has been delivered. Rate…", Snippet.around(text, listOf("domino"), 30))
    }
}
