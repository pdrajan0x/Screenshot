package com.pdrajan.dot.design

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Google Photos–style section headers: Today, Yesterday, "Mon, 28 Sep", "28 Sep 2025". */
object DateLabels {
    private val sameYear = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())
    private val otherYear = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
    private val month = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
    private val full = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

    fun localDate(millis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    fun day(millis: Long, today: LocalDate = LocalDate.now()): String {
        val d = localDate(millis)
        return when {
            d == today -> "Today"
            d == today.minusDays(1) -> "Yesterday"
            d.year == today.year -> sameYear.format(d)
            else -> otherYear.format(d)
        }
    }

    fun month(millis: Long): String = month.format(localDate(millis))

    fun dateTime(millis: Long): String =
        full.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
}
