package com.parchipos.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The handful of settings the shop actually changes. */
object Prefs {
    private const val FILE = "parchipos"

    const val DEF_PRINTER_IP = "192.168.1.200"
    const val DEF_PRINTER_PORT = 9100
    const val DEF_WIDTH = 48

    fun printerIp(c: Context): String = p(c).getString("printerIp", DEF_PRINTER_IP)!!
    fun printerPort(c: Context): Int = p(c).getInt("printerPort", DEF_PRINTER_PORT)

    data class Shop(
        val name: String,
        val address: String,
        val phone: String,
        val gstin: String,
        val footer: String,
        /** 48 columns on 80mm paper, 32 on 58mm. */
        val width: Int,
        /** Off for a counter with no kitchen, where the bill alone is enough. */
        val printParchi: Boolean,
        /** The printer's smaller Font B: same slip, about a third less paper. */
        val small: Boolean,
        /** The drawn Scoops & Sips wordmark in place of the shop name as text. */
        val brand: Boolean,
    )

    fun shop(c: Context): Shop {
        val p = p(c)
        return Shop(
            p.getString("shopName", "")!!,
            p.getString("address", "")!!,
            p.getString("phone", "")!!,
            p.getString("gstin", "")!!,
            p.getString("footer", "")!!,
            p.getInt("width", DEF_WIDTH),
            p.getBoolean("printParchi", true),
            p.getBoolean("smallPrint", true),
            p.getBoolean("printBrand", true),
        )
    }

    fun save(c: Context, ip: String, port: Int, s: Shop) {
        p(c).edit()
            .putString("printerIp", ip)
            .putInt("printerPort", port)
            .putInt("width", s.width)
            .putString("shopName", s.name)
            .putString("address", s.address)
            .putString("phone", s.phone)
            .putString("gstin", s.gstin)
            .putString("footer", s.footer)
            .putBoolean("printParchi", s.printParchi)
            .putBoolean("smallPrint", s.small)
            .putBoolean("printBrand", s.brand)
            .apply()
    }

    /**
     * The next token for today, starting again at 1 each morning.
     *
     * Small numbers on purpose: the kitchen shouts it across the counter, and
     * "fourteen" is easier to hear than a bill number.
     */
    @Synchronized
    fun nextToken(c: Context, atLeast: Int = 1): Int {
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val prefs = p(c)
        val n = maxOf(atLeast, if (prefs.getString("tokenDay", null) == today) prefs.getInt("token", 0) + 1 else 1)
        prefs.edit().putString("tokenDay", today).putInt("token", n).apply()
        return n
    }

    // ── Cloud sync ──────────────────────────────────────────────────────

    const val DEF_SERVER = "https://icecreamos.sagatheretro.workers.dev"

    /** IceCreamOS, and this phone's key from its Settings → Parchi POS. No key, no sync. */
    fun serverUrl(c: Context): String = p(c).getString("serverUrl", DEF_SERVER)!!.trim().trimEnd('/')
    fun syncKey(c: Context): String = p(c).getString("syncKey", "")!!.trim()

    /** True if the server or key changed, so the caller can resend everything. */
    fun saveSync(c: Context, url: String, key: String): Boolean {
        val newUrl = url.trim().trimEnd('/').ifEmpty { DEF_SERVER }
        val changed = newUrl != serverUrl(c) || key.trim() != syncKey(c)
        p(c).edit().putString("serverUrl", newUrl).putString("syncKey", key.trim()).apply()
        return changed
    }

    /** What the last sync attempt found, for the Settings status line. */
    data class SyncStatus(val at: Long, val error: String, val device: String, val counts: String)

    fun syncStatus(c: Context): SyncStatus {
        val p = p(c)
        return SyncStatus(p.getLong("syncAt", 0), p.getString("syncError", "")!!, p.getString("syncDevice", "")!!, p.getString("syncCounts", "")!!)
    }

    fun saveSyncStatus(c: Context, error: String, device: String? = null, counts: String? = null) {
        val e = p(c).edit().putLong("syncAt", System.currentTimeMillis()).putString("syncError", error)
        if (device != null) e.putString("syncDevice", device)
        if (counts != null) e.putString("syncCounts", counts)
        e.apply()
    }

    private fun p(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
