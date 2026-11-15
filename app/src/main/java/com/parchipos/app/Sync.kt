package com.parchipos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Sends bills to IceCreamOS, where they become ordinary retail sales.
 *
 * Offline-first: a sale is complete the moment it is in sales.jsonl, and this
 * only ever catches up. Each bill goes as its whole current state; a bill is
 * sent again whenever it changes (void, restore, payment, date), and the
 * server keeps the newest. The first sync on a phone therefore uploads its
 * entire history, which is how bills from before sync existed get in.
 *
 * What has been sent is kept apart in synced.json (uid → updatedAt sent), so
 * syncing never rewrites the sales file a till depends on.
 */
object Sync {
    private const val FILE = "synced.json"
    /** Matches the server's per-request cap. */
    private const val BATCH = 10

    private val running = AtomicBoolean(false)

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

    private fun runOnce(c: Context): String {
        val url = Prefs.serverUrl(c) + "/api/pos-sync/bills"
        val key = Prefs.syncKey(c)
        var sentAny = false
        while (true) {
            val batch = pending(c).take(BATCH)
            if (batch.isEmpty()) break
            val body = JSONObject().put("bills", JSONArray().apply { batch.forEach { put(toJson(it)) } })

            val (code, text) = try { post(url, key, body.toString()) } catch (e: Exception) {
                return fail(c, "Offline or server unreachable (${e.javaClass.simpleName})")
            }
            if (code == 401) return fail(c, "Key not recognised — check it in IceCreamOS")
            if (code !in 200..299) return fail(c, "Server error $code")

            val res = JSONObject(text)
            val acked = HashSet<String>()
            val results = res.optJSONArray("results") ?: JSONArray()
            for (i in 0 until results.length()) {
                val r = results.getJSONObject(i)
                // Anything stored server-side is the server's to post from here;
                // INVALID means this app sent something broken, so keep it.
                if (r.optString("status") != "INVALID") acked.add(r.optString("uid"))
            }
            if (acked.isEmpty()) return fail(c, "Server refused bills: " + results.optJSONObject(0)?.optString("reason"))
            markSent(c, batch.filter { it.uid in acked })
            Prefs.saveSyncStatus(c, "", res.optString("device"), res.optJSONObject("counts")?.toString() ?: "")
            sentAny = true
        }
        if (!sentAny) refreshStatus(c)
        return ""
    }

    /**
     * Nothing to send: an empty batch still gets the server's counts for
     * Settings, and lets the server spend the request posting this phone's
     * queued or parked bills — so a backlog drains on the five-minute timer.
     */
    private fun refreshStatus(c: Context) {
        val (code, text) = try {
            post(Prefs.serverUrl(c) + "/api/pos-sync/bills", Prefs.syncKey(c), "{\"bills\":[]}")
        } catch (e: Exception) {
            fail(c, "Offline or server unreachable (${e.javaClass.simpleName})"); return
        }
        if (code == 401) { fail(c, "Key not recognised — check it in IceCreamOS"); return }
        if (code !in 200..299) { fail(c, "Server error $code"); return }
        val res = try { JSONObject(text) } catch (_: Exception) { fail(c, "Unexpected reply from server"); return }
        Prefs.saveSyncStatus(c, "", res.optString("device"), res.optJSONObject("counts")?.toString() ?: "")
    }

    private fun fail(c: Context, msg: String): String {
        Prefs.saveSyncStatus(c, msg)
        return msg
    }

    private fun post(url: String, key: String, json: String): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10000
            // Posting a batch runs stock and accounting for each bill server-side.
            readTimeout = 60000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $key")
        }
        try {
            conn.outputStream.use { it.write(json.toByteArray()) }
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
     * which has none of these bills yet: start again from the whole history.
     */
    @Synchronized
    fun forget(c: Context) {
        File(c.filesDir, FILE).delete()
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
