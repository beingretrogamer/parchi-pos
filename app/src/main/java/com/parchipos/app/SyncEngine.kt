package com.parchipos.app

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Keeps this phone and Parchi Cloud in step, and through it every other
 * phone of the outlet. Local first: a bill is complete the moment it is in
 * parchi.db, and this only ever catches up.
 *
 * One round trip carries this phone's status, then its unsent edits (up to
 * [PUSH_BATCH]), then asks for everything changed since its cursor. Rounds
 * repeat until nothing is left to send or fetch.
 *
 * - **Pushing:** the server merges each change and acks it with a sequence
 *   number. A row is marked clean only if it hasn't been edited since it was
 *   sent. If the server holds something newer, the ack carries it and it
 *   replaces the local copy.
 * - **Pulling:** each record is merged with any unsent local edit using the
 *   same field-group merge as the server (Protocol.merge), so a void here and
 *   a payment change there both survive, whatever the order.
 * - **First download:** a new phone asks for a window (§5.1 of the design):
 *   everything account-wide, the outlet's settings and customers, every credit
 *   bill and payment, and the last 90 days of bills. Older days load when
 *   someone opens them.
 */
object SyncEngine {
    const val PUSH_BATCH = 100
    const val PULL_LIMIT = 500
    const val WINDOW_DAYS = 90
    private const val MAX_ROUNDS = 60

    private val running = AtomicBoolean(false)
    private val again = AtomicBoolean(false)
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val ui by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var diagSent = false
    /** Refused bills and payments go again once each time the app starts ([Store.retryRejected]). */
    @Volatile internal var retried = false

    /** Screens listen to refresh when a sync brought something or the status changed. Called on the main thread. */
    fun listen(l: () -> Unit) { listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }

    /** Something arrived outside a sync round (a live update): screens refresh. */
    fun changed() = notifyListeners()

    private fun notifyListeners() {
        if (listeners.isEmpty()) return
        try { ui.post { listeners.forEach { it() } } } catch (_: Exception) {}
    }

    /**
     * Starts a sync in the background, or asks the running one to go round
     * again. Cheap to call after every sale and on a timer. [done] runs on the
     * sync thread with an error, or "" on success.
     */
    fun kick(c: Context, done: ((String) -> Unit)? = null) {
        if (!Cloud.connected(c)) return
        if (!running.compareAndSet(false, true)) {
            again.set(true)
            return
        }
        val app = c.applicationContext
        thread(name = "parchi-cloud-sync") {
            var err: String
            try {
                do {
                    again.set(false)
                    err = runOnce(app)
                } while (again.get() && err.isEmpty())
            } finally {
                running.set(false)
            }
            notifyListeners()
            done?.invoke(err)
        }
    }

    /** One full sync, blocking. Returns "" or what went wrong. */
    fun runOnce(c: Context): String {
        val me = Cloud.me(c) ?: return "Not connected"
        return try {
            Store.put(c, "cloud.upgrade", null)
            if (!retried) {
                retried = true
                Store.retryRejected(c, me.deviceId)
            }
            var rounds = 0
            while (rounds++ < MAX_ROUNDS) {
                // Read fresh each round: a rename in the portal changes this phone's name.
                if (!round(c, Cloud.me(c) ?: me)) break
            }
            Store.put(c, "cloud.lastOk", System.currentTimeMillis().toString())
            Store.put(c, "cloud.error", null)
            ""
        } catch (e: CloudApi.ApiError) {
            val msg = when {
                e.status == 401 -> "This device's key no longer works: it was removed or replaced in Parchi Cloud."
                e.status == 403 && e.code == "account_closed" -> "This account is closed. The till keeps working on its own."
                else -> e.message ?: "Sync failed"
            }
            if (e.status == 401) Store.put(c, "cloud.removed", "1")
            Store.put(c, "cloud.error", if (e.offline) "offline:$msg" else msg)
            diagSent = false
            msg
        } catch (e: Exception) {
            val msg = "Sync failed: ${e.javaClass.simpleName}${e.message?.let { " ($it)" } ?: ""}"
            Store.put(c, "cloud.error", msg)
            diagSent = false
            msg
        } finally {
            notifyListeners()
        }
    }

