package com.parchipos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Keeps this phone and IceCreamOS in step, and through it every other phone.
 *
 * Offline-first: a sale is complete the moment it is in sales.jsonl, and this
 * only ever catches up. One run:
 *
 * 1. Sends every bill whose current state IceCreamOS has not got, as its whole
 *    state — so the first run uploads the phone's entire history, and each
 *    later void, restore, date or payment change follows. Bills of other
 *    phones edited here go the same way; IceCreamOS keeps the newest state.
 * 2. Pulls the feed of bills changed since last time, from every phone, into
 *    sales.jsonl. Sales then shows all phones, and any phone can edit any bill.
 * 3. Syncs the shared menu and shop details: sends this phone's change, or
 *    takes the other phones'.
 *
 * What has been sent is kept apart in synced.json (uid → updatedAt sent).
 */
object Sync {
    private const val FILE = "synced.json"
    /** Matches the server's per-request cap. */
    private const val BATCH = 10
    private const val FEED_PAGE = 200

    private val running = AtomicBoolean(false)

    private class SyncError(msg: String) : Exception(msg)

    fun configured(c: Context) = Prefs.syncKey(c).isNotEmpty()

    /** Sales whose current state the server has not acknowledged, oldest edit first. */
    fun pending(c: Context): List<Sale> {
        val sent = sent(c)
        return Sales.all(c).filter { (sent[it.uid] ?: -1L) < it.updatedAt }.sortedBy { it.updatedAt }
    }

    /**
     * Starts a sync in the background unless one is running or there is no
     * key. Safe to call often: after every sale, on every return to the till.
     * [done] runs on the sync thread with an error message, or "" on success.
     */
    fun kick(c: Context, done: ((String) -> Unit)? = null) {
        if (!configured(c) || !running.compareAndSet(false, true)) return
        val app = c.applicationContext
        thread(name = "parchi-sync") {
            val err = try { runOnce(app) } catch (e: Exception) { e.message ?: e.javaClass.simpleName } finally { running.set(false) }
            done?.invoke(err)
        }
    }

    private fun runOnce(c: Context): String = try {
        val versions = pushBills(c)
        pullFeed(c)
        syncShared(c, versions)
        Prefs.saveSyncStatus(c, "")
        ""
    } catch (e: SyncError) {
        fail(c, e.message ?: "Sync failed")
    }

    /** Step 1. Returns the shared docs' versions from the last reply. */
    private fun pushBills(c: Context): JSONObject {
        val url = Prefs.serverUrl(c) + "/api/pos-sync/bills"
        var last: JSONObject? = null
        while (true) {
            val batch = pending(c).take(BATCH)
            // With nothing to send, one empty batch still fetches counts and
            // versions, and lets the server spend the request posting this
            // phone's queued bills, so a backlog drains on the timer.
            if (batch.isEmpty() && last != null) break
            val body = JSONObject().put("bills", JSONArray().apply { batch.forEach { put(toJson(it)) } })
            val res = call(c, "POST", url, body.toString())

            val acked = HashSet<String>()
            val results = res.optJSONArray("results") ?: JSONArray()
            for (i in 0 until results.length()) {
                val r = results.getJSONObject(i)
                // Anything stored server-side is the server's to post from here;
                // INVALID means this app sent something broken, so keep it.
                if (r.optString("status") != "INVALID") acked.add(r.optString("uid"))
            }
            if (batch.isNotEmpty() && acked.isEmpty()) {
                throw SyncError("Server refused bills: " + results.optJSONObject(0)?.optString("reason"))
            }
            markSent(c, batch.filter { it.uid in acked })
            absorb(c, res)
            Prefs.saveSyncStatus(c, "", res.optString("device"), res.optJSONObject("counts")?.toString() ?: "")
            last = res
            if (batch.isEmpty()) break
        }
        return last?.optJSONObject("shared") ?: JSONObject()
    }

    /** Step 2: every phone's bills changed since the cursor. */
    private fun pullFeed(c: Context) {
        while (true) {
            val since = Prefs.feedCursor(c)
            val url = Prefs.serverUrl(c) + "/api/pos-sync/feed?limit=$FEED_PAGE" +
                if (since.isEmpty()) "" else "&since=" + URLEncoder.encode(since, "UTF-8")
            val res = call(c, "GET", url, null)
            absorb(c, res)
            val me = Prefs.letter(c)
            val arr = res.optJSONArray("bills") ?: JSONArray()
            val remote = (0 until arr.length()).map { fromFeed(arr.getJSONObject(it), me) }
            val settled = Sales.applyRemote(c, remote)
            markSent(c, remote.filter { it.uid in settled })
            val cursor = res.optString("cursor", "")
            if (cursor.isNotEmpty() && cursor != "null") Prefs.setFeedCursor(c, cursor)
            if (!res.optBoolean("more") || remote.isEmpty()) break
        }
    }

    private fun fromFeed(o: JSONObject, me: String): Sale {
        val la = o.optJSONArray("lines") ?: JSONArray()
        val letter = o.optString("letter", "")
        return Sale(
            id = o.getString("billNo"),
            token = o.optInt("token"),
            time = o.getLong("time"),
            lines = (0 until la.length()).map { i ->
                val l = la.getJSONObject(i)
                SaleLine(l.getString("name"), l.getDouble("price"), l.getInt("qty"), l.optString("note", ""))
            },
            payment = o.optString("payment", "CASH"),
            customer = o.optString("customer", ""),
            voided = o.optBoolean("voided", false),
            uid = o.getString("uid"),
            updatedAt = o.getLong("updatedAt"),
            letter = letter,
            // A bill this phone rang up is its own, whichever phone edited it last.
            phone = if (me.isNotEmpty() && letter == me) "" else o.optString("phone", "?"),
        )
    }

