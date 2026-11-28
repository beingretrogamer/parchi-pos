package com.parchipos.app

import android.content.Context
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Dues on a device follow a merge made in the portal: a duplicate customer counts on the one it went into. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LedgerMergeTest {

    private lateinit var c: Context
    private val me = Cloud.Me("https://cloud.test", "dev_counter", "pk_acc_x_key", "acc_x", "Shop", "out_main", "Main", "A", "Counter", 1)

    @Before fun setUp() {
        Store.closeForTests()
        c = RuntimeEnvironment.getApplication()
        c.deleteDatabase("parchi.db")
        Cloud.saveMe(c, me)
    }

    @After fun tearDown() = Store.closeForTests()

    private fun credit(uid: String, name: String, rupees: Double, at: Long) = Sales.record(c, Sale(
        id = "B-$uid", token = 1, time = at, lines = listOf(SaleLine("Tea", rupees, 1)), payment = CREDIT, customer = name, uid = uid,
        customerId = Customers.ensure(c, name), updatedAt = at,
    ))

    @Test fun aMergedDuplicateCountsOnTheRealCustomer() {
        val t0 = 1_795_700_000_000L
        credit("b1", "Ramesh", 80.0, t0)
        credit("b2", "ramesh g", 20.0, t0 + 1)
        Ledger.record(c, Receipt(time = t0 + 2, customer = "ramesh g", amount = 10.0, customerId = Customers.idFor(me.outletId, "ramesh g")))
        assertEquals(listOf("Ramesh" to 80.0, "ramesh g" to 10.0), Ledger.accounts(c).map { it.name to it.balance })

        // The portal merges "ramesh g" into Ramesh; the device hears of it.
        val dup = Customers.idFor(me.outletId, "ramesh g")
        val real = Customers.idFor(me.outletId, "Ramesh")
        Store.saveRecord(c, Store.Rec("customer", dup, JSONObject().put("name", "ramesh g").put("mergedInto", real), outletId = me.outletId, seq = 9))
        val accounts = Ledger.accounts(c)
        assertEquals(listOf("Ramesh" to 90.0), accounts.map { it.name to it.balance })
        assertEquals("c:$real", accounts.single().key)
        assertEquals(real, accounts.single().customerId)
        // The duplicate's name still finds the account, at the till and in a statement.
        assertEquals(90.0, Ledger.balance(c, "ramesh g"), 0.001)
        assertEquals(listOf(80.0, 100.0, 90.0), Ledger.statement(c, "ramesh g").map { it.balance })
        assertEquals(listOf("Ramesh"), Ledger.names(c))
    }
}