    /** One round trip. True if there is more to do. */
    private fun round(c: Context, me: Cloud.Me): Boolean {
        val messages = JSONArray()
        messages.put(JSONObject().put("type", "status").put("appVersion", appVersion(c)).put("outbox", Store.outboxCount(c)).put("clientTime", System.currentTimeMillis()))
        if (!diagSent) messages.put(diag(c))

        val sent = Store.outbox(c, PUSH_BATCH)
        if (sent.isNotEmpty()) messages.put(JSONObject().put("type", "push").put("req", System.currentTimeMillis()).put("changes", JSONArray().apply { sent.forEach { put(change(it)) } }))

        val removed = Store.get(c, "cloud.removed") == "1"
        val windowDone = Store.get(c, "cloud.windowDone") == "1"
        val cursor = Store.get(c, "cloud.cursor")?.toLongOrNull() ?: 0L
        if (!removed) {
            val pull = JSONObject().put("type", "pull").put("after", cursor).put("limit", PULL_LIMIT)
            if (!windowDone) {
                pull.put("window", JSONObject().put("fromDay", fromDay(c)))
                Store.get(c, "cloud.windowUpTo")?.toLongOrNull()?.let { pull.put("upTo", it) }
            }
            messages.put(pull)
        }

        val t0 = System.currentTimeMillis()
        // Over the live socket when it is open; otherwise (or if it doesn't answer) over HTTP.
        val replies = Live.round(messages) ?: CloudApi.sync(me.server, me.deviceKey, messages)
        val t1 = System.currentTimeMillis()
        diagSent = true

        var more = false
        var upgrade = false
        for (i in 0 until replies.length()) {
            val m = replies.getJSONObject(i)
            when (m.optString("type")) {
                "hello" -> hello(c, me, m, (t0 + t1) / 2)
                "upgrade" -> {
                    upgrade = true
                    Store.put(c, "cloud.upgrade", "1")
                    Store.put(c, "cloud.minApp", m.optString("minAppVersion"))
                }
                "ack" -> ack(c, me, m.optJSONArray("results") ?: JSONArray(), sent)
                "changes" -> if (changes(c, me, m)) more = true
            }
        }
        if (upgrade) return false // sync waits for the update; the outbox keeps everything
        // Go round again while the server has more, or while anything is still unsent
        // (a full batch, or an edit made at the counter while this round was in flight).
        return more || Store.outboxCount(c) > 0
    }

    // ── messages out ────────────────────────────────────────────────────

    internal fun change(r: Store.Raw): JSONObject = JSONObject()
        .put("kind", r.kind)
        .put("id", r.id)
        .put("updatedAt", r.updatedAt)
        .put("deleted", r.deleted)
        .put("data", r.data)
        .apply {
            r.fieldTimes?.takeIf { it.isNotEmpty() }?.let { put("fieldTimes", JSONObject(it as Map<*, *>)) }
            if (r.byUser != null && r.proof != null) { put("byUser", r.byUser); put("proof", r.proof) }
        }

    private fun diag(c: Context): JSONObject {
        val free = try { StatFs(c.filesDir.path).let { it.availableBytes / (1024 * 1024) } } catch (_: Exception) { -1L }
        val bill = Prefs.billPrinter(c)
        val kitchen = Prefs.kitchenPrinter(c)
        return JSONObject()
            .put("type", "diag")
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android", Build.VERSION.RELEASE)
            .put("freeStorageMb", free)
            .put("syncErrors", JSONArray(Store.rejected(c)))
            .put("printLog", JSONArray(PrintLog.text().lines().take(50)))
            .put("printers", JSONArray()
                .put(JSONObject().put("role", "Bill").put("kind", if (bill.bluetooth) "Bluetooth ${bill.label}" else "Wi-Fi ${bill.label}"))
                .put(JSONObject().put("role", "Kitchen").put("kind", if (kitchen.sameAsBill) "Same as bill" else if (kitchen.target.bluetooth) "Bluetooth ${kitchen.target.label}" else "Wi-Fi ${kitchen.target.label}")))
    }

    /** The first download's oldest day, kept so Sales knows which days to fetch when opened. */
    private fun fromDay(c: Context): String {
        Store.get(c, "cloud.historyFrom")?.let { return it }
        val d = Calendar.getInstance().apply { timeInMillis = Cloud.now(c); add(Calendar.DAY_OF_MONTH, -WINDOW_DAYS) }
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(d.time)
        Store.put(c, "cloud.historyFrom", day)
        return day
    }

    // ── messages in ─────────────────────────────────────────────────────

