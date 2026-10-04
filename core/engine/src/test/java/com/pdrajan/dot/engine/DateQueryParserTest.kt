package com.pdrajan.dot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class DateQueryParserTest {
    private val parser = DateQueryParser(ZoneOffset.UTC) { LocalDate.of(2026, 10, 3) }
    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test
    fun yearAndMonth() {
        val p = parser.parse("beach photos 2024")
        assertEquals(day(2024, 1, 1), p.range!!.startMillis)
        assertEquals(day(2025, 1, 1), p.range!!.endMillis)
        assertEquals("beach photos", p.rest)

        val june = parser.parse("dogs in june 2023")
        assertEquals(day(2023, 6, 1), june.range!!.startMillis)
        assertEquals("dogs", june.rest)
    }

    @Test
    fun monthWithoutYearMeansMostRecent() {
        assertEquals(day(2025, 12, 1), parser.parse("december").range!!.startMillis)
        assertEquals(day(2026, 8, 1), parser.parse("aug").range!!.startMillis)
    }

    @Test
    fun relative() {
        val p = parser.parse("food last week")
        assertEquals(day(2026, 9, 21), p.range!!.startMillis) // 3 Oct 2026 is a Saturday
        assertEquals(day(2026, 9, 28), p.range!!.endMillis)
        assertEquals("food", p.rest)
        assertEquals(day(2026, 10, 2), parser.parse("yesterday").range!!.startMillis)
    }

    @Test
    fun noDate() {
        val p = parser.parse("red car")
        assertNull(p.range)
        assertEquals("red car", p.rest)
    }
}
