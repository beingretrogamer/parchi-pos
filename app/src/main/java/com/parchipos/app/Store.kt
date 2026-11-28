package com.parchipos.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * The phone's database, parchi.db, through Android's own SQLiteOpenHelper
 * (no dependency).
 *
 * - Every amount is integer paise. The screens keep working in rupees;
 *   conversion happens only here, with [Protocol.paise], which is exact for
 *   anything typed in rupees and paise.
 * - Bills and receipts keep their whole state as the JSON record Parchi Cloud
 *   syncs (`data`). A few columns are copied out of it for fast queries.
 *   Fields this version doesn't know about are kept when it saves a record
 *   again, so an older phone never erases what a newer one or the portal wrote.
 * - Each row also carries the sync bookkeeping: edit times per field group,
 *   the server's sequence number, and `dirty` while a local edit is unsent.
 *
 * - Bills and payments also note where they came from ([Src]), so a device
 *   only ever uploads what was rung up on it, and leaving an account takes
 *   off what came from it.
 *
 * The first open imports sales.jsonl, receipts.jsonl and menu.json, in one
 * transaction, and renames them to *.imported, kept as a backup.
 */
object Store {
    private const val NAME = "parchi.db"
    private const val VERSION = 3

    @Volatile private var helper: Helper? = null

    private class Helper(private val app: Context) : SQLiteOpenHelper(app, NAME, null, VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            SCHEMA.forEach(db::execSQL)
        }

