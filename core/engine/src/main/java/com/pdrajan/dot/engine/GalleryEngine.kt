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

/** Google Photos–style "Things": zero-shot CLIP tags for photos. */
data class PhotoTag(val id: String, val label: String, val prompts: List<String>, val synonyms: List<String> = emptyList())

object PhotoTags {
    val ALL = listOf(
        PhotoTag("food", "Food", listOf("a photo of food", "a plate of food", "a dish at a restaurant"), listOf("food", "dish", "meal", "lunch", "dinner", "breakfast")),
        PhotoTag("dog", "Dogs", listOf("a photo of a dog", "a puppy"), listOf("dog", "dogs", "puppy", "pet", "pets")),
        PhotoTag("cat", "Cats", listOf("a photo of a cat", "a kitten"), listOf("cat", "cats", "kitten", "pet", "pets")),
        PhotoTag("selfie", "Selfies", listOf("a selfie", "a close-up photo of a person's face"), listOf("selfie", "selfies")),
        PhotoTag("group", "Groups", listOf("a group photo of people", "friends posing together"), listOf("group", "friends", "family")),
        PhotoTag("car", "Cars & bikes", listOf("a photo of a car", "a motorbike"), listOf("car", "cars", "bike", "vehicle")),
        PhotoTag("mountain", "Mountains", listOf("a photo of mountains", "a hill landscape"), listOf("mountain", "mountains", "hills", "trek")),
        PhotoTag("beach", "Beaches", listOf("a photo of a beach", "the sea and sand"), listOf("beach", "sea", "ocean")),
        PhotoTag("sunset", "Sunsets", listOf("a photo of a sunset", "a sunrise sky"), listOf("sunset", "sunrise", "sky")),
        PhotoTag("flower", "Flowers", listOf("a photo of flowers", "a garden with plants"), listOf("flower", "flowers", "plant", "garden")),
        PhotoTag("city", "City", listOf("a city street", "buildings and skyline"), listOf("city", "street", "building", "buildings")),
        PhotoTag("temple", "Temples & monuments", listOf("a temple", "a historic monument"), listOf("temple", "monument", "fort", "mandir")),
        PhotoTag("celebration", "Celebrations", listOf("a birthday cake with candles", "a festival celebration with lights", "a wedding"), listOf("birthday", "party", "festival", "wedding", "diwali", "cake")),
        PhotoTag("document", "Documents", listOf("a photo of a document", "a page of text", "an ID card"), listOf("document", "documents", "id", "card", "aadhaar", "pan")),
        PhotoTag("receipt", "Receipts", listOf("a printed receipt", "a bill"), listOf("receipt", "receipts", "bill", "invoice")),
        PhotoTag("screenshot", "Screenshots", listOf("a screenshot of a phone screen", "a screenshot of an app"), listOf("screenshot", "screenshots")),
        PhotoTag("meme", "Memes", listOf("a meme with text over a picture"), listOf("meme", "memes")),
        PhotoTag("sky", "Night sky", listOf("the night sky with stars", "the moon at night"), listOf("night", "stars", "moon")),
    )

    val BACKGROUND = listOf("a photo", "a blurry photo", "an indoor photo of a room", "an object on a table")

    /** Tags whose photos are worth running OCR on. */
    val TEXT_HEAVY = setOf("document", "receipt", "screenshot", "meme")

    fun byId(id: String) = ALL.firstOrNull { it.id == id }

    fun matchQuery(query: String): List<String> {
        val words = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()
        if (words.isEmpty() || words.size > 3) return emptyList()
        return ALL.filter { t -> t.synonyms.any { it in words } }.map { it.id }
    }
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

    data class Candidate(val id: Long, val hash: Long, val takenAt: Long, val pixels: Long, val sizeBytes: Long)

    /**
     * Groups near-identical photos: same dHash within [maxDistance] bits, taken within
     * [windowMillis] of each other (bursts, re-saves, forwards). Each group is sorted best-first
     * (most pixels, then largest file), so the first item is the one to keep.
     */
    fun duplicateGroups(items: List<Candidate>, maxDistance: Int = 5, windowMillis: Long = Long.MAX_VALUE): List<List<Candidate>> {
        val sorted = items.sortedBy { it.takenAt }
        val parent = IntArray(sorted.size) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        for (i in sorted.indices) {
            for (j in i + 1 until sorted.size) {
                if (windowMillis != Long.MAX_VALUE && sorted[j].takenAt - sorted[i].takenAt > windowMillis) break
                if (hamming(sorted[i].hash, sorted[j].hash) <= maxDistance) {
                    parent[find(j)] = find(i)
                }
            }
        }
        return sorted.indices.groupBy { find(it) }.values
            .filter { it.size > 1 }
            .map { idx -> idx.map { sorted[it] }.sortedWith(compareByDescending<Candidate> { it.pixels }.thenByDescending { it.sizeBytes }) }
            .sortedByDescending { g -> g.maxOf { it.takenAt } }
    }
}
