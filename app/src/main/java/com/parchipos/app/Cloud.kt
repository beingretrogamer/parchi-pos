package com.parchipos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * This phone's place in Parchi Cloud: which account and outlet it belongs
 * to, its letter, its key, and what the last sync learnt (the clock offset,
 * the other phones, the app versions).
 *
 * All of it lives in parchi.db's kv table, next to the data it describes. A
 * phone that never connects has none of it and works exactly as before.
 */
object Cloud {

    /** Staging until our shop moves (milestone 4); then the production URL. */
    const val DEFAULT_SERVER = "https://parchi-cloud-staging.sagatheretro.workers.dev"

    data class Me(
        val server: String,
        val deviceId: String,
        val deviceKey: String,
        val accountId: String,
        val accountName: String,
        val outletId: String,
        val outletName: String,
        val letter: String,
        val name: String,
        val connectedAt: Long,
    )

    data class Phone(val id: String, val name: String, val letter: String, val active: Boolean)

    /** What the status line says. */
    enum class State { STANDALONE, LIVE, WAITING, OFFLINE, ERROR, UPGRADE, REMOVED }

    private const val ME = "cloud.me"

    fun me(c: Context): Me? = Store.get(c, ME)?.let { s ->
        try {
            val o = JSONObject(s)
            Me(o.getString("server"), o.getString("deviceId"), o.getString("deviceKey"), o.getString("accountId"), o.optString("accountName"),
                o.getString("outletId"), o.optString("outletName"), o.getString("letter"), o.optString("name"), o.optLong("connectedAt"))
        } catch (_: Exception) {
            null
        }
    }

    fun connected(c: Context): Boolean = me(c) != null

    internal fun saveMe(c: Context, m: Me) = Store.put(c, ME, JSONObject()
        .put("server", m.server).put("deviceId", m.deviceId).put("deviceKey", m.deviceKey)
        .put("accountId", m.accountId).put("accountName", m.accountName)
        .put("outletId", m.outletId).put("outletName", m.outletName)
        .put("letter", m.letter).put("name", m.name).put("connectedAt", m.connectedAt).toString())

    /** The letter new bills carry: the cloud's once connected, else the old IceCreamOS one (or none). */
    fun letter(c: Context): String = me(c)?.letter ?: Prefs.letter(c)

    // ── the clock ───────────────────────────────────────────────────────

    /** Milliseconds to add to this phone's clock to get the server's, from the last sync. */
    fun clockOffset(c: Context): Long = Store.get(c, "cloud.clockOffset")?.toLongOrNull() ?: 0L

    internal fun setClockOffset(c: Context, ms: Long) = Store.put(c, "cloud.clockOffset", ms.toString())

    /**
     * Now, by the server's clock when connected. Bills and edits are stamped
     * with it, so a phone whose clock is off still files bills on the right
     * day and still wins merges in the right order.
     */
    fun now(c: Context): Long = System.currentTimeMillis() + if (connected(c)) clockOffset(c) else 0L

    /** Off by more than two minutes: worth a warning on the Parchi Cloud screen. */
    fun clockIsOff(c: Context): Boolean = connected(c) && Math.abs(clockOffset(c)) > 2 * 60_000

    // ── other phones ────────────────────────────────────────────────────

    fun phones(c: Context): List<Phone> = Store.get(c, "cloud.devices")?.let { s ->
        try {
            val a = JSONArray(s)
            (0 until a.length()).map { a.getJSONObject(it).let { o -> Phone(o.getString("id"), o.optString("name"), o.optString("letter"), o.optString("status") == "active") } }
        } catch (_: Exception) {
            emptyList()
        }
    } ?: emptyList()

    /** "" for this phone's own bills; otherwise the name of the phone that rang it up. */
    fun phoneOf(c: Context, deviceId: String?, origin: String, letter: String): String {
        val me = me(c) ?: return origin
        if (deviceId != null && deviceId == me.deviceId) return ""
        if (deviceId == null && origin.isEmpty()) return "" // from before the cloud: this phone's own
        return phones(c).find { it.id == deviceId }?.name ?: origin.ifEmpty { "Device $letter" }
    }

    // ── invoice numbers ─────────────────────────────────────────────────

    /**
     * The next invoice number for a bill on [day]: this phone's letter, the
     * financial year, and a serial that never repeats. The serial is this
     * phone's own counter, raised to the server's highest whenever a sync
     * reports one, so a reinstalled phone carries on where it left off.
     * Null when not connected: bills from before the cloud have none.
     */
    fun nextInvoice(c: Context, day: String): String? {
        val me = me(c) ?: return null
        val fy = Protocol.fyOf(day)
        return Store.tx(c) {
            val n = (Store.get(c, "inv.$fy")?.toIntOrNull() ?: 0) + 1
            Store.put(c, "inv.$fy", n.toString())
            Protocol.formatInvoice(me.letter, fy, n, outlet(c)?.optString("invoicePrefix")?.takeIf { it.isNotBlank() })
        }
    }

    internal fun raiseInvoiceSerials(c: Context, serials: JSONObject?) {
        serials ?: return
        for (fy in serials.keys()) {
            val server = serials.optInt(fy)
            val local = Store.get(c, "inv.$fy")?.toIntOrNull() ?: 0
            if (server > local) Store.put(c, "inv.$fy", server.toString())
        }
    }

    // ── what the cloud says about this outlet ───────────────────────────

    fun outlet(c: Context): JSONObject? {
        val me = me(c) ?: return null
        return Store.record(c, "outlet", me.outletId)?.data
    }

    fun rules(c: Context): JSONObject? = Store.record(c, "rules", "rules")?.data

    // ── status ──────────────────────────────────────────────────────────

    data class Status(val state: State, val outbox: Int, val lastOk: Long, val error: String, val latest: String, val minimum: String)

    fun status(c: Context): Status {
        if (!connected(c)) return Status(State.STANDALONE, 0, 0, "", "", "")
        val outbox = Store.outboxCount(c)
        val lastOk = Store.get(c, "cloud.lastOk")?.toLongOrNull() ?: 0L
        val error = Store.get(c, "cloud.error") ?: ""
        val state = when {
            Store.get(c, "cloud.removed") == "1" -> State.REMOVED
            Store.get(c, "cloud.upgrade") == "1" -> State.UPGRADE
            error.startsWith("offline:") -> State.OFFLINE
            error.isNotEmpty() -> State.ERROR
            outbox > 0 -> State.WAITING
            else -> State.LIVE
        }
        return Status(state, outbox, lastOk, error.removePrefix("offline:"), Store.get(c, "cloud.latestApp") ?: "", Store.get(c, "cloud.minApp") ?: "")
    }
}
