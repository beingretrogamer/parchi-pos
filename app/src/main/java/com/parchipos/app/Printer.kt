package com.parchipos.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

/** Sends slips to the configured printer off the main thread. */
object Printer {

    private val ui = Handler(Looper.getMainLooper())

    /** Bill first, then the parchi if the shop prints one. One connection for both. */
    fun saleBytes(c: Context, sale: Sale, bill: Boolean = true, parchi: Boolean = true): ByteArray {
        val shop = Prefs.shop(c)
        var out = ByteArray(0)
        if (bill) out += Escpos.bill(shop, sale)
        if (parchi && shop.printParchi) out += Escpos.parchi(shop, sale)
        return out
    }

    /**
     * [done] runs on the main thread with null on success, or the reason it
     * failed. The sale is already saved by the time this is called, so a
     * failure here never loses a sale — it only means a reprint is owed.
     */
    fun send(c: Context, what: String, bytes: ByteArray, done: (String?) -> Unit) {
        val app = c.applicationContext
        val ip = Prefs.printerIp(app)
        val port = Prefs.printerPort(app)
        thread {
            val err = try {
                PrinterQueue.send(ip, port, bytes, onLog = { PrintLog.append(it) })
                PrintLog.append("Printed $what")
                null
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                PrintLog.append("FAILED $what to $ip:$port — $msg")
                msg
            }
            ui.post { done(err) }
        }
    }
}
