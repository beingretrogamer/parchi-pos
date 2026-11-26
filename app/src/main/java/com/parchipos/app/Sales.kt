package com.parchipos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

data class SaleLine(
    val name: String,
    val price: Double,
    val qty: Int,
    /** "less spicy", "Mint mojito" — printed under the item on both slips. */
    val note: String = "",
) {
    val amount: Double get() = price * qty
}

/** The payment mode of a bill put on the customer's account (udhaar). */
const val CREDIT = "CREDIT"

data class Sale(
    val id: String,
    val token: Int,
    val time: Long,
    val lines: List<SaleLine>,
    val payment: String,
    val customer: String,
    val voided: Boolean = false,
    /**
     * Never changes, unlike [id], which follows the bill to a new day. It is
     * how IceCreamOS tells this sale apart from every other phone's.
     */
    val uid: String = UUID.randomUUID().toString(),
    /** Bumped by every edit (void, payment, date); the cloud keeps the newest state. */
    val updatedAt: Long = System.currentTimeMillis(),
    /**
     * Token letter of the phone that rang it up (A, B, …), from IceCreamOS.
     * Empty before this phone first synced. Phones bill at the same time and
     * each counts tokens from 1, so the letter is what tells "A5" from "B5".
     */
    val letter: String = "",
    /** Empty for this phone's own bills; otherwise the name of the phone that rang it up. */
    val phone: String = "",
    /** Rupees off the whole bill, already worked out from a percent if one was given. */
    val discount: Double = 0.0,
    val discountReason: String = "",
    /** Why it was voided; required when voiding, cleared on restore. */
    val voidReason: String = "",
) {
    /** What the slip and the screen call it: "A12", or "12" without a letter. */
    val label: String get() = letter + token
    val mine: Boolean get() = phone.isEmpty()
    /** Before the discount. */
    val subtotal: Double get() = lines.sumOf { it.amount }
    /** What the customer pays. */
    val total: Double get() = maxOf(0.0, subtotal - discount)
    /** On the customer's account rather than paid at the counter; see [Ledger]. */
    val onCredit: Boolean get() = payment == CREDIT
    val itemCount: Int get() = lines.sumOf { it.qty }
    /** The day it belongs to, as yyyyMMdd — what the Sales screen groups by. */
    val day: String get() = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(time))
}

/**
 * Every sale, one JSON object per line.
 *
 * Append-only for new sales, so recording one is a single small write and an
 * interrupted write can cost at most that one line. Voiding rewrites the file,
 * which is rare enough not to matter.
 */
object Sales {
    private const val FILE = "sales.jsonl"

    @Synchronized
    fun record(c: Context, sale: Sale) {
        File(c.filesDir, FILE).appendText(toJson(sale).toString() + "\n")
    }

    @Synchronized
    fun all(c: Context): List<Sale> {
        val f = File(c.filesDir, FILE)
        if (!f.exists()) return emptyList()
        val sales = f.readLines().mapNotNull { line ->
            // One damaged line should cost one sale, not the whole history.
            if (line.isBlank()) null else try { fromJson(JSONObject(line)) } catch (_: Exception) { null }
        }
        // Sales from before cloud sync have no uid. Give them one once and
        // write it down, so every later read (and sync) sees the same one.
        if (sales.none { it.uid.isEmpty() }) return sales
        val fixed = sales.map { if (it.uid.isEmpty()) it.copy(uid = UUID.randomUUID().toString()) else it }
        rewrite(c, fixed)
        return fixed
    }

    fun forDay(c: Context, day: String): List<Sale> = all(c).filter { it.day == day }

    // Edits find a bill by uid: bill ids from before token letters repeat
    // across phones, and any phone may edit any phone's bill.

    @Synchronized
    fun setVoided(c: Context, uid: String, voided: Boolean, reason: String = "") {
        rewrite(c, all(c).map {
            if (it.uid == uid) it.copy(voided = voided, voidReason = if (voided) reason else "", updatedAt = System.currentTimeMillis()) else it
        })
    }

    /** For a bill rung up as Cash that was paid by UPI, or the other way round. */
    @Synchronized
    fun setPayment(c: Context, uid: String, payment: String) {
        rewrite(c, all(c).map { if (it.uid == uid) it.copy(payment = payment, updatedAt = System.currentTimeMillis()) else it })
    }

    /** "yyMMdd-A007": date, the phone's letter, the token. Unique across phones. */
    fun billId(time: Long, letter: String, token: Int): String =
        SimpleDateFormat("yyMMdd", Locale.US).format(Date(time)) + "-" + letter + token.toString().padStart(3, '0')

    /** Highest token one phone (this one when [phone] is empty) used on [day]; tokens only need to be unique per phone. */
    fun maxToken(all: List<Sale>, day: String, phone: String, letter: String): Int =
        all.filter { it.day == day && it.phone == phone && (phone.isEmpty() || it.letter == letter) }.maxOfOrNull { it.token } ?: 0