    private fun hello(c: Context, me: Cloud.Me, m: JSONObject, midpoint: Long) {
        m.optLong("serverTime").takeIf { it > 0 }?.let { Cloud.setClockOffset(c, it - midpoint) }
        m.optJSONArray("devices")?.let { Store.put(c, "cloud.devices", it.toString()) }
        Cloud.raiseInvoiceSerials(c, m.optJSONObject("invoiceSerials"))
        Store.put(c, "cloud.minApp", m.optString("minAppVersion").ifEmpty { null })
        Store.put(c, "cloud.latestApp", m.optString("latestAppVersion").ifEmpty { null })
        val d = m.optJSONObject("device")
        if (d != null) {
            Store.put(c, "cloud.removed", if (d.optString("status") == "removed") "1" else null)
            val name = d.optString("name")
            val letter = d.optString("letter")
            if ((name.isNotEmpty() && name != me.name) || (letter.isNotEmpty() && letter != me.letter)) {
                Cloud.saveMe(c, me.copy(name = name.ifEmpty { me.name }, letter = letter.ifEmpty { me.letter }))
            }
        }
    }

    private fun ack(c: Context, me: Cloud.Me, results: JSONArray, sent: List<Store.Raw>) {
        val byKey = sent.associateBy { it.kind + "\u0000" + it.id }
        Store.tx(c) {
            for (i in 0 until results.length()) {
                val r = results.getJSONObject(i)
                val kind = r.optString("kind")
                val id = r.optString("id")
                val mine = byKey[kind + "\u0000" + id] ?: continue
                when {
                    r.has("rejected") && Store.tableOf(kind) == "records" && kind in SIGNED_KINDS && r.optString("rejected").startsWith("needs a") ->
                        revert(c, me, kind, id, r.optString("rejected"), r.optJSONObject("record"))
                    r.has("rejected") -> Store.markRejected(c, kind, id, r.optString("rejected"))
                    r.has("record") -> {
                        // The server holds something else (merged, or newer): take it, unless edited here meanwhile.
                        val now = Store.raw(c, kind, id)
                        if (now != null && now.updatedAt == mine.updatedAt) applyRecord(c, me, r.getJSONObject("record"), forceClean = true)
                        else applyRecord(c, me, r.getJSONObject("record"))
                    }
                    else -> Store.markSent(c, kind, id, mine.updatedAt, r.optLong("seq"))
                }
            }
        }
    }

    /** Kinds the server takes from a device only with a manager's PIN (menu, header, rules, sold out). */
    private val SIGNED_KINDS = setOf("category", "menuItem", "outlet", "rules", "itemStatus")

    /**
     * A menu or header edit refused for want of a PIN (made offline, say, by
     * someone whose PIN has changed since): back to the server's copy, or
     * gone if the server never had it, and a note on the Parchi Cloud screen.
     * Sales are never undone this way; only these shared settings.
     */
    private fun revert(c: Context, me: Cloud.Me, kind: String, id: String, reason: String, server: JSONObject?) {
        val name = Store.record(c, kind, id)?.data?.optString("name")?.takeIf { it.isNotBlank() } ?: "$kind $id"
        if (server != null) applyRecord(c, me, server, forceClean = true) else Store.deleteRow(c, kind, id)
        Store.noteProblem(c, c.getString(R.string.reverted_menu, name, reason))
    }

    /** Applies a page of records. True if the server has more. */
    private fun changes(c: Context, me: Cloud.Me, m: JSONObject): Boolean {
        val records = m.optJSONArray("records") ?: JSONArray()
        Store.tx(c) {
            for (i in 0 until records.length()) applyRecord(c, me, records.getJSONObject(i))
            val more = m.optBoolean("more")
            if (m.optBoolean("window")) {
                val upTo = m.optLong("upTo")
                Store.put(c, "cloud.windowUpTo", upTo.toString())
                if (more) Store.put(c, "cloud.cursor", m.optLong("last").toString())
                else {
                    Store.put(c, "cloud.cursor", upTo.toString())
                    Store.put(c, "cloud.windowDone", "1")
                    Store.put(c, "cloud.windowUpTo", null)
                    afterFirstDownload(c)
                }
            } else {
                Store.put(c, "cloud.cursor", m.optLong("last").toString())
            }
        }
        return m.optBoolean("more")
    }

