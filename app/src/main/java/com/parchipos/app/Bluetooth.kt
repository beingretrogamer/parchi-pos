package com.parchipos.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.IOException
import java.util.UUID

/**
 * Bluetooth thermal printers, over classic Bluetooth's serial port profile.
 * That covers the ordinary 58 and 80 mm ESC/POS printers sold for counters.
 *
 * The printer is paired once in Android's own Bluetooth settings (the PIN
 * is usually 0000 or 1234); the app only lists paired devices and connects
 * to the chosen one. That needs one permission on Android 12+, "Nearby
 * devices" (BLUETOOTH_CONNECT), and no location or scanning at all.
 */
object BtPrinter {

    /** The serial port profile every SPP printer answers on. */
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /** Small writes, so a printer with a small buffer is never overrun. */
    private const val CHUNK = 512
    private const val CHUNK_PAUSE_MS = 8L
    private const val CONNECT_TRIES = 3

    class BtError(msg: String) : Exception(msg)

    data class Paired(val name: String, val address: String, val looksLikePrinter: Boolean)

    fun needsPermission(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun hasPermission(c: Context): Boolean =
        !needsPermission() || c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun adapter(c: Context): BluetoothAdapter? =
        (c.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun isOn(c: Context): Boolean = try { adapter(c)?.isEnabled == true } catch (_: SecurityException) { false }

    /** Paired devices, printers first. Throws BtError with a reason the counter can act on. */
    @SuppressLint("MissingPermission") // checked by usable()
    fun paired(c: Context): List<Paired> {
        val ad = usable(c)
        return ad.bondedDevices.orEmpty()
            .map { Paired(name = it.name?.takeIf { n -> n.isNotBlank() } ?: it.address, address = it.address, looksLikePrinter = isPrinter(it)) }
            .sortedWith(compareByDescending<Paired> { it.looksLikePrinter }.thenBy { it.name.lowercase() })
    }

    /**
     * Sends one job: connect, write in small pieces, wait for the printer to
     * take it all, close. Blocking; runs on the printer queue's thread.
     */
    @SuppressLint("MissingPermission") // checked by usable()
    fun send(c: Context, address: String, bytes: ByteArray, onLog: (String) -> Unit) {
        val ad = usable(c)
        if (!BluetoothAdapter.checkBluetoothAddress(address.uppercase())) throw BtError("no Bluetooth printer chosen in Settings")
        val device = ad.getRemoteDevice(address.uppercase())
        // Discovery slows every connection down. Stopping it needs the scan
        // permission on Android 12+, which this app never asks for (it never
        // starts a discovery either), so only older phones bother.
        if (!needsPermission()) try { ad.cancelDiscovery() } catch (_: Exception) {}

        var attempt = 0
        while (true) {
            val socket = try {
                connect(device, attempt)
            } catch (e: IOException) {
                attempt++
                if (attempt >= CONNECT_TRIES) throw BtError("printer did not answer. Is it on, in range and not connected to another phone? (${e.message ?: "no reply"})")
                onLog("Bluetooth printer did not answer (${e.message}); retry $attempt of ${CONNECT_TRIES - 1}")
                try { Thread.sleep(600L * attempt) } catch (_: InterruptedException) {}
                continue
            }
            try {
                val out = socket.outputStream
                var i = 0
                while (i < bytes.size) {
                    val n = minOf(CHUNK, bytes.size - i)
                    out.write(bytes, i, n)
                    out.flush()
                    i += n
                    if (i < bytes.size) Thread.sleep(CHUNK_PAUSE_MS)
                }
                // Written once bytes are out, so a failure here is never retried:
                // the printer may already hold half the slip.
                Thread.sleep(PrintPlan.drainMillis(bytes.size))
            } finally {
                try { socket.close() } catch (_: IOException) {}
            }
            return
        }
    }

    /**
     * Printers differ in which socket they accept, so each retry tries the
     * next way: the standard secure socket, then insecure (no encryption,
     * which some cheap printers need), then the fixed RFCOMM channel 1 that
     * older ones listen on.
     */
    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice, attempt: Int): BluetoothSocket {
        val socket: BluetoothSocket = when (attempt) {
            0 -> device.createRfcommSocketToServiceRecord(SPP)
            1 -> device.createInsecureRfcommSocketToServiceRecord(SPP)
            else -> try {
                device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(device, 1) as BluetoothSocket
            } catch (_: Exception) {
                device.createInsecureRfcommSocketToServiceRecord(SPP)
            }
        }
        try {
            socket.connect()
        } catch (e: IOException) {
            try { socket.close() } catch (_: IOException) {}
            throw e
        }
        return socket
    }

    private fun usable(c: Context): BluetoothAdapter {
        val ad = adapter(c) ?: throw BtError("this phone has no Bluetooth")
        if (!hasPermission(c)) throw BtError("Parchi needs the Nearby devices permission for Bluetooth printers (Settings › Printers › Choose printer)")
        if (!isOn(c)) throw BtError("Bluetooth is off")
        return ad
    }

    @SuppressLint("MissingPermission")
    private fun isPrinter(d: BluetoothDevice): Boolean = try {
        // Printers report the Imaging major class; cheap ones often report
        // nothing, so this only orders the list, never hides a device.
        d.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.IMAGING ||
            (d.name ?: "").contains(Regex("print|pos|thermal|xp-|mtp|rpp|bt-58|bt-80", RegexOption.IGNORE_CASE))
    } catch (_: SecurityException) {
        false
    }
}
