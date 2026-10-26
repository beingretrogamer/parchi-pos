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
        /** GS ! — 0x00 normal, 0x01 double height, 0x11 double, 0x22 triple width and height. */
        fun size(n: Int) = raw(GS, 0x21, n)
        /** ESC M — Font A (12x24) or the narrower, shorter Font B (9x17). */
        fun font(small: Boolean) = raw(ESC, 0x4d, if (small) 1 else 0)
        /** ESC 3 — line spacing in dots; the printer still never overlaps tall text. */
        fun spacing(dots: Int) = raw(ESC, 0x33, dots)
        /**
         * GS V B — feeds just far enough to get the last line past the cutter,
         * then partial cut. Any extra feed before this is wasted paper.
         */
        fun cut() = raw(GS, 0x56, 0x42, 0x00)
        fun bytes(): ByteArray = out.toByteArray()
    }

    /** Characters per line of body text. Font B fits a third more on the same paper. */
    fun cols(shop: Prefs.Shop): Int = when {
        !shop.small -> shop.width
        shop.width == 32 -> 42
        else -> 64
    }

    private fun start(shop: Prefs.Shop): Builder {
        val b = Builder()
        if (shop.small) b.font(true).spacing(22)
        return b
    }

    fun bill(shop: Prefs.Shop, sale: Sale): ByteArray {
        val w = cols(shop)
        val b = start(shop)

        // Double height only, never double width: the name stays on one line.
        b.center()
        if (shop.name.isNotBlank()) b.bold(true).size(0x01).line(shop.name.take(w)).size(0).bold(false)
        if (shop.address.isNotBlank()) wrap(shop.address, w).forEach { b.line(it) }
        val contact = listOfNotNull(
            shop.phone.takeIf { it.isNotBlank() }?.let { "Ph: $it" },
            shop.gstin.takeIf { it.isNotBlank() }?.let { "GSTIN: $it" },
        ).joinToString("  ")
        if (contact.isNotEmpty()) wrap(contact, w).forEach { b.line(it) }

        b.left().line("-".repeat(w))
        b.bold(true).size(0x01).line(row2("TOKEN ${sale.token}", dateTime(sale.time), w)).size(0).bold(false)
        b.line(row2("Bill ${sale.id}", sale.customer, w))
        b.line("-".repeat(w))

        // Columns: item (variable) | qty (3) | rate (6) | amount (8)
        val qtyW = 3
        val rateW = 6
        val amtW = 8
        val nameW = w - qtyW - rateW - amtW - 3
        b.bold(true)
            .line("Item".padEnd(nameW) + " " + "Qty".padStart(qtyW) + " " + "Rate".padStart(rateW) + " " + "Amt".padStart(amtW))
            .bold(false)
        for (l in sale.lines) {
            val names = wrap(l.name, nameW)
            b.line(
                names.first().padEnd(nameW) + " " +
                    l.qty.toString().padStart(qtyW) + " " +
                    plain(l.price).padStart(rateW) + " " +
                    plain(l.amount).padStart(amtW)
            )
            names.drop(1).forEach { b.line("  $it") }
            if (l.note.isNotBlank()) wrap("> ${l.note}", w - 2).forEach { b.line("  $it") }
        }
        b.line("-".repeat(w))

        b.bold(true).size(0x01)
            .line(row2("TOTAL (${sale.itemCount} items)", "Rs." + Sales.money(sale.total), w))
            .size(0).bold(false)
        b.line(row2("Paid: ${sale.payment}", if (shop.gstin.isNotBlank()) "Incl. of GST" else "", w))

        b.center()
        wrap(shop.footer.ifBlank { "Thank you! Visit again." }, w).forEach { b.line(it) }
        b.cut()
        return b.bytes()
    }

    /**
     * The kitchen's copy: no prices, token and items large.
     *
     * The item lines stay in the big Font A even with small print on — they
     * are read at arm's length over a hot counter. The token is what ties
     * this slip to the customer holding the bill.
     */
    fun parchi(shop: Prefs.Shop, sale: Sale): ByteArray {
        val w = cols(shop)
        val big = shop.width
        val b = start(shop)

        b.center().bold(true).size(0x11).line("TOKEN ${sale.token}").size(0)
        b.line("KITCHEN  " + time(sale.time) + if (sale.customer.isNotBlank()) "  ${sale.customer}" else "").bold(false)
        b.left().line("-".repeat(w))

        // Quantity first: the kitchen reads "how many" before "of what".
        // Double height only, so a long name still fits on one line.
        b.font(false).bold(true)
        for (l in sale.lines) {
            val qty = "${l.qty} x "
            val names = wrap(l.name, big - qty.length)
            b.size(0x01)
            b.line(qty + names.first())
            names.drop(1).forEach { b.line(" ".repeat(qty.length) + it) }
            // Normal size but bold: it must be noticed, not compete with the item.
            b.size(0)
            if (l.note.isNotBlank()) wrap(">> ${l.note}", big - 4).forEach { b.line("    $it") }
        }
        b.size(0).bold(false).font(shop.small)

        b.line("-".repeat(w))
        b.line(row2("${sale.itemCount} items", "Bill ${sale.id}", w))
        b.cut()
        return b.bytes()
    }

    fun test(shop: Prefs.Shop): ByteArray {
        val w = cols(shop)
        return start(shop)
            .center().bold(true).size(0x01).line("TEST PRINT").size(0).bold(false)
            .line(dateTime(System.currentTimeMillis()))
            .line("-".repeat(w))
            .line("If you can read this,")
            .line("this phone can reach the printer.")
            .line("$w columns" + if (shop.small) ", small print" else "")
            .line("-".repeat(w))
            .cut()
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

    /** 60 rather than 60.00 — the column is narrow and paise are rare. */
    private fun plain(n: Double): String =
        if (n == Math.floor(n)) n.toLong().toString() else Sales.money(n)

    private fun dateTime(t: Long) = SimpleDateFormat("dd/MM/yy HH:mm", Locale.US).format(Date(t))
    private fun time(t: Long) = SimpleDateFormat("HH:mm", Locale.US).format(Date(t))
}
