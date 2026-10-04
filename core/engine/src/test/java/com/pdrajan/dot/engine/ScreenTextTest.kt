package com.pdrajan.dot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTextTest {

    @Test
    fun findsAddressBarLink() {
        val lines = listOf(
            OcrLine("12:12", 0.01f, 0.03f),
            OcrLine("🔒 nothing.community/d/61761-nothing-os-50-open-beta ⋮", 0.05f, 0.08f),
            OcrLine("NOS 5.0 Open Beta Based on Android 17", 0.3f, 0.33f),
        )
        assertEquals("https://nothing.community/d/61761-nothing-os-50-open-beta", PageLink.find(lines, browser = true))
        // Not a browser: only explicit links near the top count.
        assertNull(PageLink.find(lines, browser = false))
        assertNull(PageLink.find(listOf(OcrLine("Paid to rahul@okhdfcbank", 0.05f, 0.08f)), browser = true))
        assertNull(PageLink.find(listOf(OcrLine("version 5.0.1", 0.05f, 0.08f)), browser = true))
    }

    @Test
    fun browserPagesComeFromTheAddressBar() {
        assertTrue(Browsers.isBrowser("Chrome"))
        assertTrue(Browsers.isBrowser(null, "org.mozilla.firefox"))
        assertEquals("https://google.com/search?q=8gb+ram+laptop", PageLink.inText("11:15\ngoogle.com/search?q=8gb+ram+laptop\nGoogle\n8gb ram laptop"))
        assertEquals(listOf("chats"), Categories.forApp("WhatsApp"))
    }

    @Test
    fun headlinesAreTheBigText() {
        val lines = listOf(
            OcrLine("5:08", 0.01f, 0.04f),
            OcrLine("Red-Themed The Mentalist Episodes List", 0.20f, 0.26f),
            OcrLine("Oct 03, 2026 · 07:38 AM", 0.29f, 0.31f),
            OcrLine("A Reddit post in r/TheMentalist lists episodes", 0.40f, 0.42f),
            OcrLine("from seasons 4-6 that feature the word Red", 0.43f, 0.45f),
            OcrLine("Notes", 0.70f, 0.72f),
        )
        assertEquals("Red-Themed The Mentalist Episodes List", Headline.from(lines))
        // All the same size: no headline.
        assertEquals("", Headline.from(lines.drop(2)))
    }
}
