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
) {
    val total: Double get() = lines.sumOf { it.amount }
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

    @Synchronized
    fun setVoided(c: Context, id: String, voided: Boolean) {
        rewrite(c, all(c).map { if (it.id == id) it.copy(voided = voided, updatedAt = System.currentTimeMillis()) else it })
    }

    /** For a bill rung up as Cash that was paid by UPI, or the other way round. */
    @Synchronized
    fun setPayment(c: Context, id: String, payment: String) {
        rewrite(c, all(c).map { if (it.id == id) it.copy(payment = payment, updatedAt = System.currentTimeMillis()) else it })
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
    fun moveToDay(c: Context, id: String, day: Long): Sale? {
        val all = all(c)
        val old = all.find { it.id == id } ?: return null
        val time = Calendar.getInstance().apply {
            val clock = Calendar.getInstance().apply { timeInMillis = old.time }
            timeInMillis = day
            set(Calendar.HOUR_OF_DAY, clock.get(Calendar.HOUR_OF_DAY))
            set(Calendar.MINUTE, clock.get(Calendar.MINUTE))
            set(Calendar.SECOND, clock.get(Calendar.SECOND))
        }.timeInMillis
        val key = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(time))
        if (key == old.day) return old
        val token = (all.filter { it.day == key }.maxOfOrNull { it.token } ?: 0) + 1
        val moved = old.copy(
            id = SimpleDateFormat("yyMMdd", Locale.US).format(Date(time)) + "-" + token.toString().padStart(3, '0'),
            token = token,
            time = time,
            updatedAt = System.currentTimeMillis(),
        )
        rewrite(c, all.map { if (it.id == id) moved else it })
        return moved
    }

    /** One row per item line, which is what a spreadsheet wants to pivot on. */
    fun toCsv(sales: List<Sale>): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val time = SimpleDateFormat("HH:mm", Locale.US)
        val sb = StringBuilder("date,time,token,bill_id,customer,payment,status,item,note,qty,rate,amount,bill_total\n")
        for (s in sales) {
            val d = Date(s.time)
            for (l in s.lines) {
                sb.append(listOf(
                    date.format(d), time.format(d), s.token.toString(), s.id, s.customer, s.payment,
                    if (s.voided) "VOID" else "OK",
                    l.name, l.note, l.qty.toString(), money(l.price), money(l.amount), money(s.total),
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
        )
    }
}
