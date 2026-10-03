package com.pdrajan.dot.engine

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** A half-open time range [startMillis, endMillis) a search query refers to. */
data class DateRange(val startMillis: Long, val endMillis: Long, val label: String)

/**
 * Understands date words in gallery searches: "2024", "june", "june 2023", "yesterday",
 * "last week", "this month", "last year", "diwali 2025" isn't a date (left to CLIP/text).
 * Returns the range and the query with the date words removed.
 */
class DateQueryParser(private val zone: ZoneId = ZoneId.systemDefault(), private val today: () -> LocalDate = { LocalDate.now(zone) }) {

    data class Parsed(val range: DateRange?, val rest: String)

    fun parse(query: String): Parsed {
        val q = query.trim().lowercase()
        val now = today()
        fun range(from: LocalDate, toExclusive: LocalDate, label: String) =
            DateRange(from.atStartOfDay(zone).toInstant().toEpochMilli(), toExclusive.atStartOfDay(zone).toInstant().toEpochMilli(), label)

        RELATIVE.forEach { (phrase, make) ->
            if (Regex("\\b$phrase\\b").containsMatchIn(q)) {
                val (from, to, label) = make(now)
                return Parsed(range(from, to, label), q.replace(Regex("\\b$phrase\\b"), " ").squash())
            }
        }

        val monthMatch = MONTH_YEAR.find(q)
        if (monthMatch != null) {
            val month = MONTHS[monthMatch.groupValues[1].take(3)]!!
            val year = monthMatch.groupValues[2].toIntOrNull()
                ?: if (month.value > now.monthValue) now.year - 1 else now.year
            val from = LocalDate.of(year, month, 1)
            return Parsed(range(from, from.plusMonths(1), "${month.name.lowercase().replaceFirstChar { it.uppercase() }} $year"), q.replace(monthMatch.value, " ").squash())
        }

        val yearMatch = YEAR.find(q)
        if (yearMatch != null) {
            val year = yearMatch.value.toInt()
            if (year in 1990..now.year + 1) {
                val from = LocalDate.of(year, 1, 1)
                return Parsed(range(from, from.plusYears(1), "$year"), q.replace(yearMatch.value, " ").squash())
            }
        }
        return Parsed(null, q)
    }

    private fun String.squash() = replace(Regex("\\s+"), " ").replace(Regex("\\b(from|in|on|of)\\s*$"), "").trim()

    private companion object {
        val MONTHS = mapOf(
            "jan" to Month.JANUARY, "feb" to Month.FEBRUARY, "mar" to Month.MARCH, "apr" to Month.APRIL,
            "may" to Month.MAY, "jun" to Month.JUNE, "jul" to Month.JULY, "aug" to Month.AUGUST,
            "sep" to Month.SEPTEMBER, "oct" to Month.OCTOBER, "nov" to Month.NOVEMBER, "dec" to Month.DECEMBER,
        )
        val MONTH_YEAR = Regex(
            "\\b(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\b(?:\\s+(\\d{4}))?",
        )
        val YEAR = Regex("\\b(19|20)\\d{2}\\b")

        val RELATIVE: List<Pair<String, (LocalDate) -> Triple<LocalDate, LocalDate, String>>> = listOf(
            "today" to { d -> Triple(d, d.plusDays(1), "Today") },
            "yesterday" to { d -> Triple(d.minusDays(1), d, "Yesterday") },
            "this week" to { d -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { Triple(it, it.plusWeeks(1), "This week") } },
            "last week" to { d -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1).let { Triple(it, it.plusWeeks(1), "Last week") } },
            "this month" to { d -> d.withDayOfMonth(1).let { Triple(it, it.plusMonths(1), "This month") } },
            "last month" to { d -> d.withDayOfMonth(1).minusMonths(1).let { Triple(it, it.plusMonths(1), "Last month") } },
            "this year" to { d -> d.withDayOfYear(1).let { Triple(it, it.plusYears(1), "This year") } },
            "last year" to { d -> d.withDayOfYear(1).minusYears(1).let { Triple(it, it.plusYears(1), "Last year") } },
        )
    }
}