    /**
     * One record from the server, merged with any unsent local edit. With
     * [forceClean] (an ack for exactly what this phone sent), the server's
     * state simply replaces the local one.
     */
    internal fun applyRecord(c: Context, me: Cloud.Me, r: JSONObject, forceClean: Boolean = false) {
        val kind = r.getString("kind")
        val id = r.getString("id")
        val remote = Protocol.Version(
            updatedAt = r.getLong("updatedAt"),
            fieldTimes = r.optJSONObject("fieldTimes")?.let { o -> o.keys().asSequence().associateWith { o.getLong(it) } },
            byDevice = r.optStringOrNull("byDevice"),
            deleted = r.optBoolean("deleted", false),
            data = r.getJSONObject("data"),
        )
        val seq = r.optLong("seq")
        val local = Store.raw(c, kind, id)
        // Never backwards: a page can carry a state older than one an ack already gave this row.
        if (local != null && !local.dirty && !forceClean && seq in 1 until local.seq) return
        val state: Protocol.Version
        val dirty: Boolean
        if (local != null && local.dirty && !forceClean) {
            val mine = Protocol.Version(local.updatedAt, local.fieldTimes, me.deviceId, local.deleted, local.data)
            val merged = Protocol.merge(kind, mine, remote)
            dirty = !Protocol.sameState(kind, merged, remote)
            state = if (dirty) merged else remote
        } else {
            state = remote
            dirty = false
        }
        // A bill or payment already here keeps where it came from; anything new came from the cloud.
        val src = local?.src ?: Src.CLOUD
        when (kind) {
            "bill" -> if (state.deleted && !dirty) Store.deleteRow(c, kind, id) else {
                val d = state.data
                val phone = if (src == Src.OWN) "" else Cloud.phoneOf(c, d.optStringOrNull("deviceId"), d.optString("origin", ""), d.optString("letter", ""))
                Store.putBillRecord(c, id, d, state.updatedAt, state.fieldTimes, seq, dirty, phone, src)
            }
            "receipt" -> if (state.deleted && !dirty) Store.deleteRow(c, kind, id)
                else Store.putReceiptRecord(c, id, state.data, state.updatedAt, state.fieldTimes, seq, dirty, src)
            else -> Store.saveRecord(c, Store.Rec(
                kind = kind, id = id, data = state.data, outletId = r.optStringOrNull("outletId"),
                deleted = state.deleted, updatedAt = state.updatedAt,
                byUser = if (dirty) local?.byUser else r.optStringOrNull("byUser"),
                proof = if (dirty) local?.proof else null,
                seq = seq, dirty = dirty,
                fieldTimes = state.fieldTimes?.takeIf { Protocol.FIELD_GROUPS.containsKey(kind) },
            ))
        }
    }

    /**
     * Once the first download is in: if the account has a menu, it replaces
     * this device's (saved as a backup file at connect). If it has none and
     * the device had one, the Parchi Cloud screen offers to upload it. (Only
     * this download sets a menu record's sequence number: connecting cleared
     * any an earlier account left.)
     */
    private fun afterFirstDownload(c: Context) {
        val accountMenu = Store.records(c, "menuItem").any { it.seq > 0 }
        if (accountMenu) {
            Store.db(c).delete("records", "kind IN ('category','menuItem') AND seq = 0 AND dirty = 0", null)
            Store.put(c, "cloud.menuChoice", null)
        } else if (Store.get(c, "cloud.localMenu") == "1" && Store.records(c, "menuItem").isNotEmpty()) {
            Store.put(c, "cloud.menuChoice", "ask")
        }
        val header = Cloud.outlet(c)?.optJSONObject("header")
        val cloudHeader = header != null && listOf("address", "phone", "gstin", "footer").any { header.optString(it).isNotBlank() }
        Store.put(c, "cloud.headerChoice", if (!cloudHeader && Store.get(c, "cloud.localHeader") != null) "ask" else null)
    }

    // ── older days ──────────────────────────────────────────────────────

    /** Fetches one day before the download window into the store. Blocking; false if it couldn't. */
    fun loadDay(c: Context, day: String): Boolean {
        val me = Cloud.me(c) ?: return false
        if (Store.get(c, "cloud.day.$day") == "1") return true
        return try {
            val replies = CloudApi.day(me.server, me.deviceKey, day)
            Store.tx(c) {
                for (i in 0 until replies.length()) {
                    val recs = replies.getJSONObject(i).optJSONArray("records") ?: continue
                    for (j in 0 until recs.length()) applyRecord(c, me, recs.getJSONObject(j))
                }
                Store.put(c, "cloud.day.$day", "1")
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Whether a day is older than what the first download fetched. */
    fun needsFetch(c: Context, day: String): Boolean {
        if (!Cloud.connected(c)) return false
        val from = Store.get(c, "cloud.historyFrom") ?: return false
        return day < from && Store.get(c, "cloud.day.$day") != "1"
    }

    @Suppress("UNUSED_PARAMETER")
    fun appVersion(c: Context): String = BuildConfig.VERSION_NAME
}
