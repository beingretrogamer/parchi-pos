package com.parchipos.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The day's summary shared from Sales, as plain text for WhatsApp.
 *
 * No Android calls, so it can be tested on its own. It always names the date,
 * never "Today": the message is read later, often on another day.
 */
object Summary {

    /** Item-wise quantities from bills that stand, bestseller first. Voided bills never count. */
    fun itemTotals(sales: List<Sale>): List<Pair<String, Int>> =
        sales.filterNot { it.voided }.flatMap { it.lines }.groupBy { it.name }
            .map { (name, lines) -> name to lines.sumOf { it.qty } }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })

    /**
     * [sales] are the day's bills, voided ones included; [receipts] may be all
     * of them, and only those on [dayMillis]'s date are counted.
     */
    fun text(shopName: String, dayMillis: Long, sales: List<Sale>, receipts: List<Receipt>): String {
        val dayKey = key(dayMillis)
        val mine = sales.filter { it.day == dayKey }
        val live = mine.filterNot { it.voided }
        val voided = mine.filter { it.voided }
        val collected = receipts.filter { !it.voided && key(it.time) == dayKey }
        return buildString {
            append("$shopName — ${SimpleDateFormat("EEE, d MMM yyyy", Locale.ENGLISH).format(Date(dayMillis))}\n")
            append("Total: ${rs(live.sumOf { it.total })} (${bills(live.size)})\n")
            append("Cash: ${rs(live.filter { it.payment == "CASH" }.sumOf { it.total })}\n")
            append("UPI: ${rs(live.filter { it.payment == "UPI" }.sumOf { it.total })}\n")
            // Table bills printed and not paid yet: in the total, in no payment.
            val unsettled = live.filter { it.unsettled }
            if (unsettled.isNotEmpty()) append("Not settled yet: ${rs(unsettled.sumOf { it.total })} (${bills(unsettled.size)})\n")
            val serviceCharge = live.sumOf { it.serviceCharge }
            if (serviceCharge > 0) append("Service charge: ${rs(serviceCharge)}\n")
            val credit = live.filter { it.onCredit }.sumOf { it.total }
            if (credit > 0) append("On credit: ${rs(credit)}\n")
            val discount = live.sumOf { it.discount }
            if (discount > 0) append("Discounts: ${rs(discount)}\n")
            if (collected.isNotEmpty()) {
                append("Dues collected: Cash ${rs(collected.filter { it.mode == "CASH" }.sumOf { it.amount })}" +
                    ", UPI ${rs(collected.filter { it.mode == "UPI" }.sumOf { it.amount })}\n")
            }
            if (live.isNotEmpty()) {
                append("\nItems sold:\n")
                itemTotals(live).forEach { (name, qty) -> append("$qty × $name\n") }
            }
            // Listed apart and left out of every figure above, so the owner sees what was cancelled and why.
            if (voided.isNotEmpty()) {
                append("\nVoided (not counted): ${bills(voided.size)}, ${rs(voided.sumOf { it.total })}\n")
                voided.sortedBy { it.time }.forEach { s ->
                    append("#${s.label} ${rs(s.total)}" + (if (s.voidReason.isNotBlank()) " — ${s.voidReason}" else "") + "\n")
                }
            }
        }
    }

    private fun bills(n: Int) = if (n == 1) "1 bill" else "$n bills"

    private fun key(t: Long) = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(t))

    /** Same as the till's: whole rupees without ".00". */
    private fun rs(n: Double): String =
        if (n == Math.floor(n)) "₹" + n.toLong() else "₹" + String.format(Locale.US, "%.2f", n)
}