/**
 * Zero-shot CLIP tags for photos. They only decide which photos are worth reading text from (OCR
 * costs battery); search uses the AI's descriptions and keywords instead.
 */
data class PhotoTag(val id: String, val prompts: List<String>)

object PhotoTags {
    val ALL = listOf(
        PhotoTag("food", listOf("a photo of food", "a plate of food", "a dish at a restaurant")),
        PhotoTag("dog", listOf("a photo of a dog", "a puppy")),
        PhotoTag("cat", listOf("a photo of a cat", "a kitten")),
        PhotoTag("selfie", listOf("a selfie", "a close-up photo of a person's face")),
        PhotoTag("group", listOf("a group photo of people", "friends posing together")),
        PhotoTag("car", listOf("a photo of a car", "a motorbike")),
        PhotoTag("mountain", listOf("a photo of mountains", "a hill landscape")),
        PhotoTag("beach", listOf("a photo of a beach", "the sea and sand")),
        PhotoTag("sunset", listOf("a photo of a sunset", "a sunrise sky")),
        PhotoTag("flower", listOf("a photo of flowers", "a garden with plants")),
        PhotoTag("city", listOf("a city street", "buildings and skyline")),
        PhotoTag("temple", listOf("a temple", "a historic monument")),
        PhotoTag("celebration", listOf("a birthday cake with candles", "a festival celebration with lights", "a wedding")),
        PhotoTag("document", listOf("a photo of a document", "a page of text", "an ID card")),
        PhotoTag("receipt", listOf("a printed receipt", "a bill")),
        PhotoTag("screenshot", listOf("a screenshot of a phone screen", "a screenshot of an app")),
        PhotoTag("meme", listOf("a meme with text over a picture")),
        PhotoTag("sky", listOf("the night sky with stars", "the moon at night")),
    )

    val BACKGROUND = listOf("a photo", "a blurry photo", "an indoor photo of a room", "an object on a table")

    /** Tags whose photos are worth running OCR on. */
    val TEXT_HEAVY = setOf("document", "receipt", "screenshot", "meme")
}

/** Assigns [PhotoTags] from crop embeddings via a softmax over tag + background prompts. */
class PhotoTagger(
    private val tagPrompts: Map<String, List<FloatArray>>,
    private val background: List<FloatArray>,
    private val logitScale: Float,
) {
    fun tags(cropEmbeddings: List<FloatArray>, minProbability: Float = 0.35f, max: Int = 3): List<String> {
        if (cropEmbeddings.isEmpty()) return emptyList()
        fun best(prompts: List<FloatArray>): Float {
            var b = Float.NEGATIVE_INFINITY
            for (c in cropEmbeddings) for (p in prompts) b = maxOf(b, VectorMath.dot(c, p))
            return b
        }
        val logits = tagPrompts.mapValues { (_, p) -> best(p) * logitScale }
        val bg = best(background) * logitScale
        val maxLogit = maxOf(logits.values.maxOrNull() ?: bg, bg)
        var sum = kotlin.math.exp((bg - maxLogit).toDouble())
        val exps = logits.mapValues { (_, l) -> kotlin.math.exp((l - maxLogit).toDouble()).also { sum += it } }
        return exps.mapValues { (_, e) -> (e / sum).toFloat() }
            .filter { it.value >= minProbability }
            .entries.sortedByDescending { it.value }
            .take(max)
            .map { it.key }
    }
}

/** Perceptual hashing, blur scoring and duplicate grouping on grayscale pixel arrays. */
object ImageQuality {

