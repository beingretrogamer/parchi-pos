package com.parchipos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class SummaryTest {

    private val day: Long = Calendar.getInstance().apply { clear(); set(2026, Calendar.NOVEMBER, 27, 13, 0) }.timeInMillis

    private fun sale(token: Int, vararg lines: SaleLine, payment: String = "CASH", voided: Boolean = false, reason: String = "") =
        Sale(id = "b$token", token = token, time = day + token * 60_000L, lines = lines.toList(),
            payment = payment, customer = "", voided = voided, voidReason = reason)

    private val sales = listOf(
        sale(1, SaleLine("Butter Roti", 15.0, 4), SaleLine("Dal Makhani", 279.0, 1)),
        sale(2, SaleLine("Paneer Tikka", 375.0, 2), voided = true, reason = "Customer left"),
        sale(3, SaleLine("Butter Roti", 15.0, 2), payment = "UPI"),
    )

    @Test fun voidedBillsAreLeftOutOfItemTotals() {
        val items = Summary.itemTotals(sales).toMap()
        assertEquals(6, items["Butter Roti"])
        assertEquals(1, items["Dal Makhani"])
        assertFalse("voided item must not be counted", items.containsKey("Paneer Tikka"))
    }

    @Test fun sharedTextNamesTheDateAndKeepsVoidsApart() {
        val text = Summary.text("Punjabi Rasoi", day, sales, emptyList())
        println(text)
        assertTrue(text.startsWith("Punjabi Rasoi — Fri, 27 Nov 2026\n"))
        assertFalse(text.contains("Today"))
        assertTrue(text.contains("Total: ₹369 (2 bills)"))
        assertTrue(text.contains("Cash: ₹339"))
        assertTrue(text.contains("UPI: ₹30"))
        val itemsPart = text.substringAfter("Items sold:").substringBefore("Voided")
        assertFalse(itemsPart.contains("Paneer Tikka"))
        assertTrue(text.contains("Voided (not counted): 1 bill, ₹750"))
        assertTrue(text.contains("#2 ₹750 — Customer left"))
    }
}