        // Each version adds tables and columns, never rebuilds one.
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) Provenance.upgrade(app, db)
            // Orders merge per field group (a move here, a bill there), so records keep their group times too.
            if (oldVersion < 3) db.execSQL("ALTER TABLE records ADD COLUMN field_times TEXT")
        }
    }

    private val SCHEMA = listOf(
        "CREATE TABLE kv (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
        """CREATE TABLE bills (
             id TEXT PRIMARY KEY, bill_no TEXT NOT NULL, day TEXT NOT NULL, time INTEGER NOT NULL,
             letter TEXT NOT NULL DEFAULT '', token INTEGER NOT NULL, phone TEXT NOT NULL DEFAULT '',
             payment TEXT NOT NULL, customer TEXT NOT NULL DEFAULT '', customer_id TEXT,
             voided INTEGER NOT NULL DEFAULT 0, total INTEGER NOT NULL,
             data TEXT NOT NULL, updated_at INTEGER NOT NULL, field_times TEXT,
             seq INTEGER NOT NULL DEFAULT 0, dirty INTEGER NOT NULL DEFAULT 0, sync_error TEXT,
             src TEXT NOT NULL DEFAULT 'own')""",
        "CREATE INDEX bills_day ON bills(day)",
        "CREATE INDEX bills_payment ON bills(payment)",
        "CREATE INDEX bills_dirty ON bills(dirty)",
        """CREATE TABLE receipts (
             id TEXT PRIMARY KEY, day TEXT NOT NULL, time INTEGER NOT NULL,
             customer TEXT NOT NULL DEFAULT '', customer_id TEXT, voided INTEGER NOT NULL DEFAULT 0,
             data TEXT NOT NULL, updated_at INTEGER NOT NULL, field_times TEXT,
             seq INTEGER NOT NULL DEFAULT 0, dirty INTEGER NOT NULL DEFAULT 0, sync_error TEXT,
             src TEXT NOT NULL DEFAULT 'own')""",
        "CREATE INDEX receipts_dirty ON receipts(dirty)",
        // Everything else Parchi Cloud syncs: categories, menu items, customers, outlet, rules, staff…
        """CREATE TABLE records (
             kind TEXT NOT NULL, id TEXT NOT NULL, outlet_id TEXT, data TEXT NOT NULL,
             deleted INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL, by_user TEXT, proof TEXT,
             seq INTEGER NOT NULL DEFAULT 0, dirty INTEGER NOT NULL DEFAULT 0, sync_error TEXT, field_times TEXT,
             PRIMARY KEY (kind, id))""",
        "CREATE INDEX records_dirty ON records(dirty)",
    )

    fun db(c: Context): SQLiteDatabase {
        helper?.let { return it.writableDatabase }
        synchronized(this) {
            helper?.let { return it.writableDatabase }
            val app = c.applicationContext
            val h = Helper(app)
            val db = h.writableDatabase
            Import.run(app, db)
            helper = h
            return db
        }
    }

    /** Tests open a fresh app per test; this lets the next one start clean. */
    internal fun closeForTests() = synchronized(this) {
        helper?.close()
        helper = null
    }

    fun <T> tx(c: Context, block: (SQLiteDatabase) -> T): T {
        val db = db(c)
        db.beginTransaction()
        try {
            val r = block(db)
            db.setTransactionSuccessful()
            return r
        } finally {
            db.endTransaction()
        }
    }

    // ── kv ──────────────────────────────────────────────────────────────

    fun get(c: Context, key: String): String? =
        db(c).rawQuery("SELECT value FROM kv WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    fun put(c: Context, key: String, value: String?) {
        if (value == null) db(c).delete("kv", "key = ?", arrayOf(key))
        else db(c).insertWithOnConflict("kv", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ── bills ───────────────────────────────────────────────────────────

    /**
     * Writes a bill's whole state. [dirty] marks a local edit Parchi Cloud
     * hasn't got yet; a state that came from a server is saved clean. A new
     * bill is [src]; one already here keeps where it came from.
     */
    fun saveBill(c: Context, s: Sale, dirty: Boolean = true, src: String = Src.OWN) {
        val db = db(c)
        val (base, kept) = db.rawQuery("SELECT data, src FROM bills WHERE id = ?", arrayOf(s.uid)).use {
            if (it.moveToFirst()) JSONObject(it.getString(0)) to it.getString(1) else null to null
        }
        val data = Bills.toData(s, base)
        val times = s.fieldTimes.ifEmpty { Bills.allGroups(s.updatedAt) }
        db.insertWithOnConflict("bills", null, ContentValues().apply {
            put("id", s.uid)
            put("bill_no", s.id)
            put("day", s.day)
            put("time", s.time)
            put("letter", s.letter)
            put("token", s.token)
            put("phone", s.phone)
            put("payment", s.payment)
            put("customer", s.customer)
            put("customer_id", s.customerId)
            put("voided", if (s.voided) 1 else 0)
            put("total", Bills.totalPaise(data))
            put("data", data.toString())
            put("updated_at", s.updatedAt)
            put("field_times", JSONObject(times as Map<*, *>).toString())
            put("dirty", if (dirty) 1 else 0)
            put("src", kept ?: src)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun bill(c: Context, uid: String): Sale? = bills(c, "id = ?", arrayOf(uid)).firstOrNull()

    /** Bills matching a WHERE clause, oldest first. */
    fun bills(c: Context, where: String? = null, args: Array<String>? = null): List<Sale> =
        db(c).rawQuery(
            "SELECT id, phone, data, updated_at, field_times FROM bills" + (if (where != null) " WHERE $where" else "") + " ORDER BY time, id",
            args,
        ).use { cur -> buildList { while (cur.moveToNext()) Bills.fromRow(cur)?.let(::add) } }

    /** Highest token a phone used on a day. For this phone ([phone] empty) any letter counts, as before letters existed. */
    fun maxToken(c: Context, day: String, phone: String, letter: String): Int =
        db(c).rawQuery(
            "SELECT MAX(token) FROM bills WHERE day = ? AND phone = ? AND (? = '' OR letter = ?)",
            arrayOf(day, phone, phone, letter),
        ).use { if (it.moveToFirst() && !it.isNull(0)) it.getInt(0) else 0 }

    // ── receipts ────────────────────────────────────────────────────────

    /** A payment taken here, or an edit of one already here (which keeps where it came from). */
    fun saveReceipt(c: Context, r: Receipt, dirty: Boolean = true) {
        val db = db(c)
        val (base, kept) = db.rawQuery("SELECT data, src FROM receipts WHERE id = ?", arrayOf(r.uid)).use {
            if (it.moveToFirst()) JSONObject(it.getString(0)) to it.getString(1) else null to null
        }
        val data = Receipts.toData(r, base)
        val times = r.fieldTimes.ifEmpty { Receipts.allGroups(r.updatedAt) }
        db.insertWithOnConflict("receipts", null, ContentValues().apply {
            put("id", r.uid)
            put("day", r.day)
            put("time", r.time)
            put("customer", r.customer)
            put("customer_id", r.customerId)
            put("voided", if (r.voided) 1 else 0)
            put("data", data.toString())
            put("updated_at", r.updatedAt)
            put("field_times", JSONObject(times as Map<*, *>).toString())
            put("dirty", if (dirty) 1 else 0)
            put("src", kept ?: Src.OWN)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun receipt(c: Context, uid: String): Receipt? = receipts(c, "id = ?", arrayOf(uid)).firstOrNull()

    fun receipts(c: Context, where: String? = null, args: Array<String>? = null): List<Receipt> =
        db(c).rawQuery(
            "SELECT id, data, updated_at, field_times FROM receipts" + (if (where != null) " WHERE $where" else "") + " ORDER BY time, id",
            args,
        ).use { cur -> buildList { while (cur.moveToNext()) Receipts.fromRow(cur)?.let(::add) } }

    // ── other records ───────────────────────────────────────────────────

    /** A synced record of any other kind: category, menuItem, customer, outlet, rules, staff… */
    data class Rec(
        val kind: String,
        val id: String,
        val data: JSONObject,
        val outletId: String? = null,
        val deleted: Boolean = false,
        val updatedAt: Long = System.currentTimeMillis(),
        val byUser: String? = null,
        val proof: String? = null,
        val seq: Long = 0,
        val dirty: Boolean = false,
        /** For kinds that merge per field group (orders); null for whole-record kinds. */
        val fieldTimes: Map<String, Long>? = null,
    )

    fun record(c: Context, kind: String, id: String): Rec? =
        db(c).rawQuery("SELECT * FROM records WHERE kind = ? AND id = ?", arrayOf(kind, id)).use { if (it.moveToFirst()) rec(it) else null }

    fun records(c: Context, kind: String, includeDeleted: Boolean = false): List<Rec> =
        db(c).rawQuery(
            "SELECT * FROM records WHERE kind = ?" + (if (includeDeleted) "" else " AND deleted = 0"),
            arrayOf(kind),
        ).use { cur -> buildList { while (cur.moveToNext()) add(rec(cur)) } }

    fun saveRecord(c: Context, r: Rec) {
        db(c).insertWithOnConflict("records", null, ContentValues().apply {
            put("kind", r.kind)
            put("id", r.id)
            put("outlet_id", r.outletId)
            put("data", r.data.toString())
            put("deleted", if (r.deleted) 1 else 0)
            put("updated_at", r.updatedAt)
            put("by_user", r.byUser)
            put("proof", r.proof)
            put("seq", r.seq)
            put("dirty", if (r.dirty) 1 else 0)
            put("field_times", r.fieldTimes?.let { JSONObject(it as Map<*, *>).toString() })
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun rec(cur: Cursor) = Rec(
        kind = cur.getString(cur.getColumnIndexOrThrow("kind")),
        id = cur.getString(cur.getColumnIndexOrThrow("id")),
        data = JSONObject(cur.getString(cur.getColumnIndexOrThrow("data"))),
        outletId = cur.getString(cur.getColumnIndexOrThrow("outlet_id")),
        deleted = cur.getInt(cur.getColumnIndexOrThrow("deleted")) == 1,
        updatedAt = cur.getLong(cur.getColumnIndexOrThrow("updated_at")),
        byUser = cur.getString(cur.getColumnIndexOrThrow("by_user")),
        proof = cur.getString(cur.getColumnIndexOrThrow("proof")),
        seq = cur.getLong(cur.getColumnIndexOrThrow("seq")),
        dirty = cur.getInt(cur.getColumnIndexOrThrow("dirty")) == 1,
        fieldTimes = cur.getColumnIndex("field_times").takeIf { it >= 0 }?.let { times(cur.getString(it)) }?.takeIf { it.isNotEmpty() },
    )

    // ── sync bookkeeping ────────────────────────────────────────────────

    /** A row as the sync engine sees it: the record, its times, and whether a local edit is unsent. */
    data class Raw(
        val table: String,
        val kind: String,
        val id: String,
        val data: JSONObject,
        val updatedAt: Long,
        val fieldTimes: Map<String, Long>?,
        val deleted: Boolean,
        val dirty: Boolean,
        val seq: Long,
        val byUser: String? = null,
        val proof: String? = null,
        val outletId: String? = null,
        /** Bills and payments only: see [Src]. */
        val src: String = Src.OWN,
    )

    fun tableOf(kind: String) = when (kind) {
        "bill" -> "bills"
        "receipt" -> "receipts"
        else -> "records"
    }

    fun raw(c: Context, kind: String, id: String): Raw? = when (val t = tableOf(kind)) {
        "records" -> record(c, kind, id)?.let { Raw(t, kind, id, it.data, it.updatedAt, it.fieldTimes, it.deleted, it.dirty, it.seq, it.byUser, it.proof, it.outletId) }
        else -> db(c).rawQuery("SELECT data, updated_at, field_times, dirty, seq, src FROM $t WHERE id = ?", arrayOf(id)).use {
            if (!it.moveToFirst()) null
            else Raw(t, kind, id, JSONObject(it.getString(0)), it.getLong(1), times(it.getString(2)), false, it.getInt(3) == 1, it.getLong(4), src = it.getString(5))
        }
    }

    /**
     * Local edits Parchi Cloud hasn't acknowledged, oldest first: customers
     * and the menu (few) ahead of payments and bills (many), and never the
     * server's own kinds.
     */
    fun outbox(c: Context, limit: Int): List<Raw> {
        val out = ArrayList<Raw>()
        db(c).rawQuery(
            // An order goes before its rounds, though the server takes them either way round.
            """SELECT * FROM records WHERE dirty = 1 AND kind IN ('customer','category','menuItem','itemStatus','outlet','rules','order','round')
               ORDER BY CASE kind WHEN 'customer' THEN 0 WHEN 'category' THEN 1 WHEN 'order' THEN 3 WHEN 'round' THEN 4 ELSE 2 END, updated_at LIMIT ?""",
            arrayOf(limit.toString()),
        ).use { cur -> while (cur.moveToNext()) rec(cur).let { out.add(Raw("records", it.kind, it.id, it.data, it.updatedAt, it.fieldTimes, it.deleted, true, it.seq, it.byUser, it.proof, it.outletId)) } }
        for ((t, kind) in listOf("receipts" to "receipt", "bills" to "bill")) {
            if (out.size >= limit) break
            db(c).rawQuery("SELECT id, data, updated_at, field_times, seq FROM $t WHERE dirty = 1 ORDER BY updated_at LIMIT ?", arrayOf((limit - out.size).toString())).use { cur ->
                while (cur.moveToNext()) out.add(Raw(t, kind, cur.getString(0), JSONObject(cur.getString(1)), cur.getLong(2), times(cur.getString(3)), false, true, cur.getLong(4)))
            }
        }
        return out
    }

    fun outboxCount(c: Context): Int = db(c).rawQuery(
        """SELECT (SELECT COUNT(*) FROM bills WHERE dirty = 1) + (SELECT COUNT(*) FROM receipts WHERE dirty = 1)
             + (SELECT COUNT(*) FROM records WHERE dirty = 1 AND kind IN ('customer','category','menuItem','itemStatus','outlet','rules'))""",
        null,
    ).use { it.moveToFirst(); it.getInt(0) }

    /**
     * The server has [seq] for what was sent. Clean only if the row is still
     * the state that was sent: an edit made meanwhile must still go.
     */
    fun markSent(c: Context, kind: String, id: String, sentUpdatedAt: Long, seq: Long) {
        val t = tableOf(kind)
        val where = if (t == "records") "kind = ? AND id = ? AND updated_at = ?" else "id = ? AND updated_at = ?"
        val args = if (t == "records") arrayOf(kind, id, sentUpdatedAt.toString()) else arrayOf(id, sentUpdatedAt.toString())
        db(c).update(t, ContentValues().apply { put("dirty", 0); put("seq", seq); putNull("sync_error") }, where, args)
    }

    /**
     * Bills and payments the server refused go again: its rules may have
     * changed since (old voids without a reason were refused until it learnt
     * that bills from before the cloud never asked for one). Each keeps its
     * reason until the server takes it. One rung up here that the server never
     * took is stamped with this device's id first, as connecting does. Feed
     * copies of other phones' bills never go. Returns how many will go.
     */
    fun retryRejected(c: Context, deviceId: String): Int = tx(c) { db ->
        var n = 0
        for (t in listOf("bills", "receipts")) {
            val rows = db.rawQuery(
                "SELECT id, data, seq, src FROM $t WHERE sync_error IS NOT NULL AND dirty = 0 AND NOT (src = ? AND seq = 0)",
                arrayOf(Src.FEED),
            ).use { cur -> buildList { while (cur.moveToNext()) add(listOf(cur.getString(0), cur.getString(1), cur.getLong(2).toString(), cur.getString(3))) } }
            for ((id, data, seq, src) in rows) {
                val v = ContentValues().apply { put("dirty", 1) }
                if (seq == "0" && src == Src.OWN) v.put("data", JSONObject(data).put("deviceId", deviceId).toString())
                n += db.update(t, v, "id = ?", arrayOf(id))
            }
        }
        n
    }

    /** The server refused it: kept, marked, and not sent again until [retryRejected]. */
    fun markRejected(c: Context, kind: String, id: String, reason: String) {
        val t = tableOf(kind)
        val (where, args) = if (t == "records") "kind = ? AND id = ?" to arrayOf(kind, id) else "id = ?" to arrayOf(id)
        db(c).update(t, ContentValues().apply { put("dirty", 0); put("sync_error", reason.take(200)) }, where, args)
    }

    /** A refusal that left nothing behind to mark (an edit that was undone): kept as a line of text. */
    fun noteProblem(c: Context, text: String) {
        val list = try { JSONArray(get(c, "cloud.problems") ?: "[]") } catch (_: Exception) { JSONArray() }
        val next = JSONArray().put(text)
        for (i in 0 until minOf(list.length(), 9)) next.put(list.optString(i))
        put(c, "cloud.problems", next.toString())
    }

    /** Rows the server refused, and undone edits, newest first, for the Parchi Cloud screen and diagnostics. */
    fun rejected(c: Context, limit: Int = 20): List<String> = notes(c) + rows(c, limit)

    private fun notes(c: Context): List<String> = try {
        JSONArray(get(c, "cloud.problems") ?: "[]").let { a -> (0 until a.length()).map { a.optString(it) } }
    } catch (_: Exception) { emptyList() }

    private fun rows(c: Context, limit: Int): List<String> = db(c).rawQuery(
        """SELECT 'Bill ' || bill_no || ': ' || sync_error, updated_at FROM bills WHERE sync_error IS NOT NULL
           UNION ALL SELECT 'Payment ' || customer || ': ' || sync_error, updated_at FROM receipts WHERE sync_error IS NOT NULL
           UNION ALL SELECT kind || ' ' || id || ': ' || sync_error, updated_at FROM records WHERE sync_error IS NOT NULL
           ORDER BY 2 DESC LIMIT ?""",
        arrayOf(limit.toString()),
    ).use { cur -> buildList { while (cur.moveToNext()) add(cur.getString(0)) } }

    /** A bill exactly as the server holds it (or as merged), with this device's display name for its device. */
    fun putBillRecord(c: Context, id: String, data: JSONObject, updatedAt: Long, fieldTimes: Map<String, Long>?, seq: Long, dirty: Boolean, phone: String, src: String) {
        val s = Bills.fromData(id, phone, data, updatedAt, fieldTimes ?: emptyMap())
        db(c).insertWithOnConflict("bills", null, ContentValues().apply {
            put("id", id)
            put("bill_no", s.id)
            put("day", data.optString("day").ifEmpty { s.day })
            put("time", s.time)
            put("letter", s.letter)
            put("token", s.token)
            put("phone", phone)
            put("payment", s.payment)
            put("customer", s.customer)
            put("customer_id", s.customerId)
            put("voided", if (s.voided) 1 else 0)
            put("total", Bills.totalPaise(data))
            put("data", data.toString())
            put("updated_at", updatedAt)
            put("field_times", fieldTimes?.let { JSONObject(it as Map<*, *>).toString() })
            put("seq", seq)
            put("dirty", if (dirty) 1 else 0)
            putNull("sync_error")
            put("src", src)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun putReceiptRecord(c: Context, id: String, data: JSONObject, updatedAt: Long, fieldTimes: Map<String, Long>?, seq: Long, dirty: Boolean, src: String) {
        db(c).insertWithOnConflict("receipts", null, ContentValues().apply {
            put("id", id)
            put("day", data.optString("day"))
            put("time", data.optLong("time"))
            put("customer", data.optString("customer", ""))
            put("customer_id", data.optStringOrNull("customerId"))
            put("voided", if (data.optBoolean("voided", false)) 1 else 0)
            put("data", data.toString())
            put("updated_at", updatedAt)
            put("field_times", fieldTimes?.let { JSONObject(it as Map<*, *>).toString() })
            put("seq", seq)
            put("dirty", if (dirty) 1 else 0)
            putNull("sync_error")
            put("src", src)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun count(c: Context, table: String, where: String): Int =
        db(c).rawQuery("SELECT COUNT(*) FROM $table WHERE $where", null).use { it.moveToFirst(); it.getInt(0) }

    fun deleteRow(c: Context, kind: String, id: String) {
        val t = tableOf(kind)
        if (t == "records") db(c).delete(t, "kind = ? AND id = ?", arrayOf(kind, id)) else db(c).delete(t, "id = ?", arrayOf(id))
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** prefix + 16 hex characters of SHA-256: the same input gives the same id on every phone. */
    fun stableId(prefix: String, input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return prefix + "_" + digest.take(8).joinToString("") { "%02x".format(it) }
    }

    fun randomId(prefix: String): String = prefix + "_" + UUID.randomUUID().toString().replace("-", "").take(16)

    internal fun times(json: String?): Map<String, Long> {
        if (json.isNullOrEmpty()) return emptyMap()
        val o = JSONObject(json)
        return o.keys().asSequence().associateWith { o.getLong(it) }
    }
}

/** A bill's cloud record ⇄ the till's [Sale]. */
internal object Bills {

    fun allGroups(at: Long): Map<String, Long> = Protocol.FIELD_GROUPS.getValue("bill").keys.associateWith { at }

    /**
     * The record Parchi Cloud syncs, written over [base] (what is stored), so
     * fields this version doesn't manage (approvals, anything newer) survive.
     */
    fun toData(s: Sale, base: JSONObject?): JSONObject {
        val o = if (base != null) JSONObject(base.toString()) else JSONObject()
        o.put("billNo", s.id)
        o.put("token", s.token)
        o.put("letter", s.letter)
        o.put("day", s.day)
        o.put("time", s.time)
        o.putOrRemove("deviceId", s.deviceId)
        o.putOrRemove("origin", s.origin.ifBlank { null })
        // A table bill printed but not paid yet has no payment until it is settled.
        o.put("payment", if (s.payment.isEmpty()) JSONObject.NULL else s.payment)
        o.putOrRemove("settledAt", s.settledAt)
        o.put("customer", s.customer)
        o.putOrRemove("customerId", s.customerId)
        o.putOrRemove("invoiceNo", s.invoiceNo)
        o.putOrRemove("orderId", s.orderId)
        o.putOrRemove("table", s.table)
        if (s.roundIds.isNotEmpty()) o.put("roundIds", JSONArray(s.roundIds)) else o.remove("roundIds")
        o.putOrRemove("covers", s.covers)
        if (s.serviceChargePct > 0) o.put("serviceChargePct", s.serviceChargePct) else o.remove("serviceChargePct")
        o.put("lines", JSONArray().apply {
            s.lines.forEach { l ->
                put(JSONObject().apply {
                    put("name", l.name)
                    put("price", Protocol.paise(l.price))
                    put("qty", l.qty)
                    if (l.note.isNotBlank()) put("note", l.note)
                    if (l.itemId != null) put("itemId", l.itemId)
                    if (l.variant != null) put("variant", l.variant)
                    if (l.custom) put("custom", true)
                    if (l.taxRate != null) put("taxRate", l.taxRate)
                })
            }
        })
        val discount = Protocol.paise(s.discount)
        if (discount > 0) o.put("discount", discount) else o.remove("discount")
        o.putOrRemove("discountReason", s.discountReason.ifBlank { null })
        o.put("voided", s.voided)
        o.putOrRemove("voidReason", s.voidReason.ifBlank { null })
        if (s.approvals.isNotEmpty()) o.put("approvals", ApprovalNote.toArray(s.approvals)) else o.remove("approvals")
        return o
    }

    fun totalPaise(data: JSONObject): Long {
        val arr = data.optJSONArray("lines") ?: JSONArray()
        val lines = (0 until arr.length()).map { arr.getJSONObject(it).let { l -> Protocol.Line(l.optLong("price"), l.optInt("qty"), l.optDouble("taxRate", 0.0).takeIf { r -> !r.isNaN() } ?: 0.0) } }
        return Protocol.billTotals(lines, data.optLong("discount", 0), true, data.optDouble("serviceChargePct", 0.0).takeIf { !it.isNaN() } ?: 0.0).total
    }

    fun fromRow(cur: Cursor): Sale? = try {
        fromData(
            uid = cur.getString(0),
            phone = cur.getString(1),
            o = JSONObject(cur.getString(2)),
            updatedAt = cur.getLong(3),
            fieldTimes = Store.times(cur.getString(4)),
        )
    } catch (_: Exception) {
        null // a damaged row costs one bill, never the screen
    }

    fun fromData(uid: String, phone: String, o: JSONObject, updatedAt: Long, fieldTimes: Map<String, Long>): Sale {
        val arr = o.optJSONArray("lines") ?: JSONArray()
        val lines = (0 until arr.length()).map { i ->
            val l = arr.getJSONObject(i)
            SaleLine(
                name = l.getString("name"),
                price = Protocol.rupees(l.getLong("price")),
                qty = l.getInt("qty"),
                note = l.optString("note", ""),
                itemId = l.optStringOrNull("itemId"),
                variant = l.optStringOrNull("variant"),
                custom = l.optBoolean("custom", false),
                taxRate = if (l.has("taxRate") && !l.isNull("taxRate")) l.getDouble("taxRate") else null,
            )
        }
        return Sale(
            id = o.getString("billNo"),
            token = o.getInt("token"),
            time = o.getLong("time"),
            lines = lines,
            // null: a table bill not settled yet. (optString would turn JSON null into "null".)
            payment = if (o.has("payment") && o.isNull("payment")) "" else o.optString("payment", "CASH"),
            customer = o.optString("customer", ""),
            voided = o.optBoolean("voided", false),
            uid = uid,
            updatedAt = updatedAt,
            letter = o.optString("letter", ""),
            phone = phone,
            discount = Protocol.rupees(o.optLong("discount", 0)),
            discountReason = o.optString("discountReason", ""),
            voidReason = o.optString("voidReason", ""),
            deviceId = o.optStringOrNull("deviceId"),
            origin = o.optString("origin", ""),
            customerId = o.optStringOrNull("customerId"),
            invoiceNo = o.optStringOrNull("invoiceNo"),
            fieldTimes = fieldTimes,
            approvals = ApprovalNote.listFrom(o.optJSONArray("approvals")),
            orderId = o.optStringOrNull("orderId"),
            table = o.optStringOrNull("table"),
            roundIds = o.optJSONArray("roundIds")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            covers = if (o.has("covers") && !o.isNull("covers")) o.getInt("covers") else null,
            serviceChargePct = o.optDouble("serviceChargePct", 0.0).takeIf { !it.isNaN() } ?: 0.0,
            settledAt = if (o.has("settledAt") && !o.isNull("settledAt")) o.getLong("settledAt") else null,
        )
    }
}

/** A dues payment's cloud record ⇄ [Receipt]. */
internal object Receipts {

    fun allGroups(at: Long): Map<String, Long> = Protocol.FIELD_GROUPS.getValue("receipt").keys.associateWith { at }

    fun toData(r: Receipt, base: JSONObject?): JSONObject {
        val o = if (base != null) JSONObject(base.toString()) else JSONObject()
        o.put("time", r.time)
        o.put("day", r.day)
        o.putOrRemove("deviceId", r.deviceId)
        o.putOrRemove("customerId", r.customerId)
        o.put("customer", r.customer)
        o.put("amount", Protocol.paise(r.amount))
        val discount = Protocol.paise(r.discount)
        if (discount > 0) o.put("discount", discount) else o.remove("discount")
        o.put("mode", r.mode)
        o.putOrRemove("note", r.note.ifBlank { null })
        o.put("voided", r.voided)
        o.putOrRemove("voidReason", r.voidReason.ifBlank { null })
        if (r.approvals.isNotEmpty()) o.put("approvals", ApprovalNote.toArray(r.approvals)) else o.remove("approvals")
        return o
    }

    fun fromRow(cur: Cursor): Receipt? = try {
        val o = JSONObject(cur.getString(1))
        Receipt(
            uid = cur.getString(0),
            time = o.getLong("time"),
            customer = o.optString("customer", ""),
            amount = Protocol.rupees(o.optLong("amount", 0)),
            discount = Protocol.rupees(o.optLong("discount", 0)),
            mode = o.optString("mode", "CASH"),
            note = o.optString("note", ""),
            voided = o.optBoolean("voided", false),
            voidReason = o.optString("voidReason", ""),
            updatedAt = cur.getLong(2),
            fieldTimes = Store.times(cur.getString(3)),
            deviceId = o.optStringOrNull("deviceId"),
            customerId = o.optStringOrNull("customerId"),
            approvals = ApprovalNote.listFrom(o.optJSONArray("approvals")),
        )
    } catch (_: Exception) {
        null
    }
}

/**
 * The one-time move from the v0.6 files into parchi.db. All in one
 * transaction: either every bill, payment and menu item is in the database,
 * or nothing is and the files are untouched for the next try.
 */
internal object Import {
    private const val DONE = "imported_v1"

    fun run(c: Context, db: SQLiteDatabase) {
        val done = db.rawQuery("SELECT value FROM kv WHERE key = ?", arrayOf(DONE)).use { it.moveToFirst() }
        if (!done) {
            db.beginTransaction()
            try {
                importSales(c, db)
                importReceipts(c, db)
                importMenu(c, db)
                db.insertWithOnConflict("kv", null, ContentValues().apply { put("key", DONE); put("value", System.currentTimeMillis().toString()) }, SQLiteDatabase.CONFLICT_REPLACE)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        // Kept as a backup, under a name nothing reads. Retried on every open until it works.
        for (name in listOf("sales.jsonl", "receipts.jsonl", "menu.json")) {
            val f = File(c.filesDir, name)
            if (f.exists()) f.renameTo(File(c.filesDir, "$name.imported"))
        }
    }

    private fun lines(c: Context, name: String): List<JSONObject> {
        val f = File(c.filesDir, name)
        if (!f.exists()) return emptyList()
        // One damaged line costs one entry, as it always did.
        return f.readLines().mapNotNull { if (it.isBlank()) null else try { JSONObject(it) } catch (_: Exception) { null } }
    }

    private fun importSales(c: Context, db: SQLiteDatabase) {
        for (o in lines(c, "sales.jsonl")) {
            val s = try { legacySale(o) } catch (_: Exception) { null } ?: continue
            insertBill(db, s)
        }
    }

    private fun insertBill(db: SQLiteDatabase, s: Sale) {
        val data = Bills.toData(s, null)
        db.insertWithOnConflict("bills", null, ContentValues().apply {
            put("id", s.uid)
            put("bill_no", s.id)
            put("day", s.day)
            put("time", s.time)
            put("letter", s.letter)
            put("token", s.token)
            put("phone", s.phone)
            put("payment", s.payment)
            put("customer", s.customer)
            put("voided", if (s.voided) 1 else 0)
            put("total", Bills.totalPaise(data))
            put("data", data.toString())
            put("updated_at", s.updatedAt)
            put("field_times", JSONObject(Bills.allGroups(s.updatedAt) as Map<*, *>).toString())
            put("dirty", 0)
            put("src", if (s.phone.isEmpty()) Src.OWN else Src.FEED)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** sales.jsonl as v0.6 wrote it. Bills from before cloud sync had no uid; they get one now. */
    internal fun legacySale(o: JSONObject): Sale {
        val arr = o.getJSONArray("lines")
        val lines = (0 until arr.length()).map { i ->
            val l = arr.getJSONObject(i)
            SaleLine(l.getString("name"), l.getDouble("price"), l.getInt("qty"), l.optString("note", ""))
        }
        val phone = o.optString("phone", "")
        return Sale(
            o.getString("id"), o.getInt("token"), o.getLong("time"), lines,
            o.optString("payment", "CASH"), o.optString("customer", ""), o.optBoolean("voided", false),
            uid = o.optString("uid", "").ifEmpty { UUID.randomUUID().toString() },
            updatedAt = o.optLong("updatedAt", o.getLong("time")),
            letter = o.optString("letter", ""),
            phone = phone,
            discount = o.optDouble("discount", 0.0).takeUnless { it.isNaN() } ?: 0.0,
            discountReason = o.optString("discountReason", ""),
            voidReason = o.optString("voidReason", ""),
            origin = phone,
        )
    }

    private fun importReceipts(c: Context, db: SQLiteDatabase) {
        for (o in lines(c, "receipts.jsonl")) {
            val r = try {
                Receipt(
                    uid = o.getString("uid"),
                    time = o.getLong("time"),
                    customer = o.getString("customer"),
                    amount = o.getDouble("amount"),
                    discount = o.optDouble("discount", 0.0).takeUnless { it.isNaN() } ?: 0.0,
                    mode = o.optString("mode", "CASH"),
                    note = o.optString("note", ""),
                    voided = o.optBoolean("voided", false),
                    voidReason = o.optString("voidReason", ""),
                    updatedAt = o.getLong("time"),
                )
            } catch (_: Exception) { null } ?: continue
            val data = Receipts.toData(r, null)
            db.insertWithOnConflict("receipts", null, ContentValues().apply {
                put("id", r.uid)
                put("day", r.day)
                put("time", r.time)
                put("customer", r.customer)
                put("voided", if (r.voided) 1 else 0)
                put("data", data.toString())
                put("updated_at", r.updatedAt)
                put("field_times", JSONObject(Receipts.allGroups(r.updatedAt) as Map<*, *>).toString())
                put("dirty", 0)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /**
     * menu.json becomes category and menu item records. Categories keep the
     * order they first appear in; items keep their order within each. A phone
     * that never had a menu file gets the starter menu, as before.
     */
    private fun importMenu(c: Context, db: SQLiteDatabase) {
        val f = File(c.filesDir, "menu.json")
        val items: List<MenuItem> = if (f.exists()) {
            try {
                val arr = JSONArray(f.readText())
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val va = o.optJSONArray("variants") ?: JSONArray()
                    MenuItem(
                        o.getString("id"), o.getString("name"), o.getDouble("price"), o.optString("category", ""),
                        (0 until va.length()).map { j -> Variant(va.getJSONObject(j).getString("label"), va.getJSONObject(j).getDouble("price")) },
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            Menu.parseLines(Menu.DEFAULT_MENU).items
        }
        val now = System.currentTimeMillis()
        val cats = LinkedHashMap<String, String>() // key → id
        items.forEachIndexed { i, item ->
            val cat = item.category.trim()
            val catId = if (cat.isEmpty()) null else cats.getOrPut(Menu.catKey(cat)) {
                val id = Store.stableId("cat", Menu.catKey(cat))
                write(db, Store.Rec("category", id, JSONObject().put("name", cat).put("sort", cats.size + 1), updatedAt = now))
                id
            }
            write(db, Store.Rec("menuItem", item.id, Menu.itemData(item.copy(sort = (i + 1).toDouble()), catId, null), updatedAt = now))
        }
    }

    private fun write(db: SQLiteDatabase, r: Store.Rec) {
        db.insertWithOnConflict("records", null, ContentValues().apply {
            put("kind", r.kind)
            put("id", r.id)
            put("data", r.data.toString())
            put("deleted", 0)
            put("updated_at", r.updatedAt)
            put("dirty", 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
}

internal fun JSONObject.putOrRemove(key: String, value: Any?) {
    if (value == null) remove(key) else put(key, value)
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null