    /** ARGB → 0..255 luma. */
    fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xff
        val g = (argb shr 8) and 0xff
        val b = argb and 0xff
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /**
     * dHash of a 9×8 grayscale thumbnail (row-major, 72 values): bit set when a pixel is brighter
     * than its right neighbour. Robust to scaling and recompression.
     */
    fun dHash(gray9x8: IntArray): Long {
        require(gray9x8.size == 72)
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                if (gray9x8[y * 9 + x] > gray9x8[y * 9 + x + 1]) hash = hash or (1L shl bit)
                bit++
            }
        }
        return hash
    }

    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** Variance of the Laplacian; low values mean a blurry image. */
    fun sharpness(gray: IntArray, width: Int, height: Int): Double {
        if (width < 3 || height < 3) return 0.0
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val lap = 4 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - width] - gray[i + width]
                sum += lap
                sumSq += lap.toDouble() * lap
                n++
            }
        }
        val mean = sum / n
        return sumSq / n - mean * mean
    }

    data class Candidate(
        val id: Long,
        val hash: Long,
        val takenAt: Long,
        val pixels: Long,
        val sizeBytes: Long,
        val sharpness: Double = 0.0,
        val favorite: Boolean = false,
        /** Width / height; 0 when unknown. A crop is a different photo, not a copy. */
        val aspect: Float = 0f,
    )

    /** The copy to keep first: a favourite, then the most pixels, the sharpest, the largest file. */
    val BEST_FIRST: Comparator<Candidate> = compareByDescending<Candidate> { it.favorite }
        .thenByDescending { it.pixels }
        .thenByDescending { it.sharpness }
        .thenByDescending { it.sizeBytes }

    /**
     * Groups near-identical photos (re-saves, forwards, burst shots): dHash within [maxDistance]
     * bits, taken within [windowMillis], and [same] (a look-alike check) agrees. Every photo in a
     * group is a near copy of the group's first one, the best (see [BEST_FIRST]), which is the one to
     * keep: groups never chain A~B~C into A and C, which may look nothing alike.
     */
    fun duplicateGroups(
        items: List<Candidate>,
        maxDistance: Int = 4,
        windowMillis: Long = Long.MAX_VALUE,
        same: (Candidate, Candidate) -> Boolean = { _, _ -> true },
    ): List<List<Candidate>> {
        val sorted = items.sortedBy { it.takenAt }
        val near = Array(sorted.size) { HashSet<Int>() }
        // Pigeonhole: hashes within maxDistance bits agree exactly on at least one of
        // maxDistance + 1 bands, so only items sharing a band value are compared.
        val bands = maxDistance.coerceIn(0, 63) + 1
        val width = 64 / bands
        for (b in 0 until bands) {
            val shift = b * width
            val bits = if (b == bands - 1) 64 - shift else width
            val mask = if (bits == 64) -1L else (1L shl bits) - 1
            val buckets = HashMap<Long, MutableList<Int>>()
            for (i in sorted.indices) buckets.getOrPut((sorted[i].hash ushr shift) and mask) { ArrayList() }.add(i)
            for (bucket in buckets.values) {
                for (x in 0 until bucket.size - 1) {
                    val i = bucket[x]
                    for (y in x + 1 until bucket.size) {
                        val j = bucket[y]
                        if (windowMillis != Long.MAX_VALUE && sorted[j].takenAt - sorted[i].takenAt > windowMillis) break
                        if (j in near[i] || hamming(sorted[i].hash, sorted[j].hash) > maxDistance) continue
                        if (!sameShape(sorted[i], sorted[j]) || !same(sorted[i], sorted[j])) continue
                        near[i] += j
                        near[j] += i
                    }
                }
            }
        }
        return clusterAroundBest(sorted, near)
    }

    private fun sameShape(a: Candidate, b: Candidate): Boolean =
        a.aspect <= 0f || b.aspect <= 0f || kotlin.math.abs(a.aspect - b.aspect) <= 0.03f * maxOf(a.aspect, b.aspect)

    /** Best unassigned photo first; its group is the unassigned photos near it. */
    internal fun clusterAroundBest(sorted: List<Candidate>, near: Array<out Set<Int>>): List<List<Candidate>> {
        val assigned = BooleanArray(sorted.size)
        val order = sorted.indices.filter { near[it].isNotEmpty() }.sortedWith { a, b -> BEST_FIRST.compare(sorted[a], sorted[b]) }
        val groups = ArrayList<List<Candidate>>()
        for (i in order) {
            if (assigned[i]) continue
            val members = near[i].filter { !assigned[it] }
            if (members.isEmpty()) continue
            assigned[i] = true
            members.forEach { assigned[it] = true }
            groups += listOf(sorted[i]) + members.map { sorted[it] }.sortedWith(BEST_FIRST)
        }
        return groups.sortedByDescending { g -> g.maxOf { it.takenAt } }
    }
}
