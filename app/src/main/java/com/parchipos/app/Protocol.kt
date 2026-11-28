package com.parchipos.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * The Parchi Cloud sync protocol's shared rules, ported from the server's
 * src/protocol.ts: the field-group merge, money and tax in paise, and
 * invoice numbers.
 *
 * Both sides must agree to the paisa and on every tie-break, so this is
 * checked against the same fixtures as the server
 * (app/src/test/resources/protocol, copied from parchi-cloud/protocol/fixtures).
 * No Android calls beyond org.json.
 */
object Protocol {

    const val VERSION = 2

    /**
     * A bill is edited in independent ways (voided on one phone, moved to UPI
     * on another), so each group carries its own edit time and both edits
     * survive. Kinds not listed are whole-record: the newer edit wins.
     */
    val FIELD_GROUPS: Map<String, Map<String, List<String>>> = mapOf(
        "bill" to linkedMapOf(
            "void" to listOf("voided", "voidReason"),
            // A table bill is printed first and settled when the guest pays: settling is a payment edit.
            "payment" to listOf("payment", "customerId", "customer", "settledAt"),
            "date" to listOf("day", "time", "billNo", "token", "invoiceNo"),
            "content" to listOf("lines", "discount", "discountReason", "approvals", "letter", "deviceId", "origin",
                "orderId", "table", "roundIds", "covers", "serviceChargePct"),
        ),
        "receipt" to linkedMapOf(
            "void" to listOf("voided", "voidReason"),
            "content" to listOf("time", "day", "deviceId", "customerId", "customer", "amount", "discount", "mode", "note"),
        ),
        // A running order is moved on one device while it is billed on another, and its guests counted on a third.
        "order" to linkedMapOf(
            "place" to listOf("tableId", "mergedInto"),
            "status" to listOf("status", "billIds", "cancelReason", "approvals"),
            "info" to listOf("covers", "note"),
        ),
    )

    /** One state of a record, as far as merging cares. */
    data class Version(
        val updatedAt: Long,
        val fieldTimes: Map<String, Long>?,
        val byDevice: String?,
        val deleted: Boolean,
        val data: JSONObject,
    )

    private fun later(ta: Long, da: String, ca: String, tb: Long, db: String, cb: String): Boolean = when {
        ta != tb -> ta > tb
        da != db -> da > db
        else -> ca > cb
    }

    private fun groupTime(v: Version, group: String): Long = v.fieldTimes?.get(group) ?: v.updatedAt

    private fun pick(data: JSONObject, fields: List<String>): String =
        canonical(JSONArray().apply { fields.forEach { put(if (data.has(it)) data.get(it) else JSONObject.NULL) } })

    /**
     * Merges two states of one record. Commutative and idempotent, so a phone
     * and the server end up holding the same thing whatever order edits
     * arrive in.
     */
    fun merge(kind: String, a: Version, b: Version): Version {
        val da = a.byDevice ?: ""
        val db = b.byDevice ?: ""
        val aNewer = later(a.updatedAt, da, canonical(a.data), b.updatedAt, db, canonical(b.data))
        val newer = if (aNewer) a else b
        val groups = FIELD_GROUPS[kind] ?: return newer.copy(fieldTimes = null)
        // Fields outside every group follow the newer record as a whole.
        val data = JSONObject(newer.data.toString())
        val times = LinkedHashMap<String, Long>()
        for ((group, fields) in groups) {
            val ta = groupTime(a, group)
            val tb = groupTime(b, group)
            val winner = if (later(ta, da, pick(a.data, fields), tb, db, pick(b.data, fields))) a else b
            for (f in fields) {
                if (winner.data.has(f)) data.put(f, winner.data.get(f)) else data.remove(f)
            }
            times[group] = maxOf(ta, tb)
        }
        return Version(maxOf(a.updatedAt, b.updatedAt), times, newer.byDevice, newer.deleted, data)
    }

    /** Whether two states hold the same content (who made them aside). */
    fun sameState(kind: String, a: Version, b: Version): Boolean {
        val ft = { v: Version -> if (FIELD_GROUPS.containsKey(kind)) canonical(JSONObject((v.fieldTimes ?: emptyMap<String, Long>()) as Map<*, *>)) else "" }
        return a.updatedAt == b.updatedAt && a.deleted == b.deleted && ft(a) == ft(b) && canonical(a.data) == canonical(b.data)
    }

    // ── Canonical JSON ──────────────────────────────────────────────────
    //
    // Keys sorted at every level, written exactly as JavaScript's
    // JSON.stringify writes them, because the merge breaks ties by comparing
    // these strings and the server compares the same ones.