    /** Step 3: the shared menu and shop details. */
    private fun syncShared(c: Context, versions: JSONObject) {
        var docs: JSONObject? = null
        for (key in listOf("menu", "settings")) {
            val local = Prefs.sharedVersion(c, key)
            val server = versions.optInt(key, 0)
            // The first phone to sync seeds a doc no phone has shared yet.
            if (Prefs.sharedDirty(c, key) || server == 0) {
                val json = localJson(c, key)
                val res = try { call(c, "PUT", Prefs.serverUrl(c) + "/api/pos-sync/shared",
                    JSONObject().put("key", key).put("baseVersion", if (server == 0) 0 else local).put("json", json).toString())
                } catch (e: Conflict) {
                    // Another phone changed it first: theirs stands, this phone takes it.
                    e.body.optString("json").takeIf { it.isNotEmpty() && it != "null" }?.let { applyLocal(c, key, it) }
                    Prefs.setSharedVersion(c, key, e.body.optInt("version"))
                    Prefs.setSharedDirty(c, key, false)
                    Prefs.setSyncNote(c, c.getString(R.string.sync_conflict, if (key == "menu") "The menu" else "Shop details"))
                    continue
                }
                Prefs.setSharedVersion(c, key, res.optInt("version"))
                // Edited again while this was being sent: leave it to go next time.
                if (localJson(c, key) == json) Prefs.setSharedDirty(c, key, false)
            } else if (server != local) {
                val d = docs ?: call(c, "GET", Prefs.serverUrl(c) + "/api/pos-sync/shared", null).also { docs = it }
                val doc = d.optJSONObject(key) ?: continue
                val json = doc.optString("json")
                if (json.isNotEmpty() && json != "null" && applyLocal(c, key, json)) Prefs.setSharedVersion(c, key, doc.optInt("version"))
            }
        }
    }

    private fun localJson(c: Context, key: String) = if (key == "menu") Menu.sharedJson(c) else Prefs.shopJson(c)
    private fun applyLocal(c: Context, key: String, json: String) =
        if (key == "menu") Menu.applyShared(c, json) else Prefs.applyShopJson(c, json)

    /** This phone's token letter, which IceCreamOS hands out on the first sync. New bills carry it. */
    private fun absorb(c: Context, res: JSONObject) {
        val letter = res.optString("letter", "")
        if (letter.isNotEmpty() && letter != Prefs.letter(c)) Prefs.setLetter(c, letter)
    }

    private class Conflict(val body: JSONObject) : Exception("conflict")

    /** One request with this phone's key; throws SyncError on anything but 2xx (Conflict for 409). */
    private fun call(c: Context, method: String, url: String, json: String?): JSONObject {
        val (code, text) = try { request(method, url, Prefs.syncKey(c), json) } catch (e: Exception) {
            throw SyncError("Offline or server unreachable (${e.javaClass.simpleName})")
        }
        if (code == 401) throw SyncError("Key not recognised — check it in IceCreamOS")
        val body = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
        if (code == 409) throw Conflict(body)
        if (code !in 200..299) throw SyncError("Server error $code")
        return body
    }

    private fun fail(c: Context, msg: String): String {
        Prefs.saveSyncStatus(c, msg)
        return msg
    }

    private fun request(method: String, url: String, key: String, json: String?): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10000
            // Posting a batch runs stock and accounting for each bill server-side.
            readTimeout = 60000
            setRequestProperty("Authorization", "Bearer $key")
            if (json != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (json != null) conn.outputStream.use { it.write(json.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }

    private fun toJson(s: Sale): JSONObject = JSONObject()
        .put("uid", s.uid)
        .put("billNo", s.id)
        .put("token", s.token)
        .put("time", s.time)
        .put("updatedAt", s.updatedAt)
        .put("payment", s.payment)
        .put("customer", s.customer)
        .put("voided", s.voided)
        .put("lines", JSONArray().apply {
            s.lines.forEach { put(JSONObject().put("name", it.name).put("price", it.price).put("qty", it.qty).put("note", it.note)) }
        })

    // ── synced.json ─────────────────────────────────────────────────────

    /**
     * A different key or server is a different device record in IceCreamOS,
     * which has none of these bills yet: start again from the whole history,
     * the whole feed, and the shared docs as that server has them.
     */
    @Synchronized
    fun forget(c: Context) {
        File(c.filesDir, FILE).delete()
        Prefs.setFeedCursor(c, "")
        Prefs.setLetter(c, "")
        for (key in listOf("menu", "settings")) Prefs.setSharedVersion(c, key, 0)
    }

    @Synchronized
    private fun sent(c: Context): Map<String, Long> {
        val f = File(c.filesDir, FILE)
        if (!f.exists()) return emptyMap()
        return try {
            val o = JSONObject(f.readText())
            o.keys().asSequence().associateWith { o.getLong(it) }
        } catch (_: Exception) {
            emptyMap() // unreadable: resend everything; the server ignores what it already has
        }
    }

    @Synchronized
    private fun markSent(c: Context, sales: List<Sale>) {
        val o = JSONObject(sent(c))
        sales.forEach { o.put(it.uid, it.updatedAt) }
        val tmp = File(c.filesDir, "$FILE.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(File(c.filesDir, FILE))
    }
}
