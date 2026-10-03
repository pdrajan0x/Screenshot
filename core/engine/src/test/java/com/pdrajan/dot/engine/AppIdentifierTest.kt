package com.pdrajan.dot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIdentifierTest {

    // Prompts and screens are made of orthogonal unit vectors so every similarity is exact.
    private val dim = 64
    private var nextAxis = 0
    private fun axis(): FloatArray = FloatArray(dim).also { it[nextAxis++] = 1f }
    private fun mix(vararg parts: Pair<FloatArray, Float>): FloatArray =
        VectorMath.l2Normalize(FloatArray(dim) { i -> parts.sumOf { (v, w) -> (v[i] * w).toDouble() }.toFloat() })

    private val apps = listOf(
        AppCandidate("WhatsApp", "com.whatsapp", AppPrompts.forApp("WhatsApp", "com.whatsapp")),
        AppCandidate("Google Pay", "com.google.android.apps.nbu.paisa.user", AppPrompts.forApp("Google Pay", null)),
        AppCandidate("Swiggy", "in.swiggy.android", AppPrompts.forApp("Swiggy", null)),
        AppCandidate("Chrome", "com.android.chrome", AppPrompts.forApp("Chrome", "com.android.chrome")),
    )
    private val promptVectors = apps.flatMap { it.prompts }.distinct().associateWith { axis() }

    private fun identifier(known: List<KnownShot> = emptyList()) = AppIdentifier(apps, promptVectors, 100f, known)

    @Test
    fun decisiveTextWins() {
        val r = identifier().identify("Paid to Rahul\nGoogle transaction ID\nCICAgOj", emptyList(), 0L, null)!!
        assertEquals("Google Pay", r.label)
        assertEquals("com.google.android.apps.nbu.paisa.user", r.packageName)
    }

    @Test
    fun looksLikeTheAppsPromptWithoutAnyText() {
        val look = promptVectors.getValue("a screenshot of the Swiggy app")
        assertEquals("Swiggy", identifier().identify("", listOf(look, look), 0L, null)!!.label)
    }

    @Test
    fun learnsFromTheUsersOwnCertainScreenshots() {
        // A chat screen that faintly resembles a web page: CLIP alone says Chrome, but it looks
        // just like a screenshot the user took in WhatsApp.
        val shot = mix(axis() to 15f, promptVectors.getValue("a screenshot of a web page in a mobile browser") to 1f)
        val known = listOf(KnownShot("WhatsApp", "com.whatsapp", 1_000L, listOf(shot)))
        assertEquals("Chrome", identifier().identify("", listOf(shot), 10_000_000L, null)!!.label)
        assertEquals("WhatsApp", identifier(known).identify("", listOf(shot), 10_000_000L, null)!!.label)
    }

    @Test
    fun screenshotsTakenMinutesApartShareTheirApp() {
        val known = listOf(KnownShot("Swiggy", "in.swiggy.android", 1_000_000L, listOf(axis())))
        val r = identifier(known).identify("Order details", emptyList(), 1_000_000L + 60_000L, null)!!
        assertEquals("Swiggy", r.label)
        // Hours later the burst is over: no reason to prefer it.
        val later = identifier(known).identify("Order details", emptyList(), 1_000_000L + 3 * 3_600_000L, null)!!
        assertTrue(later.confidence < r.confidence)
    }

    @Test
    fun unusedAppsArePenalised() {
        val usage = mapOf("com.whatsapp" to 600.0, "com.android.chrome" to 30.0)
        val r = identifier().identify("", emptyList(), 0L, usage)!!
        assertEquals("WhatsApp", r.label)
    }

    @Test
    fun appsFromPastCertainScreenshotsStayCandidates() {
        val known = listOf(KnownShot("Zomato", null, 0L, listOf(axis())))
        val id = identifier(known)
        assertEquals(apps.size + 1, id.size)
        assertEquals("Zomato", id.identify("zomato gold", emptyList(), 50_000_000L, null)!!.label)
    }

    @Test
    fun alwaysAnswersWithAConfidence() {
        val r = identifier().identify("", emptyList(), 0L, null)!!
        assertTrue(r.confidence in 0f..1f)
        assertNull(AppIdentifier(emptyList(), emptyMap(), 100f, emptyList()).identify("x", emptyList(), 0L, null))
    }

    @Test
    fun canonicalMatchesWholeWordsOnly() {
        assertEquals("Netflix", AppRecognizer.canonical("Netflix"))
        assertEquals("X", AppRecognizer.canonical("X"))
        assertNull(AppRecognizer.canonical("Motorola Notes"))
        assertEquals("WhatsApp", AppRecognizer.canonical("WhatsApp Business"))
        assertEquals("Google Pay", AppRecognizer.canonical("Google Pay (GPay)"))
    }

    @Test
    fun promptsDescribeTheKindOfApp() {
        assertTrue("a screenshot of a web page in a mobile browser" in AppPrompts.forApp("Chrome", "com.android.chrome"))
        assertTrue("a screenshot of a WhatsApp chat" in AppPrompts.forApp("WhatsApp", "com.whatsapp"))
        assertTrue("a screenshot of a UPI payment confirmation" in AppPrompts.forApp("PhonePe", "com.phonepe.app"))
        assertEquals(listOf("a screenshot of the Calculator app"), AppPrompts.forApp("Calculator", "com.google.android.calculator"))
        assertEquals(1, AppPrompts.forApp("Pixel Launcher", "com.google.android.apps.nexuslauncher", isLauncher = true).size)
    }
}
