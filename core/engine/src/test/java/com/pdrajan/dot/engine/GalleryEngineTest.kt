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

class ImageQualityTest {
    @Test
    fun dHashIgnoresBrightnessShift() {
        val base = IntArray(72) { (it * 37) % 255 }
        val brighter = IntArray(72) { minOf(255, base[it] + 10) }
        assertTrue(ImageQuality.hamming(ImageQuality.dHash(base), ImageQuality.dHash(brighter)) <= 4)
    }

    @Test
    fun sharpImagesScoreHigher() {
        val w = 32
        val sharp = IntArray(w * w) { i -> if ((i % w / 4 + i / w / 4) % 2 == 0) 0 else 255 }
        val flat = IntArray(w * w) { 128 }
        assertTrue(ImageQuality.sharpness(sharp, w, w) > 1000)
        assertEquals(0.0, ImageQuality.sharpness(flat, w, w), 1e-9)
    }

    @Test
    fun groupsNearDuplicatesBestFirst() {
        val items = listOf(
            ImageQuality.Candidate(1, 0b1010_1010L, 1_000, pixels = 12_000_000, sizeBytes = 3_000_000),
            ImageQuality.Candidate(2, 0b1010_1011L, 2_000, pixels = 1_000_000, sizeBytes = 200_000),
            ImageQuality.Candidate(3, 0x7FFF_0000_FFFFL, 3_000, pixels = 12_000_000, sizeBytes = 3_000_000),
        )
        val groups = ImageQuality.duplicateGroups(items)
        assertEquals(1, groups.size)
        assertEquals(listOf(1L, 2L), groups.single().map { it.id })
    }

    @Test
    fun bucketedGroupingMatchesBruteForce() {
        val rnd = java.util.Random(7)
        val bases = List(40) { rnd.nextLong() }
        val items = List(600) { i ->
            var h = bases[rnd.nextInt(bases.size)]
            repeat(rnd.nextInt(8)) { h = h xor (1L shl rnd.nextInt(64)) }
            ImageQuality.Candidate(i.toLong(), h, rnd.nextInt(100_000).toLong(), pixels = rnd.nextInt(1000).toLong(), sizeBytes = i.toLong())
        }
        for (window in listOf(Long.MAX_VALUE, 5_000L)) {
            val sorted = items.sortedBy { it.takenAt }
            val parent = IntArray(sorted.size) { it }
            fun find(i: Int): Int = if (parent[i] == i) i else find(parent[i]).also { parent[i] = it }
            for (i in sorted.indices) for (j in i + 1 until sorted.size) {
                if (sorted[j].takenAt - sorted[i].takenAt <= window && ImageQuality.hamming(sorted[i].hash, sorted[j].hash) <= 5) {
                    parent[find(j)] = find(i)
                }
            }
            val expected = sorted.indices.groupBy { find(it) }.values.filter { it.size > 1 }.map { g -> g.map { sorted[it].id }.toSet() }.toSet()
            val actual = ImageQuality.duplicateGroups(items, maxDistance = 5, windowMillis = window).map { g -> g.map { it.id }.toSet() }.toSet()
            assertEquals(expected, actual)
        }
    }

    @Test
    fun tagQueryIntent() {
        assertEquals(listOf("dog", "cat"), PhotoTags.matchQuery("pets"))
        assertEquals(listOf("food"), PhotoTags.matchQuery("food"))
    }
}
