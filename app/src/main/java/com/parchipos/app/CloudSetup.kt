package com.parchipos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Customer records (Parchi Cloud), one per name per outlet. */
object Customers {
    /**
     * Ids come from the outlet and the name as the till matches it (case and
     * spaces ignored), so two phones that meet the same name, even offline,
     * make the same customer rather than two.
     */
    fun idFor(outletId: String, name: String): String = Store.stableId("cust", outletId + "|" + Ledger.key(name))

    /** The customer for a credit name, created if new. Null when not connected or blank. */
    fun ensure(c: Context, name: String): String? {
        val me = Cloud.me(c) ?: return null
        if (name.isBlank()) return null
        val id = idFor(me.outletId, name)
        if (Store.record(c, "customer", id) == null) {
            Store.saveRecord(c, Store.Rec("customer", id, JSONObject().put("name", name.trim()), outletId = me.outletId, updatedAt = Cloud.now(c), dirty = true))
        }
        return id
    }
}

/** Connecting a phone to Parchi Cloud, and leaving it. */
object CloudSetup {

    /** What this phone would upload on connecting: its own bills, and its payments. */
    data class Upload(val bills: Int, val payments: Int, val firstDay: String?, val lastDay: String?)

    fun pendingUpload(c: Context): Upload {
        val mine = Store.bills(c, "phone = ''")
        val receipts = Store.receipts(c)
        val days = mine.map { it.day } + receipts.map { it.day }
        return Upload(mine.size, receipts.size, days.minOrNull(), days.maxOrNull())
    }

    /**
     * Redeems the code, then gets this phone's data ready to go up. Blocking
     * (a network call); the first sync then runs in the background.
     */
    fun connect(c: Context, server: String, code: String, name: String): Cloud.Me {
        val p = CloudApi.pair(server, code.trim(), name.trim(), Prefs.letter(c).ifBlank { null }, SyncEngine.appVersion(c))
        val me = Cloud.Me(server.trimEnd('/'), p.deviceId, p.deviceKey, p.accountId, p.accountName, p.outletId, p.outletName, p.letter, name.trim(), System.currentTimeMillis())
        Store.tx(c) {
            for (key in listOf("cloud.cursor", "cloud.windowDone", "cloud.windowUpTo", "cloud.historyFrom", "cloud.removed", "cloud.upgrade", "cloud.error", "cloud.devices", "cloud.menuChoice", "cloud.headerChoice")) Store.put(c, key, null)
            Cloud.saveMe(c, me)
            Cloud.setClockOffset(c, if (p.serverTime > 0) p.serverTime - System.currentTimeMillis() else 0)
            prepare(c, me)
        }
        return me
    }

    /**
     * Gets the history ready to upload, without changing any edit time (the
     * server has none of it yet):
     *
     * - This phone's own bills are stamped with its device id and name, and go.
     * - Other phones' bills (seen through the IceCreamOS feed) stay here and
     *   don't go: each phone uploads its own, so nothing arrives twice under
     *   two names.
     * - Every credit name becomes a customer; bills and payments point to it.
     * - Payments were never shared, so all of them are this phone's.
     * - The menu is saved to menu.backup.json, and waits for the first
     *   download to decide whether the account's menu replaces it.
     */
    internal fun prepare(c: Context, me: Cloud.Me) {
        val db = Store.db(c)
        val names = LinkedHashMap<String, Pair<String, Long>>() // customer id → (name as last typed, when)
        fun customer(name: String, time: Long): String? {
            if (name.isBlank()) return null
            val id = Customers.idFor(me.outletId, name)
            val prev = names[id]
            if (prev == null || time >= prev.second) names[id] = name.trim() to time
            return id
        }

        for (s in Store.bills(c)) {
            val mine = s.phone.isEmpty()
            val cid = if (s.onCredit) customer(s.customer, s.time) else s.customerId
            val fixed = if (mine) s.copy(deviceId = s.deviceId ?: me.deviceId, origin = s.origin.ifBlank { me.name }, customerId = cid) else s.copy(customerId = cid)
            val raw = Store.raw(c, "bill", s.uid) ?: continue
            Store.putBillRecord(c, s.uid, Bills.toData(fixed, raw.data), raw.updatedAt, raw.fieldTimes, 0, dirty = mine, phone = s.phone)
        }
        for (r in Store.receipts(c)) {
            val cid = customer(r.customer, r.time) ?: continue
            val raw = Store.raw(c, "receipt", r.uid) ?: continue
            Store.putReceiptRecord(c, r.uid, Receipts.toData(r.copy(customerId = cid, deviceId = r.deviceId ?: me.deviceId), raw.data), raw.updatedAt, raw.fieldTimes, 0, dirty = true)
        }
        val now = System.currentTimeMillis()
        for ((id, v) in names) {
            if (Store.record(c, "customer", id) == null) {
                Store.saveRecord(c, Store.Rec("customer", id, JSONObject().put("name", v.first), outletId = me.outletId, updatedAt = now, dirty = true))
            }
        }

        val menu = Menu.all(c)
        if (menu.isNotEmpty()) {
            File(c.filesDir, "menu.backup.json").writeText(JSONArray().apply {
                menu.forEach { m -> put(JSONObject().put("name", m.name).put("price", m.price).put("category", m.category)
                    .put("variants", JSONArray().apply { m.variants.forEach { put(JSONObject().put("label", it.label).put("price", it.price)) } })) }
            }.toString())
        }
        Store.put(c, "cloud.localMenu", if (menu.isNotEmpty()) "1" else null)
        // Menu edits made before connecting go up only by choice, with a manager's PIN.
        db.execSQL("UPDATE records SET dirty = 0 WHERE kind IN ('category','menuItem','itemStatus')")

        val shop = Prefs.shop(c)
        val header = JSONObject().put("shopName", shop.name).put("address", shop.address).put("phone", shop.phone).put("gstin", shop.gstin).put("footer", shop.footer)
        Store.put(c, "cloud.localHeader", if (listOf(shop.name, shop.address, shop.phone, shop.gstin, shop.footer).any { it.isNotBlank() }) header.toString() else null)
    }