    fun canonical(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null, JSONObject.NULL -> sb.append("null")
            is JSONObject -> {
                // JSON null is a value and kept; org.json has no "undefined", so every key counts.
                val keys = v.keys().asSequence().toList().sorted()
                sb.append('{')
                keys.forEachIndexed { i, k ->
                    if (i > 0) sb.append(',')
                    quote(sb, k)
                    sb.append(':')
                    write(sb, v.opt(k))
                }
                sb.append('}')
            }
            is JSONArray -> {
                sb.append('[')
                for (i in 0 until v.length()) {
                    if (i > 0) sb.append(',')
                    write(sb, v.opt(i))
                }
                sb.append(']')
            }
            is Map<*, *> -> write(sb, JSONObject(v))
            is Collection<*> -> write(sb, JSONArray(v))
            is String -> quote(sb, v)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Number -> number(sb, v.toDouble())
            else -> quote(sb, v.toString())
        }
    }

    /** Integral doubles print without ".0", as in JavaScript. */
    private fun number(sb: StringBuilder, d: Double) {
        when {
            d.isNaN() || d.isInfinite() -> sb.append("null")
            d == Math.rint(d) && Math.abs(d) < 1e15 -> sb.append(d.toLong().toString())
            else -> sb.append(d.toString().replace("E", "e"))
        }
    }

    /** JSON.stringify's string escaping: quotes, backslash and control characters only. */
    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
    }

    // ── Money and tax (integer paise) ───────────────────────────────────

    data class Line(val price: Long, val qty: Int, val taxRate: Double = 0.0)
    data class LineFigures(val net: Long, val tax: Long)
    /**
     * [serviceCharge] is before its own tax, [serviceChargeTax] that tax (in [tax] too). With tax-inclusive
     * prices a bill shows the two together, which is exactly the percentage of the food's price.
     */
    data class Totals(
        val subtotal: Long, val discount: Long, val tax: Long, val total: Long, val lines: List<LineFigures>,
        val serviceCharge: Long = 0, val serviceChargeTax: Long = 0,
    ) {
        /** The service charge as a bill prints it (see the class note). */
        fun serviceChargeShown(pricesIncludeTax: Boolean): Long = serviceCharge + if (pricesIncludeTax) serviceChargeTax else 0
    }

    /**
     * The bill's figures. The discount is spread over lines in proportion to
     * their amounts, the remainder on the last line so it adds up to the
     * paisa; tax is worked out per line at its own rate.
     */
    fun billTotals(lines: List<Line>, discountRaw: Long, pricesIncludeTax: Boolean = true, serviceChargePct: Double = 0.0): Totals {
        val amounts = lines.map { it.price * it.qty }
        val subtotal = amounts.sum()
        val discount = discountRaw.coerceIn(0, subtotal)
        val pct = serviceChargePct.coerceIn(0.0, 100.0)
        var given = 0L
        var serviceCharge = 0L
        var foodTax = 0L
        val out = amounts.mapIndexed { i, amount ->
            val share = if (i == amounts.size - 1) discount - given
                else if (subtotal != 0L) Math.round(discount.toDouble() * amount / subtotal) else 0L
            given += share
            val net = amount - share
            val r = lines[i].taxRate
            val tax = when {
                r <= 0 -> 0L
                pricesIncludeTax -> Math.round(net * r / (100 + r))
                else -> Math.round(net * r / 100)
            }
            foodTax += tax
            // A service charge is a percentage of the line's value before tax, taxed at the line's rate.
            val sc = if (pct > 0) Math.round((if (pricesIncludeTax) net - tax else net) * pct / 100) else 0L
            val scTax = if (r <= 0) 0L else Math.round(sc * r / 100)
            serviceCharge += sc
            LineFigures(net, tax + scTax)
        }
        val tax = out.sumOf { it.tax }
        val scTax = tax - foodTax
        return Totals(subtotal, discount, tax, subtotal - discount + (if (pricesIncludeTax) 0 else foodTax) + serviceCharge + scTax, out, serviceCharge, scTax)
    }

    /** ₹45.50 → 4550. Every price on a phone was typed in rupees and paise, so this is exact. */
    fun paise(rupees: Double): Long = Math.round(rupees * 100)

    fun rupees(paise: Long): Double = paise / 100.0

    // ── Invoice numbers ─────────────────────────────────────────────────

    /** Indian financial year (April to March) of a yyyyMMdd day: 20261127 → "26-27". */
    fun fyOf(day: String): String {
        val y = day.substring(0, 4).toInt()
        val m = day.substring(4, 6).toInt()
        val start = if (m >= 4) y else y - 1
        fun yy(n: Int) = (n % 100).toString().padStart(2, '0')
        return "${yy(start)}-${yy(start + 1)}"
    }

    private val INVOICE = Regex("^(?:([A-Z0-9]{1,8})-)?([A-Z]{1,3})/(\\d{2}-\\d{2})/(\\d{6})$")

    data class Invoice(val prefix: String?, val letter: String, val fy: String, val serial: Int)

    fun formatInvoice(letter: String, fy: String, serial: Int, prefix: String? = null): String =
        (if (!prefix.isNullOrEmpty()) "$prefix-" else "") + "$letter/$fy/" + serial.toString().padStart(6, '0')

    fun parseInvoice(no: String): Invoice? {
        val m = INVOICE.matchEntire(no) ?: return null
        return Invoice(m.groupValues[1].ifEmpty { null }, m.groupValues[2], m.groupValues[3], m.groupValues[4].toInt())
    }

    /** "0.7.0" style versions; missing parts count as 0. */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.', '-').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val pb = b.split('.', '-').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
            if (d != 0) return Integer.signum(d)
        }
        return 0
    }
}
