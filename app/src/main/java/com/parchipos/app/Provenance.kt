package com.parchipos.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File

/**
 * Where a bill or payment on this device came from. It decides what goes up
 * when the device joins an account (only its own) and what comes off when it
 * leaves one (whatever the account gave it).
 */
object Src {
    /** Rung up on this device, or held from before Parchi Cloud as its own. */
    const val OWN = "own"
    /** Another phone's bill, seen through the IceCreamOS feed before Parchi Cloud. Kept, never uploaded. */
    const val FEED = "feed"
    /** Came from Parchi Cloud: another device's, or a replaced device's. Off again on leaving the account. */
    const val CLOUD = "cloud"
}

/**
 * The move to parchi.db version 2, which notes each row's [Src].
 *
 * A device that never connected knows exactly: a bill with no device name is
 * its own, one with a name came through the IceCreamOS feed.
 *
 * A device that has synced didn't record where its rows came from, and a
 * device id alone can't tell: a device that took over another's slot (Replace
 * in the portal) carries that device's id, so the other device's bills looked
 * like its own and went up again on the next connect. What it does still have
 * are the v0.6 files it started from (sales.jsonl.imported,
 * receipts.jsonl.imported), so:
 *
 * 1. A bill in those files is what it was there: its own, or a feed bill.
 * 2. A bill that has never been near the cloud (no device id) is its own if
 *    it has no device name, else a feed bill.
 * 3. A bill rung up under its current connection (its device id now) is its own.
 * 4. Anything else came from the cloud.
 *
 * Payments go the same way (they were never shared, so the file has only its own).
 */
internal object Provenance {

    fun upgrade(c: Context, db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE bills ADD COLUMN src TEXT NOT NULL DEFAULT '${Src.OWN}'")
        db.execSQL("ALTER TABLE receipts ADD COLUMN src TEXT NOT NULL DEFAULT '${Src.OWN}'")

        val me = db.rawQuery("SELECT value FROM kv WHERE key = 'cloud.me'", null).use { if (it.moveToFirst()) it.getString(0) else null }
            ?.let { try { JSONObject(it).optString("deviceId").ifEmpty { null } } catch (_: Exception) { null } }
        val imported = importedBills(c)
        val importedReceipts = importedReceipts(c)

        val bills = ArrayList<Pair<String, String>>() // id → src
        db.rawQuery("SELECT id, bill_no, time, phone, data FROM bills", null).use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getString(0)
                val phone = cur.getString(3)
                val device = try { JSONObject(cur.getString(4)).optStringOrNull("deviceId") } catch (_: Exception) { null }
                val fromFile = imported[id] ?: imported[key(cur.getString(1), cur.getLong(2))]
                bills += id to when {
                    fromFile != null -> fromFile
                    device == null -> if (phone.isEmpty()) Src.OWN else Src.FEED
                    device == me -> Src.OWN
                    else -> Src.CLOUD
                }
            }
        }
        for ((id, src) in bills) {
            // A bill of its own is shown as this device's, whatever device id an earlier connection gave it.
            if (src == Src.OWN) db.execSQL("UPDATE bills SET src = ?, phone = '' WHERE id = ?", arrayOf(src, id))
            else db.execSQL("UPDATE bills SET src = ? WHERE id = ?", arrayOf(src, id))
        }

        val receipts = ArrayList<Pair<String, String>>()
        db.rawQuery("SELECT id, data FROM receipts", null).use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getString(0)
                val device = try { JSONObject(cur.getString(1)).optStringOrNull("deviceId") } catch (_: Exception) { null }
                receipts += id to if (id in importedReceipts || device == null || device == me) Src.OWN else Src.CLOUD
            }
        }
        for ((id, src) in receipts) db.execSQL("UPDATE receipts SET src = ? WHERE id = ?", arrayOf(src, id))
    }

    private fun key(billNo: String, time: Long) = "$billNo|$time"

    /** Bills in sales.jsonl.imported, by uid and by bill number and time (old lines had no uid): own or feed. */
    private fun importedBills(c: Context): Map<String, String> {
        val out = HashMap<String, String>()
        lines(File(c.filesDir, "sales.jsonl.imported")).forEach { o ->
            val src = if (o.optString("phone", "").isEmpty()) Src.OWN else Src.FEED
            o.optString("uid", "").takeIf { it.isNotEmpty() }?.let { out[it] = src }
            if (o.has("id") && o.has("time")) out[key(o.optString("id"), o.optLong("time"))] = src
        }
        return out
    }

    private fun importedReceipts(c: Context): Set<String> =
        lines(File(c.filesDir, "receipts.jsonl.imported")).mapNotNullTo(HashSet()) { it.optString("uid", "").ifEmpty { null } }

    private fun lines(f: File): List<JSONObject> {
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { if (it.isBlank()) null else try { JSONObject(it) } catch (_: Exception) { null } }
    }
}
