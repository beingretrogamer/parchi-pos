package com.parchipos.app

/**
 * Where a slip goes: a network printer (IP and port, as before) or a
 * Bluetooth one paired in Android's settings.
 *
 * No Android calls in this file, so the routing below is unit-tested.
 */
data class PrinterTarget(
    val bluetooth: Boolean,
    val host: String = "",
    val port: Int = 9100,
    /** MAC address, "00:11:22:AA:BB:CC". */
    val btAddress: String = "",
    /** The name it was paired under, shown in Settings and in errors. */
    val btName: String = "",
) {
    /** Two targets with the same key are the same physical printer. */
    val key: String get() = if (bluetooth) "bt:" + btAddress.uppercase() else "net:$host:$port"

    /** "Counter BT (00:11:…)" or "192.168.1.200:9100". */
    val label: String get() = when {
        !bluetooth -> "$host:$port"
        btName.isNotBlank() -> btName
        btAddress.isNotBlank() -> btAddress
        else -> "Bluetooth printer"
    }

    /** Enough to try printing: an address for Bluetooth, a host for the network. */
    val ready: Boolean get() = if (bluetooth) BT_ADDRESS.matches(btAddress) else host.isNotBlank() && port in 1..65535

    companion object {
        val BT_ADDRESS = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
    }
}

/** The kitchen printer: the bill printer itself, or one of its own (with its own paper width). */
data class KitchenPrinter(
    val sameAsBill: Boolean,
    val target: PrinterTarget,
    /** 48 columns on 80 mm paper, 32 on 58 mm. */
    val width: Int,
)

enum class Slip { BILL, PARCHI }

/** One connection to one printer, carrying one or both slips. */
data class PrintJob(val target: PrinterTarget, val slips: List<Slip>, val width: Int)

object PrintPlan {
    /**
     * Which printer gets which slip. The bill goes to the bill printer and
     * the parchi to the kitchen printer; when those are one printer, both go
     * in a single connection (bill first), as they always have.
     */
    fun of(bill: PrinterTarget, billWidth: Int, kitchen: KitchenPrinter, wantBill: Boolean, wantParchi: Boolean): List<PrintJob> {
        val kitchenTarget = if (kitchen.sameAsBill) bill else kitchen.target
        val kitchenWidth = if (kitchen.sameAsBill) billWidth else kitchen.width
        val oneConnection = kitchenTarget.key == bill.key
        return when {
            !wantBill && !wantParchi -> emptyList()
            !wantParchi -> listOf(PrintJob(bill, listOf(Slip.BILL), billWidth))
            !wantBill -> listOf(PrintJob(kitchenTarget, listOf(Slip.PARCHI), kitchenWidth))
            // Same printer, same paper: one connection, bill then parchi.
            oneConnection && kitchenWidth == billWidth -> listOf(PrintJob(bill, listOf(Slip.BILL, Slip.PARCHI), billWidth))
            else -> listOf(PrintJob(bill, listOf(Slip.BILL), billWidth), PrintJob(kitchenTarget, listOf(Slip.PARCHI), kitchenWidth))
        }
    }

    /**
     * How long to wait after writing before closing a Bluetooth connection.
     * A write returns once the phone's stack has the bytes, not the printer;
     * closing early cuts the end of the slip off. About 10 KB a second over SPP.
     */
    fun drainMillis(bytes: Int): Long = (400L + bytes / 10).coerceAtMost(5_000L)
}
