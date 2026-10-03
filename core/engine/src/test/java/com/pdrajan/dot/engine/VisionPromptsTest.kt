package com.pdrajan.dot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionPromptsTest {

    @Test
    fun parsesAPhotoAnswer() {
        // Verbatim LFM2.5-VL 1.6B output (with the photo grammar) for a street photo.
        val p = VisionPrompts.parsePhoto(
            "Description: A man in a tan coat and blue jeans stands with his arms crossed on the sidewalk near a blue bus.\n" +
                "Keywords: man, tan coat, blue jeans, arms crossed, sidewalk, blue bus",
        )!!
        assertEquals("A man in a tan coat and blue jeans stands with his arms crossed on the sidewalk near a blue bus.", p.description)
        assertEquals(listOf("man", "tan coat", "blue jeans", "arms crossed", "sidewalk", "blue bus"), p.keywords)
        assertNull(VisionPrompts.parsePhoto("Keywords: a, b"))
        assertEquals("A yellow building.", VisionPrompts.parsePhoto("Description: A yellow building\nKeywords: x")!!.description)
    }

    @Test
    fun keywordsDropTimesAndNumbers() {
        assertEquals(
            listOf("upi", "payment", "rahul sharma", "oct 2026"),
            VisionPrompts.cleanKeywords("upi, payment, 499, rahul sharma, oct 2026, 6.42pm, 10:42, Payment"),
        )
    }

    @Test
    fun screenshotAnswerParsesWithKeywords() {
        val s = SummaryParser.parse(
            "Title: WhatsApp conversation\nSummary: Deepanshu Arya asks about bike service; the bill was ₹1,250.\n" +
                "App: WhatsApp\nKeywords: deepanshu arya, bike service, hero showroom, 1250, message",
        )!!
        assertEquals("WhatsApp", s.app)
        assertEquals(listOf("deepanshu arya", "bike service", "hero showroom", "message"), s.tags)
        val prompt = VisionPrompts.screenshot("Deepanshu Arya\nMessage", "WhatsApp")
        assertTrue(prompt.startsWith("This is a phone screenshot. It was taken in WhatsApp. Text read from it:\nDeepanshu Arya"))
        assertTrue(VisionPrompts.screenshot("", null).startsWith("This is a phone screenshot.\n\nAnswer"))
    }

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
}