    /**
     * Back to a phone of its own. Everything stays on the phone, including
     * the bills of other phones; the portal still lists this phone until it is
     * removed there.
     */
    fun disconnect(c: Context) {
        Store.tx(c) {
            Store.db(c).execSQL("DELETE FROM kv WHERE key LIKE 'cloud.%'")
            Store.db(c).execSQL("UPDATE bills SET dirty = 0")
            Store.db(c).execSQL("UPDATE receipts SET dirty = 0")
            Store.db(c).execSQL("UPDATE records SET dirty = 0")
        }
    }

    // ── manager PINs ────────────────────────────────────────────────────

    data class Staff(val userId: String, val name: String, val role: String, val salt: String, val verifier: String)

    /** People whose PIN works on this phone: active, with a PIN, and either owners or managers of this outlet. */
    fun staff(c: Context): List<Staff> {
        val me = Cloud.me(c) ?: return emptyList()
        return Store.records(c, "staff").mapNotNull { r ->
            val d = r.data
            val outlets = d.optJSONArray("outletIds")
            val here = outlets == null || outlets.length() == 0 || (0 until outlets.length()).any { outlets.optString(it) == me.outletId }
            val salt = d.optStringOrNull("pinSalt")
            val verifier = d.optStringOrNull("pinVerifier")
            if (d.optBoolean("active", true) && here && salt != null && verifier != null) Staff(r.id, d.optString("name"), d.optString("role"), salt, verifier) else null
        }
    }

    /** Whose PIN this is, and its key, or null. Slow (PBKDF2): call off the main thread. */
    fun checkPin(c: Context, pin: String, roles: Set<String> = setOf("owner", "manager")): Pair<Staff, String>? {
        if (!Pin.valid(pin)) return null
        for (s in staff(c).filter { it.role in roles }) {
            Pin.matches(pin, s.salt, s.verifier)?.let { return s to it }
        }
        return null
    }

    /** Signs a record edit with a manager's PIN key, as the server requires for menu, outlet and rules. */
    fun sign(c: Context, rec: Store.Rec, staff: Staff, key: String): Store.Rec {
        val at = maxOf(Cloud.now(c), rec.updatedAt + 1)
        return rec.copy(updatedAt = at, byUser = staff.userId, proof = Pin.proof(key, rec.kind, rec.id, at), dirty = true)
    }

    /** Makes this phone's menu the account menu. Each record is signed with the PIN. */
    fun uploadMenu(c: Context, staff: Staff, key: String) {
        Store.tx(c) {
            for (kind in listOf("category", "menuItem")) {
                Store.records(c, kind).filter { it.seq == 0L }.forEach { Store.saveRecord(c, sign(c, it, staff, key)) }
            }
            Store.put(c, "cloud.menuChoice", null)
        }
    }

    /** Uses the account's menu after all: this phone's own is dropped (it is in menu.backup.json). */
    fun keepAccountMenu(c: Context) {
        Store.tx(c) {
            Store.db(c).delete("records", "kind IN ('category','menuItem') AND seq = 0", null)
            Store.put(c, "cloud.menuChoice", null)
        }
    }

    /** Makes this phone's bill header the outlet's. Signed with the PIN. */
    fun uploadHeader(c: Context, staff: Staff, key: String) {
        val me = Cloud.me(c) ?: return
        val local = Store.get(c, "cloud.localHeader")?.let { JSONObject(it) } ?: return
        Store.tx(c) {
            val rec = Store.record(c, "outlet", me.outletId) ?: return@tx
            val header = (rec.data.optJSONObject("header") ?: JSONObject())
            for (k in listOf("shopName", "address", "phone", "gstin", "footer")) local.optString(k).takeIf { it.isNotBlank() }?.let { header.put(k, it) }
            header.put("logo", Prefs.shop(c).brand)
            val data = JSONObject(rec.data.toString()).put("header", header)
            Store.saveRecord(c, sign(c, rec.copy(data = data), staff, key))
            Store.put(c, "cloud.headerChoice", null)
        }
    }

    fun skipHeader(c: Context) = Store.put(c, "cloud.headerChoice", null)
}
