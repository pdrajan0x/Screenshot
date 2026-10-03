package com.pdrajan.dot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppHintsTest {

    @Test
    fun interfaceWordsNameTheApp() {
        assertEquals("GitHub", AppHints.fromText("ggml-org\nllama.cpp\n98.2k stars 14.9k forks\nIssues 412\nPull requests 530").first())
        assertEquals("LinkedIn", AppHints.fromText("Priya Nair • 2nd\nSenior Android Engineer\nLike Comment Repost Send").first())
        assertEquals(listOf(AppNames.LOCK_SCREEN), AppHints.fromText("82%\nFriday, 3 October\n7:41\nInstagram · now\nrahul_99 liked your photo."))
        assertEquals("Google", AppHints.fromText("google.com/search?q=ram\nAll Shopping Images Videos News\nAmazon.in\nPeople also ask").first())
    }

    @Test
    fun brandsInTheContentAreNoHint() {
        // Amazon results in a Google search, a brand's post in a feed: not the app.
        assertTrue("Amazon" !in AppHints.fromText("Amazon.in · https://www.amazon.in\nBuy 8GB RAM Laptops at Amazon.in"))
        assertTrue(AppHints.fromText("JACK & JONES Slim fit denim jacket\njackjones.in").isEmpty())
    }
}
