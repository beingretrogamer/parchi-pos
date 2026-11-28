package com.parchipos.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

/**
 * The dine-in screens driven as a waiter would: seat a table, send a round,
 * print the bill, settle it. The server is a stand-in that takes everything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TablesScreensTest {

    private lateinit var c: Context
    private val me = Cloud.Me("https://cloud.test", "dev_counter", "pk_acc_x_key", "acc_x", "Tandoor Terrace", "out_main", "Main", "A", "Counter", 1)

    @Before fun setUp() {
        Store.closeForTests()
        c = RuntimeEnvironment.getApplication()
        c.filesDir.listFiles()?.forEach { it.delete() }
        c.deleteDatabase("parchi.db")
        c.getSharedPreferences("parchipos", Context.MODE_PRIVATE).edit().clear().commit()
        CloudApi.transport = object : CloudApi.Transport {
            override fun request(method: String, url: String, bearer: String?, body: String?): Pair<Int, String> {
                val msgs = JSONObject(body ?: "{}").optJSONArray("messages") ?: JSONArray()
                val changes = (0 until msgs.length()).map { msgs.getJSONObject(it) }.firstOrNull { it.optString("type") == "push" }?.getJSONArray("changes") ?: JSONArray()
                val acks = JSONArray().apply { for (i in 0 until changes.length()) changes.getJSONObject(i).let { put(JSONObject().put("kind", it.getString("kind")).put("id", it.getString("id")).put("seq", 100 + i)) } }
                return 200 to JSONObject().put("protocol", 1).put("messages", JSONArray()
                    .put(JSONObject().put("type", "hello").put("serverTime", System.currentTimeMillis()).put("invoiceSerials", JSONObject()))
                    .put(JSONObject().put("type", "ack").put("results", acks))
                    .put(JSONObject().put("type", "changes").put("records", JSONArray()).put("last", 5).put("more", false))).toString()
            }
        }
        Cloud.saveMe(c, me)
        Store.put(c, "cloud.windowDone", "1")
        Store.saveRecord(c, Store.Rec("outlet", me.outletId, JSONObject().put("name", "Main").put("serviceCharge", JSONObject().put("pct", 5).put("label", "Service charge")), outletId = me.outletId, seq = 1))
        for ((i, t) in listOf("T1", "T2").withIndex()) {
            Store.saveRecord(c, Store.Rec("table", "tbl_$i", JSONObject().put("name", t).put("area", "Inside").put("seats", 4).put("sort", i + 1).put("active", true), outletId = me.outletId, seq = 2L + i))
        }
        Menu.replaceAll(c, listOf(MenuItem("", "Dal Makhani", 320.0, "Mains"), MenuItem("", "Sweet Lassi", 90.0, "Drinks")))
    }

    @After fun tearDown() {
        CloudApi.transport = CloudApi.Http
        Store.closeForTests()
    }

    private fun idle(until: () -> Boolean = { true }) {
        repeat(100) {
            shadowOf(Looper.getMainLooper()).idle()
            if (until()) return
            Thread.sleep(50)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun find(v: View, pred: (View) -> Boolean): View? {
        if (pred(v)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), pred)?.let { return it }
        return null
    }

    private fun byText(root: View, prefix: String): View = find(root) { it is TextView && it.text.toString().startsWith(prefix) && it.isShown }
        ?: error("no view starting \"$prefix\"")

    /** The view to tap for [prefix]: the text itself, or the tile it sits in. */
    private fun tap(root: View, prefix: String) {
        var v: View? = byText(root, prefix)
        while (v != null && !v.hasOnClickListeners()) v = v.parent as? View
        assertNotNull("nothing to tap for $prefix", v)
        v!!.performClick()
        idle()
    }

    @Test fun seatSendBillSettle() {
        // The till offers Tables.
        val till = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val btnTables = till.findViewById<Button>(R.id.btnTables)
        assertEquals(View.VISIBLE, btnTables.visibility)

        // The grid: two free tables. T1 seats two.
        val grid = Robolectric.buildActivity(TablesActivity::class.java).setup().get()
        val gridRoot = grid.window.decorView
        assertTrue(byText(gridRoot, "T1").isShown)
        tap(gridRoot, "T1")
        val covers = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        find(covers.window!!.decorView) { it is EditText }!!.let { (it as EditText).setText("2") }
        covers.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        val toTill = shadowOf(grid).nextStartedActivity
        assertEquals(MainActivity::class.java.name, toTill.component!!.className)
        assertEquals("tbl_0", toTill.getStringExtra(MainActivity.EXTRA_TABLE))

        // The till in table mode: no payment, a Send button.
        val tableTill = Robolectric.buildActivity(MainActivity::class.java, toTill).setup().get()
        val tillRoot = tableTill.window.decorView
        assertEquals(View.VISIBLE, tableTill.findViewById<View>(R.id.tableBar).visibility)
        assertEquals("T1 · new order", tableTill.findViewById<TextView>(R.id.tableTitle).text.toString())
        assertEquals(View.GONE, tableTill.findViewById<View>(R.id.payCash).let { it.parent as View }.visibility)
        tap(tillRoot, "Dal Makhani")
        tap(tillRoot, "Sweet Lassi")
        tap(tillRoot, "Sweet Lassi")
        val charge = tableTill.findViewById<Button>(R.id.btnCharge)
        assertEquals("Send to kitchen · 3 items", charge.text.toString())
        charge.performClick()
        idle()
        val order = Tables.orders(c).single()
        assertEquals(2, order.covers)
        assertEquals(listOf(listOf("Dal Makhani" to 1, "Sweet Lassi" to 2)), Tables.rounds(c, setOf(order.id)).map { r -> r.lines.map { it.name to it.qty } })
        assertEquals(TablesActivity::class.java.name, shadowOf(tableTill).nextStartedActivity.component!!.className)

        // Back on the grid T1 is running with what went to the kitchen.
        val grid2 = Robolectric.buildActivity(TablesActivity::class.java).setup().get()
        assertTrue(byText(grid2.window.decorView, "₹500 ·").isShown)

        // The order: print the bill (sync first, then the preview with the 5% service charge).
        val screen = Robolectric.buildActivity(OrderActivity::class.java, Intent(c, OrderActivity::class.java).putExtra(OrderActivity.EXTRA_ORDER, order.id)).setup().get()
        val screenRoot = screen.window.decorView
        tap(screenRoot, "Print bill")
        idle { ShadowAlertDialog.getLatestAlertDialog()?.let { find(it.window!!.decorView) { v -> v is TextView && v.text.contains("TOTAL") } } != null }
        val preview = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        val figures = find(preview.window!!.decorView) { it is TextView && it.text.contains("TOTAL") } as TextView
        assertTrue(figures.text.toString(), figures.text.contains("Service charge 5%   ₹25"))
        assertTrue(figures.text.toString(), figures.text.contains("TOTAL   ₹525"))
        preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        val bill = Tables.billsOf(c, setOf(order.id)).single()
        assertTrue(bill.unsettled)
        assertEquals(525.0, bill.total, 0.001)
        assertEquals(Tables.BILLED, Tables.order(c, order.id)!!.status)

        // Settle: the guest pays by UPI.
        tap(screenRoot, "Settle")
        shadowOf(ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(1)
        idle()
        val paid = Store.bill(c, bill.uid)!!
        assertEquals("UPI", paid.payment)
        assertNotNull(paid.settledAt)
        assertTrue(Tables.states(c).first { it.table.name == "T1" }.free)
    }
}
