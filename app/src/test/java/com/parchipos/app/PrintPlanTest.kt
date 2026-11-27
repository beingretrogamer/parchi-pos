package com.parchipos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintPlanTest {

    private val lan = PrinterTarget(bluetooth = false, host = "192.168.1.200", port = 9100)
    private val btCounter = PrinterTarget(bluetooth = true, btAddress = "00:11:22:AA:BB:CC", btName = "Counter")
    private val btKitchen = PrinterTarget(bluetooth = true, btAddress = "00:11:22:AA:BB:DD", btName = "Kitchen")

    private fun same() = KitchenPrinter(sameAsBill = true, target = PrinterTarget(bluetooth = false), width = 48)

    @Test fun oneSharedPrinterGetsBothSlipsInOneConnection() {
        val jobs = PrintPlan.of(lan, 48, same(), wantBill = true, wantParchi = true)
        assertEquals(listOf(PrintJob(lan, listOf(Slip.BILL, Slip.PARCHI), 48)), jobs)
    }

    @Test fun aKitchenPrinterGetsOnlyTheParchi() {
        val jobs = PrintPlan.of(btCounter, 32, KitchenPrinter(false, lan, 48), wantBill = true, wantParchi = true)
        assertEquals(listOf(PrintJob(btCounter, listOf(Slip.BILL), 32), PrintJob(lan, listOf(Slip.PARCHI), 48)), jobs)
    }

    @Test fun twoBluetoothPrintersAreTwoJobs() {
        val jobs = PrintPlan.of(btCounter, 32, KitchenPrinter(false, btKitchen, 32), wantBill = true, wantParchi = true)
        assertEquals(2, jobs.size)
        assertEquals(btKitchen, jobs[1].target)
    }

    @Test fun pickingTheSamePrinterTwiceIsStillOneConnection() {
        // Same address typed in a different case is the same printer.
        val again = btCounter.copy(btAddress = "00:11:22:aa:bb:cc")
        val jobs = PrintPlan.of(btCounter, 32, KitchenPrinter(false, again, 32), wantBill = true, wantParchi = true)
        assertEquals(listOf(PrintJob(btCounter, listOf(Slip.BILL, Slip.PARCHI), 32)), jobs)
    }

    @Test fun samePrinterWithDifferentPaperIsTwoJobs() {
        val jobs = PrintPlan.of(lan, 48, KitchenPrinter(false, lan, 32), wantBill = true, wantParchi = true)
        assertEquals(listOf(PrintJob(lan, listOf(Slip.BILL), 48), PrintJob(lan, listOf(Slip.PARCHI), 32)), jobs)
    }

    @Test fun reprintsAndParchiOffSendOnlyWhatIsAsked() {
        val kitchen = KitchenPrinter(false, btKitchen, 32)
        assertEquals(listOf(PrintJob(lan, listOf(Slip.BILL), 48)), PrintPlan.of(lan, 48, kitchen, wantBill = true, wantParchi = false))
        assertEquals(listOf(PrintJob(btKitchen, listOf(Slip.PARCHI), 32)), PrintPlan.of(lan, 48, kitchen, wantBill = false, wantParchi = true))
        assertEquals(listOf(PrintJob(lan, listOf(Slip.PARCHI), 48)), PrintPlan.of(lan, 48, same(), wantBill = false, wantParchi = true))
        assertTrue(PrintPlan.of(lan, 48, kitchen, wantBill = false, wantParchi = false).isEmpty())
    }

    @Test fun targetsKnowWhenTheyCanPrint() {
        assertTrue(lan.ready)
        assertTrue(btCounter.ready)
        assertFalse(PrinterTarget(bluetooth = true).ready)
        assertFalse(PrinterTarget(bluetooth = true, btAddress = "not-an-address").ready)
        assertFalse(lan.copy(host = "").ready)
        assertEquals("Counter", btCounter.label)
        assertEquals("00:11:22:AA:BB:CC", btCounter.copy(btName = "").label)
        assertEquals("192.168.1.200:9100", lan.label)
    }

    @Test fun drainWaitGrowsWithTheJobAndIsCapped() {
        assertEquals(400L, PrintPlan.drainMillis(0))
        assertEquals(1_400L, PrintPlan.drainMillis(10_000))
        assertEquals(5_000L, PrintPlan.drainMillis(1_000_000))
    }
}
