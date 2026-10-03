package com.pdrajan.dot.engine

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId

enum class EntityType { URL, EMAIL, PHONE, UPI, AMOUNT, DATE, CODE }

/**
 * Something actionable found in a screenshot's text.
 * @param value normalised form (digits-only phone, full URL, `name@handle`, …)
 * @param epochMillis for [EntityType.DATE]: the start time, when it could be parsed
 * @param hasTime for [EntityType.DATE]: whether a time of day was found next to the date
 */
data class Entity(
    val type: EntityType,
    val text: String,
    val value: String,
    val epochMillis: Long? = null,
    val hasTime: Boolean = false,
)

/** Rule-based extraction tuned for Indian screenshots (₹, UPI, +91 numbers, dd/mm dates). */
class EntityExtractor(
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val today: () -> LocalDate = { LocalDate.now(zone) },
) {

    fun extract(text: String): List<Entity> {
        if (text.isBlank()) return emptyList()
        val found = ArrayList<Entity>()
        val taken = ArrayList<IntRange>()

        fun claim(range: IntRange): Boolean {
            if (taken.any { it.first <= range.last && range.first <= it.last }) return false
            taken.add(range)
            return true
        }

        EMAIL.findAll(text).forEach { m ->
            if (claim(m.range)) found += Entity(EntityType.EMAIL, m.value, m.value.lowercase())
        }
        UPI.findAll(text).forEach { m ->
            if (claim(m.range)) found += Entity(EntityType.UPI, m.value, m.value)
        }
        URL.findAll(text).forEach { m ->
            val raw = m.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']')
            val range = m.range.first until m.range.first + raw.length
            if (raw.length > 4 && claim(range)) {
                val full = if (raw.startsWith("http", ignoreCase = true)) raw else "https://$raw"
                found += Entity(EntityType.URL, raw, full)
            }
        }
        CODE.findAll(text).forEach { m ->
            val g = m.groups[2]!!
            if (claim(g.range)) found += Entity(EntityType.CODE, g.value, g.value)
        }
        findDates(text).forEach { (range, entity) -> if (claim(range)) found += entity }
        AMOUNT.findAll(text).forEach { m ->
            if (claim(m.range)) found += Entity(EntityType.AMOUNT, m.value.trim(), normalizeAmount(m.value))
        }
        PHONE.findAll(text).forEach { m ->
            val digits = m.value.filter { it.isDigit() || it == '+' }
            val bare = digits.removePrefix("+")
            if (bare.length in 10..13 && claim(m.range)) found += Entity(EntityType.PHONE, m.value.trim(), digits)
        }

        return found.distinctBy { it.type to it.value }
    }

    private fun normalizeAmount(raw: String): String {
        val number = Regex("[0-9][0-9,]*(?:\\.[0-9]{1,2})?").find(raw)?.value?.replace(",", "") ?: return raw.trim()
        val currency = when {
            raw.contains('$') -> "$"
            raw.contains('€') -> "€"
            raw.contains('£') -> "£"
            else -> "₹"
        }
        return currency + number
    }

    private fun findDates(text: String): List<Pair<IntRange, Entity>> {
        val out = ArrayList<Pair<IntRange, Entity>>()
        val now = today()

        fun add(m: MatchResult, date: LocalDate?) {
            if (date == null) return
            val time = TIME.find(text.substring(m.range.last + 1, minOf(text.length, m.range.last + 30)))
            val (localTime, range) = if (time != null) {
                parseTime(time) to (m.range.first..(m.range.last + 1 + time.range.last))
            } else {
                null to m.range
            }
            val dateTime = LocalDateTime.of(date, localTime ?: LocalTime.of(9, 0))
            out += range to Entity(
                type = EntityType.DATE,
                text = text.substring(range),
                value = date.toString() + (localTime?.let { "T$it" } ?: ""),
                epochMillis = dateTime.atZone(zone).toInstant().toEpochMilli(),
                hasTime = localTime != null,
            )
        }

        ISO_DATE.findAll(text).forEach { m ->
            add(m, safeDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()))
        }
        NUMERIC_DATE.findAll(text).forEach { m ->
            val d = m.groupValues[1].toInt()
            val mo = m.groupValues[2].toInt()
            val y = expandYear(m.groupValues[3].toInt())
            // India uses day/month order.
            add(m, safeDate(y, mo, d))
        }
        DAY_MONTH.findAll(text).forEach { m ->
            val month = monthOf(m.groupValues[2]) ?: return@forEach
            add(m, resolve(m.groupValues[1].toInt(), month, m.groupValues[3].toIntOrNull(), now))
        }
        MONTH_DAY.findAll(text).forEach { m ->
            val month = monthOf(m.groupValues[1]) ?: return@forEach
            add(m, resolve(m.groupValues[2].toInt(), month, m.groupValues[3].toIntOrNull(), now))
        }
        return out
    }

    private fun resolve(day: Int, month: Month, year: Int?, now: LocalDate): LocalDate? {
        if (year != null) return safeDate(expandYear(year), month.value, day)
        val thisYear = safeDate(now.year, month.value, day) ?: return null
        // A date without a year that has already passed this year most likely means next year
        // for tickets/events; keep it in this year if it's within the last month (receipts).
        return if (thisYear.isBefore(now.minusDays(31))) thisYear.plusYears(1) else thisYear
    }

    private fun expandYear(y: Int) = if (y < 100) 2000 + y else y

    private fun safeDate(y: Int, m: Int, d: Int): LocalDate? =
        if (y !in 1990..2100 || m !in 1..12 || d !in 1..31) null
        else runCatching { LocalDate.of(y, m, d) }.getOrNull()

    private fun parseTime(m: MatchResult): LocalTime? {
        val g = m.groupValues
        val withMinutes = g[1].isNotEmpty()
        var h = (if (withMinutes) g[1] else g[4]).toInt()
        val min = if (withMinutes) g[2].toInt() else 0
        val ampm = (if (withMinutes) g[3] else g[5]).lowercase()
        if (ampm.startsWith("p") && h < 12) h += 12
        if (ampm.startsWith("a") && h == 12) h = 0
        return if (h in 0..23 && min in 0..59) LocalTime.of(h, min) else null
    }

    private fun monthOf(s: String): Month? = MONTHS[s.lowercase().take(3)]

    companion object {
        private val MONTHS = mapOf(
            "jan" to Month.JANUARY, "feb" to Month.FEBRUARY, "mar" to Month.MARCH, "apr" to Month.APRIL,
            "may" to Month.MAY, "jun" to Month.JUNE, "jul" to Month.JULY, "aug" to Month.AUGUST,
            "sep" to Month.SEPTEMBER, "oct" to Month.OCTOBER, "nov" to Month.NOVEMBER, "dec" to Month.DECEMBER,
        )
        private const val MONTH_RX =
            "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"

        private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}")
        // UPI IDs look like emails without a domain dot: name@okhdfcbank, 98765xxxxx@ybl.
        private val UPI = Regex("(?<![\\w.@])[A-Za-z0-9][A-Za-z0-9._-]{1,63}@[A-Za-z]{2,32}(?![\\w.@-])")
        private val URL = Regex(
            "(?i)(?<![@\\w])(?:https?://|www\\.)[^\\s<>\"'`]+|" +
                "(?<![@\\w./])[a-z0-9][a-z0-9-]{1,62}(?:\\.[a-z0-9-]{1,62})*\\.(?:com|in|org|net|io|co|app|dev|me|ly|gl|tv|xyz|info|edu|gov|ai|shop|store|link)(?:/[^\\s<>\"'`]*)?(?![\\w@])",
        )
        private val CODE = Regex(
            "(?i)\\b(otp|one[- ]time password|verification code|security code|login code|code|pin)\\b\\D{0,20}?(?<!\\d)(\\d{4,8})(?!\\d)",
        )
        private val AMOUNT = Regex(
            "(?i)(?:₹|rs\\.?|inr|\\$|€|£)\\s?[0-9][0-9,]*(?:\\.[0-9]{1,2})?(?:\\s?/-)?|" +
                "(?<![\\w.])[0-9][0-9,]*(?:\\.[0-9]{1,2})?\\s?(?:/-|rupees|inr)(?!\\w)",
        )
        private val PHONE = Regex(
            "(?<![\\w+])(?:\\+?91[\\s-]?)?[6-9][0-9]{4}[\\s-]?[0-9]{5}(?!\\d)|" +
                "(?<![\\w+])1800[\\s-]?[0-9]{3}[\\s-]?[0-9]{3,4}(?!\\d)|" +
                "\\+[1-9][0-9]{0,2}[\\s-]?\\(?[0-9]{2,4}\\)?[\\s-]?[0-9]{3,4}[\\s-]?[0-9]{3,4}(?!\\d)",
        )
        private val ISO_DATE = Regex("(?<!\\d)(\\d{4})-(\\d{2})-(\\d{2})(?!\\d)")
        private val NUMERIC_DATE = Regex("(?<![\\d/.-])(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4}|\\d{2})(?![\\d/.-])")
        private val DAY_MONTH = Regex(
            "(?i)(?<!\\d)(\\d{1,2})(?:st|nd|rd|th)?[\\s-]+$MONTH_RX\\.?(?:[\\s,'-]+(\\d{4}|\\d{2})(?![\\d:]))?(?![a-z])",
        )
        private val MONTH_DAY = Regex(
            "(?i)(?<![a-z])$MONTH_RX\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?(?!\\d)(?:,?\\s+(\\d{4})(?![\\d:]))?",
        )
        // "5:30", "5.30 pm", "17:30" (groups 1-3) or "5 pm" (groups 4-5). A bare "5" is not a time.
        // Never start a set with "[:" — Android's ICU regex reads it as a POSIX class like [:alpha:].
        private val TIME = Regex(
            "(?i)(?<![\\d.:])(\\d{1,2})[.:](\\d{2})(?!\\d)\\s?(am|pm|a\\.m\\.|p\\.m\\.)?|" +
                "(?<![\\d.:])(\\d{1,2})\\s?(am|pm|a\\.m\\.|p\\.m\\.)",
        )
    }
}
