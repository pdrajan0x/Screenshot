package com.pdrajan.dot.engine

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** A half-open time range [startMillis, endMillis) a search query refers to. */
data class DateRange(val startMillis: Long, val endMillis: Long, val label: String)

/**
 * Understands date words in searches: "2024", "june", "june 2023", "yesterday",
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
