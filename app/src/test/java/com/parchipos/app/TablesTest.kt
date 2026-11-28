package com.parchipos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Dine-in on a device (plan §15): rounds from two devices on one table, taking an
 * item back, the bill with a service charge, settling it, a round that arrives
 * after the bill, reopening, merging two parties, and how orders sync.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TablesTest {

    private lateinit var c: Context
    private val me = Cloud.Me("https://cloud.test", "dev_counter", "pk_acc_x_key", "acc_x", "Tandoor Terrace", "out_main", "Main", "A", "Counter", 1)
    private val dal = Tables.Line("Dal Makhani", 320.0, 1, itemId = "m3", taxRate = 5.0)
    private val naan = Tables.Line("Butter Naan", 60.0, 3, itemId = "m2", taxRate = 5.0)
    private val lassi = Tables.Line("Sweet Lassi", 90.0, 2, itemId = "m4", taxRate = 5.0)

    @Before fun setUp() {
        Store.closeForTests()
        c = RuntimeEnvironment.getApplication()
        c.filesDir.listFiles()?.forEach { it.delete() }
        c.deleteDatabase("parchi.db")
        Cloud.saveMe(c, me)
        for ((i, t) in listOf("T1", "T2", "T3").withIndex()) {
            Store.saveRecord(c, Store.Rec("table", "tbl_$i", JSONObject().put("name", t).put("area", "Inside").put("seats", 4).put("sort", i + 1).put("active", true), outletId = me.outletId, seq = 1L + i))
        }
        // Another outlet's table never shows here.
        Store.saveRecord(c, Store.Rec("table", "tbl_other", JSONObject().put("name", "X1").put("sort", 1).put("active", true), outletId = "out_branch", seq = 9))
    }

    @After fun tearDown() = Store.closeForTests()

    private fun table(name: String) = Tables.tables(c).first { it.name == name }

    /** A round from the waiter's device, as a sync brings it. */
    private fun waiterRound(orderId: String, id: String, lines: List<Tables.Line>, seq: Long) {
        val data = JSONObject().put("orderId", orderId).put("deviceId", "dev_waiter").put("origin", "Waiter").put("time", Cloud.now(c)).put("day", "20261128")
            .put("lines", JSONArray().apply { lines.forEach { l -> put(JSONObject().put("name", l.name).put("price", Protocol.paise(l.price)).put("qty", l.qty).put("itemId", l.itemId).put("taxRate", l.taxRate)) } })
        SyncEngine.applyRecord(c, me, JSONObject().put("kind", "round").put("id", id).put("outletId", me.outletId).put("seq", seq).put("updatedAt", Cloud.now(c)).put("deleted", false).put("data", data))
    }

    @Test fun twoDevicesOneTableTheBillAndSettling() {
        assertEquals(listOf("T1", "T2", "T3"), Tables.tables(c).map { it.name })
        assertTrue(Tables.enabled(c))
        val t2 = table("T2")
        val o = Tables.open(c, t2, covers = 3)
        val r1 = Tables.send(c, o.id, listOf(dal, naan))
        waiterRound(o.id, "rnd_waiter", listOf(lassi, naan.copy(qty = 1)), seq = 50)

        // The grid: T2 running with what went to the kitchen (₹320 + ₹240 + ₹180).
        val s = Tables.states(c).first { it.table.name == "T2" }
        assertEquals(1, s.running.size)
        assertEquals(740.0, s.sent, 0.001)
        assertEquals(3, s.covers)
        assertTrue(Tables.states(c).first { it.table.name == "T1" }.free)

        // One naan taken back: a round of its own, pointing at the line.
        Tables.takeBack(c, o.id, r1, 1, 1, "burnt", null)
        val rounds = Tables.rounds(c, setOf(o.id))
        assertEquals(3, rounds.size)
        val (net, over) = Tables.net(rounds)
        assertFalse(over)
        assertEquals(listOf("Dal Makhani" to 1, "Butter Naan" to 3, "Sweet Lassi" to 2), net.map { it.line.name to it.qty })
        assertEquals(680.0, net.sumOf { it.amount }, 0.001)

        // The bill: every round, a 5% service charge, not paid yet.
        val sale = Tables.bill(c, o, "T2", net, rounds.map { it.id }, 0.0, "", 5.0, emptyList())
        assertTrue(sale.unsettled)
        assertEquals(714.0, sale.total, 0.001) // 680 + 5% with its GST
        assertEquals(34.0, sale.serviceCharge, 0.001)
        assertEquals(3, sale.covers)
        assertEquals("A/26-27/000001", sale.invoiceNo)
        val stored = Store.raw(c, "bill", sale.uid)!!.data
        assertTrue(stored.isNull("payment"))
        assertEquals("T2", stored.getString("table"))
        assertEquals(5.0, stored.getDouble("serviceChargePct"), 0.0)
        assertEquals(71400L, Bills.totalPaise(stored))
        assertEquals(Tables.BILLED, Tables.order(c, o.id)!!.status)
        assertEquals(listOf(sale.uid), Tables.order(c, o.id)!!.billIds)

        // T2 now waits for its payment; nothing is running.
        val after = Tables.states(c).first { it.table.name == "T2" }
        assertTrue(after.running.isEmpty())
        assertEquals(listOf(sale.uid), after.toSettle.map { it.uid })

        // The guest pays by UPI.
        Sales.settle(c, sale.uid, "UPI")
        val paid = Store.bill(c, sale.uid)!!
        assertEquals("UPI", paid.payment)
        assertTrue(paid.settledAt != null)
        assertTrue(Tables.states(c).first { it.table.name == "T2" }.free)
    }

    @Test fun aRoundAfterTheBillAndReopening() {
        val o = Tables.open(c, table("T1"), null)
        val r1 = Tables.send(c, o.id, listOf(dal))
        val sale = Tables.bill(c, o, "T1", Tables.net(listOf(r1)).first, listOf(r1.id), 0.0, "", 0.0, emptyList())
        // The waiter's device sent another round while the bill printed.
        waiterRound(o.id, "rnd_late", listOf(lassi), seq = 70)
        val s = Tables.states(c).first { it.table.name == "T1" }
        assertEquals(1, s.notOnBill)
        assertEquals(listOf("rnd_late"), Tables.unbilled(c, listOf(Tables.order(c, o.id)!!)).map { it.id })

        // Reopen: the bill is voided, the order runs again, and both rounds are unbilled.
        Tables.reopen(c, sale, null)
        assertTrue(Store.bill(c, sale.uid)!!.voided)
        assertEquals("Reopened", Store.bill(c, sale.uid)!!.voidReason)
        assertEquals(Tables.OPEN, Tables.order(c, o.id)!!.status)
        assertEquals(setOf(r1.id, "rnd_late"), Tables.unbilled(c, listOf(Tables.order(c, o.id)!!)).map { it.id }.toSet())
    }

    @Test fun twoPartiesMergedBillTogether() {
        val a = Tables.open(c, table("T1"), 2)
        val b = Tables.open(c, table("T3"), 2)
        Tables.send(c, a.id, listOf(dal))
        Tables.send(c, b.id, listOf(lassi))
        Tables.merge(c, Tables.order(c, b.id)!!, Tables.order(c, a.id)!!)
        val all = Tables.orders(c).associateBy { it.id }
        val party = Tables.group(all, all.getValue(a.id))
        assertEquals(setOf(a.id, b.id), party.map { it.id }.toSet())
        val states = Tables.states(c)
        assertEquals(1, states.first { it.table.name == "T1" }.running.size)
        assertEquals(4, states.first { it.table.name == "T1" }.covers)
        assertTrue(states.first { it.table.name == "T3" }.free)
        val rounds = Tables.unbilled(c, party)
        val sale = Tables.bill(c, all.getValue(a.id), "T1", Tables.net(rounds).first, rounds.map { it.id }, 0.0, "", 0.0, emptyList())
        assertEquals(500.0, sale.total, 0.001)
        assertEquals(4, sale.covers)
        assertEquals(Tables.BILLED, Tables.order(c, b.id)!!.status)
    }

    @Test fun takingBackMoreThanWasSentNeverGoesBelowNone() {
        val o = Tables.open(c, table("T1"), null)
        val r = Tables.send(c, o.id, listOf(lassi.copy(qty = 1)))
        Tables.takeBack(c, o.id, r, 0, 1, "spilt", null)
        Tables.takeBack(c, o.id, r, 0, 1, "spilt (other device)", null)
        val (net, over) = Tables.net(Tables.rounds(c, setOf(o.id)))
        assertTrue(net.isEmpty())
        assertTrue(over)
    }

    @Test fun ordersGoUpBeforeTheirRoundsAndMergePerGroup() {
        val o = Tables.open(c, table("T1"), null)
        Tables.send(c, o.id, listOf(dal))
        assertEquals(listOf("order", "round"), Store.outbox(c, 10).map { it.kind })
        assertEquals(setOf("place", "status", "info"), Store.outbox(c, 10).first().fieldTimes!!.keys)

        // The server takes it; then another device moves it to T3 while this one counts the guests.
        val sent = Store.raw(c, "order", o.id)!!
        SyncEngine.applyRecord(c, me, JSONObject().put("kind", "order").put("id", o.id).put("outletId", me.outletId).put("seq", 5)
            .put("updatedAt", sent.updatedAt).put("fieldTimes", JSONObject(sent.fieldTimes!! as Map<*, *>)).put("deleted", false).put("data", sent.data), forceClean = true)
        Tables.setCovers(c, Tables.order(c, o.id)!!, 5)
        val mine = Store.raw(c, "order", o.id)!!
        val moved = JSONObject(sent.data.toString()).put("tableId", "tbl_2")
        val remoteTimes = JSONObject(sent.fieldTimes!! as Map<*, *>).put("place", mine.updatedAt + 1000)
        SyncEngine.applyRecord(c, me, JSONObject().put("kind", "order").put("id", o.id).put("outletId", me.outletId).put("seq", 6)
            .put("updatedAt", mine.updatedAt + 1000).put("fieldTimes", remoteTimes).put("byDevice", "dev_waiter").put("deleted", false).put("data", moved))
        val now = Tables.order(c, o.id)!!
        assertEquals("tbl_2", now.tableId)  // their move
        assertEquals(5, now.covers)           // my count, still to go up
        assertTrue(now.rec.dirty)
    }

    @Test fun aCounterBillIsUnchanged() {
        val s = Sale(id = "261128-A001", token = 1, time = Cloud.now(c), lines = listOf(SaleLine("Chai", 20.0, 2, taxRate = 5.0)), payment = "CASH", customer = "")
        assertEquals(40.0, s.total, 0.0)
        assertEquals(0.0, s.serviceCharge, 0.0)
        assertFalse(s.unsettled)
        val d = Bills.toData(s, null)
        assertEquals("CASH", d.getString("payment"))
        assertFalse(d.has("serviceChargePct"))
        assertFalse(d.has("orderId"))
        assertNull(Bills.fromData("u", "", d, 1, emptyMap()).orderId)
    }
}
