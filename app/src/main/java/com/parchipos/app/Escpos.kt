package com.parchipos.app

import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ESC/POS bytes for the two slips every sale prints: the customer's bill and
 * the kitchen's parchi.
 *
 * A port of IceCreamOS's src/lib/escpos.ts, trimmed to what a counter needs.
 * Epson-compatible commands only, so it drives the generic 80mm and 58mm
 * printers that shops actually buy.
 */
object Escpos {

    private const val ESC = 0x1b
    private const val GS = 0x1d
    private const val LF = 0x0a

    class Builder {
        private val out = ByteArrayOutputStream()

        init { raw(ESC, 0x40) } // ESC @ — reset whatever the last job left behind

        fun raw(vararg b: Int) = apply { b.forEach { out.write(it) } }

        /**
         * Printers ship with a code page that has no ₹ and no Devanagari, so
         * anything outside printable ASCII would come out as junk glyphs —
         * "?" at least shows where something was lost.
         */
        fun text(s: String) = apply {
            s.replace("₹", "Rs.").forEach { ch -> out.write(if (ch.code in 0x20..0x7e) ch.code else '?'.code) }
        }

        fun line(s: String = "") = text(s).raw(LF)
        fun feed(n: Int) = apply { repeat(n) { out.write(LF) } }
        fun left() = raw(ESC, 0x61, 0)
        fun center() = raw(ESC, 0x61, 1)
        fun bold(on: Boolean) = raw(ESC, 0x45, if (on) 1 else 0)
        /** GS ! — 0x00 normal, 0x11 double, 0x22 triple width and height. */
        fun size(n: Int) = raw(GS, 0x21, n)
        /** Feed to the cutter, then partial cut. */
        fun cut() = raw(GS, 0x56, 0x42, 0x00)
        fun bytes(): ByteArray = out.toByteArray()
    }

    fun bill(shop: Prefs.Shop, sale: Sale): ByteArray {
        val w = shop.width
        val b = Builder()

        b.center()
        if (shop.name.isNotBlank()) b.bold(true).size(0x11).line(shop.name.take(w / 2)).size(0).bold(false)
        if (shop.address.isNotBlank()) wrap(shop.address, w).forEach { b.line(it) }
        if (shop.phone.isNotBlank()) b.line("Ph: ${shop.phone}")
        if (shop.gstin.isNotBlank()) b.line("GSTIN: ${shop.gstin}")
        b.line("=".repeat(w))

        b.bold(true).size(0x11).line("TOKEN ${sale.token}").size(0).bold(false)
        b.left()
        b.line(row2("Bill: ${sale.id}", dateTime(sale.time), w))
        if (sale.customer.isNotBlank()) b.line("Customer: ${sale.customer}")
        b.line("-".repeat(w))

        // Columns: item (variable) | qty (4) | rate (8) | amount (9)
        val qtyW = 4
        val rateW = 8
        val amtW = 9
        val nameW = w - qtyW - rateW - amtW - 3
        b.bold(true)
            .line("Item".padEnd(nameW) + " " + "Qty".padStart(qtyW) + " " + "Rate".padStart(rateW) + " " + "Amt".padStart(amtW))
            .bold(false)
        for (l in sale.lines) {
            val names = wrap(l.name, nameW)
            b.line(
                names.first().padEnd(nameW) + " " +
                    l.qty.toString().padStart(qtyW) + " " +
                    Sales.money(l.price).padStart(rateW) + " " +
                    Sales.money(l.amount).padStart(amtW)
            )
            names.drop(1).forEach { b.line("  $it") }
            if (l.note.isNotBlank()) wrap("> ${l.note}", w - 2).forEach { b.line("  $it") }
        }
        b.line("-".repeat(w))

        b.bold(true).size(0x11).line(row2("TOTAL", "Rs." + Sales.money(sale.total), w / 2)).size(0).bold(false)
        b.line(row2("${sale.itemCount} items", "Paid: ${sale.payment}", w))
        if (shop.gstin.isNotBlank()) b.line("Prices are inclusive of GST")
        b.line("=".repeat(w))

        b.center()
        wrap(shop.footer.ifBlank { "Thank you! Visit again." }, w).forEach { b.line(it) }
        b.feed(3).cut()
        return b.bytes()
    }

    /**
     * The kitchen's copy: no prices, everything large.
     *
     * The token is printed as big as the printer goes because it is the one
     * thing read from across the counter, and it is what ties this slip to
     * the customer holding the bill.
     */
    fun parchi(shop: Prefs.Shop, sale: Sale): ByteArray {
        val w = shop.width
        val half = w / 2
        val b = Builder()

        b.center().bold(true).line("KITCHEN ORDER")
        b.size(0x22).line(sale.token.toString()).size(0).bold(false)
        b.line(time(sale.time) + if (sale.customer.isNotBlank()) "  -  ${sale.customer}" else "")
        b.left().line("-".repeat(w))

        // Quantity first: the kitchen reads "how many" before "of what".
        b.bold(true).size(0x11)
        for (l in sale.lines) {
            val qty = "${l.qty} x "
            val names = wrap(l.name, half - qty.length)
            b.line(qty + names.first())
            names.drop(1).forEach { b.line(" ".repeat(qty.length) + it) }
            // Normal size but bold: it must be noticed, not compete with the item.
            if (l.note.isNotBlank()) {
                b.size(0)
                wrap(">> ${l.note}", w - 4).forEach { b.line("    $it") }
                b.size(0x11)
            }
        }
        b.size(0).bold(false)

        b.line("-".repeat(w))
        b.line(row2("${sale.itemCount} items", "Bill ${sale.id}", w))
        b.feed(3).cut()
        return b.bytes()
    }

    fun test(shop: Prefs.Shop): ByteArray {
        val w = shop.width
        return Builder()
            .center().bold(true).size(0x11).line("TEST PRINT").size(0).bold(false)
            .line(dateTime(System.currentTimeMillis()))
            .line("=".repeat(w))
            .line("If you can read this,")
            .line("this phone can reach the printer.")
            .line("Paper width: $w columns")
            .line("=".repeat(w))
            .feed(3).cut()
            .bytes()
    }

    /** Left label and right value, padded to `width`; the label gives way first. */
    fun row2(left: String, right: String, width: Int): String {
        if (left.length + right.length + 1 > width) {
            return left.take(maxOf(1, width - right.length - 1)) + " " + right
        }
        return left + " ".repeat(width - left.length - right.length) + right
    }

    /** Splits on spaces where it can, hard-breaks words longer than a line. */
    fun wrap(text: String, width: Int): List<String> {
        val out = ArrayList<String>()
        var line = ""
        for (word0 in text.trim().split(Regex("\\s+"))) {
            var word = word0
            while (word.length > width) {
                if (line.isNotEmpty()) { out.add(line); line = "" }
                out.add(word.take(width))
                word = word.drop(width)
            }
            line = when {
                line.isEmpty() -> word
                line.length + 1 + word.length <= width -> "$line $word"
                else -> { out.add(line); word }
            }
        }
        if (line.isNotEmpty() || out.isEmpty()) out.add(line)
        return out
    }

    private fun dateTime(t: Long) = SimpleDateFormat("dd/MM/yy HH:mm", Locale.US).format(Date(t))
    private fun time(t: Long) = SimpleDateFormat("HH:mm", Locale.US).format(Date(t))
}
