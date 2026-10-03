package com.pdrajan.dot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class EntityExtractorTest {

    private val extractor = EntityExtractor(ZoneOffset.UTC) { LocalDate.of(2026, 10, 3) }

    private fun of(text: String, type: EntityType) = extractor.extract(text).filter { it.type == type }

    @Test
    fun upiIdsAreNotEmails() {
        val upi = of("Pay to rahul.k@okhdfcbank or 9876543210@ybl", EntityType.UPI)
        assertEquals(listOf("rahul.k@okhdfcbank", "9876543210@ybl"), upi.map { it.value })
        assertTrue(of("Pay to rahul.k@okhdfcbank", EntityType.EMAIL).isEmpty())
        assertEquals(listOf("support@swiggy.in"), of("Mail support@swiggy.in", EntityType.EMAIL).map { it.value })
    }

    @Test
    fun indianPhoneNumbers() {
        val phones = of("Call +91 98765 43210 or 08765-43210? Also 1800 123 4567", EntityType.PHONE).map { it.value }
        assertTrue(phones.contains("+919876543210"))
        assertTrue(phones.contains("18001234567"))
        assertTrue(of("Order total 12345678901234", EntityType.PHONE).isEmpty())
    }

    @Test
    fun amounts() {
        val a = of("Paid ₹1,499.00 to Swiggy. MRP Rs. 2,999 and 450/-", EntityType.AMOUNT).map { it.value }
        assertEquals(listOf("₹1499.00", "₹2999", "₹450"), a)
    }

    @Test
    fun urls() {
        val u = of("Visit https://example.com/a?b=1, or www.irctc.co.in. Also amazon.in/deals", EntityType.URL).map { it.value }
        assertEquals(listOf("https://example.com/a?b=1", "https://www.irctc.co.in", "https://amazon.in/deals"), u)
        assertTrue(of("Mail me at a@b.com", EntityType.URL).isEmpty())
    }

    @Test
    fun otpCodes() {
        assertEquals(listOf("482913"), of("482913 is your OTP? No: Your OTP is 482913. Do not share", EntityType.CODE).map { it.value })
    }

    @Test
    fun datesWithTimes() {
        val d = of("Show on 12 Oct 2026, 7:30 PM at PVR", EntityType.DATE).single()
        assertEquals("2026-10-12T19:30", d.value)
        assertTrue(d.hasTime)

        val noYear = of("Departs Mar 5 at 6 am", EntityType.DATE).single()
        assertEquals("2027-03-05T06:00", noYear.value) // already passed this year → next year

        val numeric = of("Valid till 03/11/2026", EntityType.DATE).single()
        assertEquals("2026-11-03", numeric.value) // day/month order
        assertFalse(numeric.hasTime)

        assertEquals("2026-12-25", of("Date: 2026-12-25", EntityType.DATE).single().value)
    }

    @Test
    fun bareNumbersAreNotTimes() {
        val d = of("Delivered 29 Sep, 5 items", EntityType.DATE).single()
        assertFalse(d.hasTime)
    }
}
