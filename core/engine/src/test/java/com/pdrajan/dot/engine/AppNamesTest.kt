package com.pdrajan.dot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppNamesTest {

    private val apps = listOf("Chrome", "Google", "Google Pay", "WhatsApp Business", "Amazon Shopping", "X", "Maps", "GitHub", "Instagram", "Photos")
        .map { AppNames.Choice(it, "pkg.$it") }

    private fun match(answer: String) = AppNames.match(answer, apps)?.label

    @Test
    fun mapsTheModelsWordingOntoInstalledApps() {
        assertEquals("Chrome", match("Google Chrome"))
        assertEquals("Google Pay", match("GPay"))
        assertEquals("X", match("Twitter"))
        assertEquals("Maps", match("Google Maps"))
        assertEquals("Photos", match("Google Photos"))
        assertEquals("WhatsApp Business", match("WhatsApp"))
        assertEquals("Amazon Shopping", match("Amazon"))
        assertEquals("GitHub", match("github"))
        assertEquals(AppNames.LOCK_SCREEN, match("Lock Screen"))
        assertEquals(AppNames.HOME_SCREEN, match("home screen"))
    }

    @Test
    fun namesThatAreNoInstalledAppGiveNoApp() {
        assertNull(match("Llama.cpp"))
        assertNull(match("Jack & Jones"))
        assertNull(match("Zepto"))
        assertNull(match(""))
        // A short name doesn't swallow longer ones: "Go" is not "Google".
        assertNull(match("Go"))
    }
}
