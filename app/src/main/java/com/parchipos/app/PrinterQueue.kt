package com.parchipos.app

import android.content.Context
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * The single path to the printers.
 *
 * Everything that opens a connection to a printer goes through here, because
 * a typical ESC/POS printer accepts one connection at a time — two at once
 * and the second either fails or its bytes interleave with the first, and
 * both slips come out unreadable. A sale, a reprint from Sales and a Test
 * print can all be in flight together; this makes them take turns, per
 * printer, so the kitchen printer never waits on the bill printer.
 */
object PrinterQueue {

    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    /** A Bluetooth connect that fails takes ~12 s a try, so the next job waits longer. */
    private const val QUEUE_WAIT_MS = 45_000L
    private const val CONNECT_RETRIES = 2
    private const val RETRY_BACKOFF_MS = 700L

    /**
     * Blocking. Throws if the printer could not be reached, or if another job
     * held that printer for too long.
     */
    fun send(
        c: Context,
        target: PrinterTarget,
        bytes: ByteArray,
        timeoutMs: Int = 5000,
        onLog: (String) -> Unit = {},
    ) {
        val lock = locks.getOrPut(target.key) { ReentrantLock(true) }
        if (!lock.tryLock(QUEUE_WAIT_MS, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("printer busy with another job")
        }
        try {
            if (target.bluetooth) BtPrinter.send(c, target.btAddress, bytes, onLog)
            else sendWithRetry(target.host, target.port, bytes, timeoutMs, onLog)
        } finally {
            lock.unlock()
        }
    }

    /**
     * Retries only a failure to *connect*.
     *
     * A printer that is asleep, or busy finishing the previous slip, refuses
     * the connection for a second or two, and retrying there turns a spurious
     * failure into a print. Once the connection is open and bytes have gone
     * out the printer may already hold part of the receipt, so resending would
     * produce a duplicate or a torn slip followed by a whole one — a write
     * failure is reported, never retried.
     */
    private fun sendWithRetry(
        ip: String,
        port: Int,
        bytes: ByteArray,
        timeoutMs: Int,
        onLog: (String) -> Unit,
    ) {
        var attempt = 0
        while (true) {
            val sock = Socket()
            try {
                sock.connect(InetSocketAddress(ip, port), timeoutMs)
            } catch (e: Exception) {
                try { sock.close() } catch (_: Exception) {}
                attempt++
                if (attempt > CONNECT_RETRIES) throw e
                onLog("Printer did not answer (${e.message}); retry $attempt of $CONNECT_RETRIES")
                try { Thread.sleep(RETRY_BACKOFF_MS * attempt) } catch (_: InterruptedException) {}
                continue
            }
            sock.use {
                it.soTimeout = timeoutMs
                it.getOutputStream().apply { write(bytes); flush() }
                // Give the printer a moment to drain before the FIN.
                try { Thread.sleep(120) } catch (_: InterruptedException) {}
            }
            return
        }
    }
}