    /**
     * Merges bills from the IceCreamOS feed: other phones' bills, and this
     * phone's own if another phone edited them. Whichever state is newer wins,
     * so an edit made here and not yet sent is kept. Returns the uids whose
     * local state now equals the server's, which need no sending.
     */
    @Synchronized
    fun applyRemote(c: Context, remote: List<Sale>): Set<String> {
        if (remote.isEmpty()) return emptySet()
        val local = all(c)
        val byUid = LinkedHashMap<String, Sale>()
        local.forEach { byUid[it.uid] = it }
        val settled = HashSet<String>()
        var changed = false
        for (r in remote) {
            val l = byUid[r.uid]
            when {
                l == null || l.updatedAt < r.updatedAt -> { byUid[r.uid] = r; changed = true; settled.add(r.uid) }
                l.updatedAt == r.updatedAt -> settled.add(r.uid)
            }
        }
        if (changed) rewrite(c, byUid.values.toList())
        return settled
    }

    private fun rewrite(c: Context, sales: List<Sale>) {
        val tmp = File(c.filesDir, "$FILE.tmp")
        tmp.writeText(sales.joinToString("") { toJson(it).toString() + "\n" })
        tmp.renameTo(File(c.filesDir, FILE))
    }

    /**
     * Moves a bill to another day, keeping its clock time. It takes the next
     * token on that day, and a new id to match, since the id is date + token
     * and must not collide with a bill already there.
     */
    @Synchronized
    fun moveToDay(c: Context, uid: String, day: Long): Sale? {
        val all = all(c)
        val old = all.find { it.uid == uid } ?: return null
        val time = Calendar.getInstance().apply {
            val clock = Calendar.getInstance().apply { timeInMillis = old.time }
            timeInMillis = day
            set(Calendar.HOUR_OF_DAY, clock.get(Calendar.HOUR_OF_DAY))
            set(Calendar.MINUTE, clock.get(Calendar.MINUTE))
            set(Calendar.SECOND, clock.get(Calendar.SECOND))
        }.timeInMillis
        val key = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(time))
        if (key == old.day) return old
        // Next token of the same phone on that day, keeping its letter.
        val token = maxToken(all, key, old.phone, old.letter) + 1
        val moved = old.copy(
            id = billId(time, old.letter, token),
            token = token,
            time = time,
            updatedAt = System.currentTimeMillis(),
        )
        rewrite(c, all.map { if (it.uid == uid) moved else it })
        return moved
    }

    /** One row per item line, which is what a spreadsheet wants to pivot on. */
    fun toCsv(sales: List<Sale>): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val time = SimpleDateFormat("HH:mm", Locale.US)
        val sb = StringBuilder("date,time,token,bill_id,phone,customer,payment,status,void_reason,item,note,qty,rate,amount,bill_discount,discount_reason,bill_total\n")
        for (s in sales) {
            val d = Date(s.time)
            for (l in s.lines) {
                sb.append(listOf(
                    date.format(d), time.format(d), s.label, s.id, s.phone.ifEmpty { "this phone" }, s.customer, s.payment,
                    if (s.voided) "VOID" else "OK", s.voidReason,
                    l.name, l.note, l.qty.toString(), money(l.price), money(l.amount),
                    money(s.discount), s.discountReason, money(s.total),
                ).joinToString(",") { csvCell(it) }).append('\n')
            }
        }
        return sb.toString()
    }

    fun money(n: Double): String = String.format(Locale.US, "%.2f", n)

    private fun csvCell(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private fun toJson(s: Sale): JSONObject {
        val lines = JSONArray()
        s.lines.forEach { lines.put(JSONObject().put("name", it.name).put("price", it.price).put("qty", it.qty).put("note", it.note)) }
        return JSONObject()
            .put("id", s.id).put("token", s.token).put("time", s.time)
            .put("payment", s.payment).put("customer", s.customer)
            .put("voided", s.voided).put("lines", lines)
            .put("uid", s.uid).put("updatedAt", s.updatedAt)
            .put("letter", s.letter).put("phone", s.phone)
            .put("discount", s.discount).put("discountReason", s.discountReason)
            .put("voidReason", s.voidReason)
    }

    private fun fromJson(o: JSONObject): Sale {
        val arr = o.getJSONArray("lines")
        val lines = (0 until arr.length()).map { i ->
            val l = arr.getJSONObject(i)
            SaleLine(l.getString("name"), l.getDouble("price"), l.getInt("qty"), l.optString("note", ""))
        }
        return Sale(
            o.getString("id"), o.getInt("token"), o.getLong("time"), lines,
            o.optString("payment", "CASH"), o.optString("customer", ""), o.optBoolean("voided", false),
            uid = o.optString("uid", ""),
            updatedAt = o.optLong("updatedAt", o.getLong("time")),
            letter = o.optString("letter", ""),
            phone = o.optString("phone", ""),
            discount = o.optDouble("discount", 0.0).takeUnless { it.isNaN() } ?: 0.0,
            discountReason = o.optString("discountReason", ""),
            voidReason = o.optString("voidReason", ""),
        )
    }
}
