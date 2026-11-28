package com.parchipos.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Sends slips to the configured printers off the main thread. */
object Printer {

    private val ui = Handler(Looper.getMainLooper())

    /** A slip that did not print, where it was going, and why. */
    data class Failure(val slip: Slip, val printer: String, val reason: String)

    /**
     * One job to one printer (a test slip, a dues receipt). [done] runs on
     * the main thread with null on success, or the reason it failed.
     */
    fun send(c: Context, target: PrinterTarget, what: String, bytes: ByteArray, done: (String?) -> Unit) {
        val app = c.applicationContext
        thread {
            val err = run(app, target, what, bytes)
            ui.post { done(err) }
        }
    }

    /**
     * A sale's slips: the bill to the bill printer, the parchi to the
     * kitchen printer, or both in one go when that is one printer. The sale
     * is already saved, so a failure only ever means a reprint is owed.
     * [done] runs on the main thread with the slips that failed; empty when
     * everything printed.
     */
    fun sale(c: Context, sale: Sale, bill: Boolean, parchi: Boolean, done: (List<Failure>) -> Unit) {
        val app = c.applicationContext
        val shop = Prefs.shop(app)
        val jobs = PrintPlan.of(Prefs.billPrinter(app), shop.width, Prefs.kitchenPrinter(app), bill, parchi)
        if (jobs.isEmpty()) {
            ui.post { done(emptyList()) }
            return
        }
        val failures = Collections.synchronizedList(ArrayList<Failure>())
        val left = AtomicInteger(jobs.size)
        // Different printers print at the same time; the queue keeps each one to one job at a time.
        for (job in jobs) thread {
            val err = try {
                val s = shop.copy(width = job.width)
                var bytes = ByteArray(0)
                if (Slip.BILL in job.slips) bytes += Escpos.bill(s, sale)
                if (Slip.PARCHI in job.slips) bytes += Escpos.parchi(s, sale)
                val what = "token ${sale.label} (" + job.slips.joinToString(" + ") { it.name.lowercase() } + ")"
                run(app, job.target, what, bytes)
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            if (err != null) job.slips.forEach { failures.add(Failure(it, job.target.label, err)) }
            if (left.decrementAndGet() == 0) {
                val result = failures.sortedBy { it.slip }
                ui.post { done(result) }
            }
        }
    }

    /**
     * A table's round to the kitchen printer (the bill printer when there is no separate one).
     * [done] runs on the main thread with an error, or null once it printed.
     */
    fun round(c: Context, table: String, round: Tables.Round, no: Int, covers: Int?, done: (String?) -> Unit) {
        val app = c.applicationContext
        val shop = Prefs.shop(app)
        val job = PrintPlan.of(Prefs.billPrinter(app), shop.width, Prefs.kitchenPrinter(app), false, true).firstOrNull()
        if (job == null) {
            ui.post { done(null) }
            return
        }
        thread {
            val err = try {
                run(app, job.target, "table $table round $no", Escpos.round(shop.copy(width = job.width), table, round, no, covers))
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            ui.post { done(err) }
        }
    }

    private fun run(app: Context, target: PrinterTarget, what: String, bytes: ByteArray): String? = try {
        if (!target.ready) throw IllegalStateException(if (target.bluetooth) "no Bluetooth printer chosen in Settings" else "no printer address in Settings")
        PrinterQueue.send(app, target, bytes, onLog = { PrintLog.append(it) })
        PrintLog.append("Printed $what on ${target.label}")
        null
    } catch (e: Exception) {
        val msg = e.message ?: e.javaClass.simpleName
        PrintLog.append("FAILED $what on ${target.label} — $msg")
        msg
    }
}
