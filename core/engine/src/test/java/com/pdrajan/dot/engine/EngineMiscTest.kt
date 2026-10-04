package com.pdrajan.dot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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

class SearchTest {
    @Test
    fun ftsQueryUsesPrefixOnLastTerm() {
        assertEquals("movie tick*", FtsQuery.build("Movie Tick"))
        assertEquals("नमस्ते*", FtsQuery.build("नमस्ते"))
        assertNull(FtsQuery.build("  ?? "))
    }

    @Test
    fun categoryIntent() {
        assertEquals(listOf("movies"), Categories.matchQuery("movies"))
        assertEquals(listOf("payments"), Categories.matchQuery("upi receipt"))
        assertEquals(listOf("chats"), Categories.matchQuery("my chats"))
        assertEquals(listOf("shopping"), Categories.matchQuery("Shopping"))
        assertTrue(Categories.matchQuery("blue car parked near a mountain at sunset").isEmpty())
        // Things inside a category don't pull in the whole category (a cough syrup page for "shoes").
        assertTrue(Categories.matchQuery("shoes").isEmpty())
        assertTrue(Categories.matchQuery("red shoes").isEmpty())
        assertTrue(Categories.matchQuery("shopping shoes").isEmpty())
        assertTrue(Categories.matchQuery("whatsapp").isEmpty())
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
