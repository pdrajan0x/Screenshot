package com.pdrajan.dot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryTest {

    @Test
    fun parsesModelOutputWithTrailingSpacesAndHyphenTags() {
        // A Google Pay receipt, with the trailing spaces and hyphenated keywords small models write.
        val out = "Title: Google Pay UPI Transaction  \n" +
            "Summary: ₹499 paid to Rahul Sharma on 3 Oct 2026 via UPI transaction ID 427519836104  \n" +
            "App: Google Pay  \n" +
            "Tags: google-pay, upi-transaction, pay-to-rahul-sharma, oct-2026, split-expense, bank-transfer"
        val s = SummaryParser.parse(out)!!
        assertEquals("Google Pay UPI Transaction", s.title)
        assertEquals("₹499 paid to Rahul Sharma on 3 Oct 2026 via UPI transaction ID 427519836104", s.summary)
        assertEquals("Google Pay", s.app)
        assertEquals(listOf("google pay", "upi transaction", "pay to rahul sharma", "oct 2026", "split expense", "bank transfer"), s.tags)
    }

    @Test
    fun unknownAppBecomesNullAndGarbageIsRejected() {
        val s = SummaryParser.parse("Title: IRCTC PNR Confirmation\nSummary: PNR 4528193760, confirmed.\nApp: unknown\nTags: irctc, pnr")!!
        assertNull(s.app)
        assertNull(SummaryParser.parse("I cannot help with that."))
    }

    @Test
    fun longScreensAreCutAtALineBoundary() {
        val text = (1..400).joinToString("\n") { "line number $it" }
        val clipped = VisionPrompts.clip(text, 200)
        assertTrue(clipped.length <= 200)
        assertTrue(clipped.endsWith(clipped.lines().last()) && clipped.lines().last().startsWith("line number"))
    }

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
    fun recognisesAppsFromLooksAndText() {
        // CLIP's probabilities for the user's real WhatsApp screenshot.
        val whatsappLook = mapOf("WhatsApp" to 0.753f, "Telegram" to 0.233f, "~other" to 0.005f)
        assertEquals("WhatsApp", AppRecognizer.recognize("Deepanshu Arya\nbana di pure ?\nMessage", whatsappLook)?.app)
        // Text alone.
        assertEquals("Google Pay", AppRecognizer.recognize("Paid to Rahul\nGoogle transaction ID\nCICAgOj", null)?.app)
        // Unclear look and no clues: no guess rather than a wrong one.
        val unclear = mapOf("~gallery" to 0.24f, "YouTube" to 0.22f, "~browser" to 0.19f, "~other" to 0.15f)
        assertNull(AppRecognizer.recognize("PHOTOS\nToday\nYesterday", unclear))
        assertEquals("WhatsApp", AppRecognizer.canonical("WhatsApp Messenger"))
        assertTrue(AppRecognizer.isBrowser("Chrome"))
    }
}
