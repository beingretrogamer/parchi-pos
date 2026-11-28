package com.parchipos.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.io.FileOutputStream

/**
 * Renders every screen to PNG, for UI reviews: before and after pictures of a
 * change, at phone, folding-phone and tablet sizes, light and dark. Skipped
 * unless PARCHI_SHOTS names an output folder; PARCHI_SHOP may name a records
 * dump (category, menuItem, outlet, customer, rules, table) to use a real menu.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ScreenshotsTest {

    private lateinit var c: Context
    private lateinit var out: File
    private val me = Cloud.Me("https://cloud.test", "dev_galla", "pk_acc_x_key", "acc_x", "Scoops and Sips", "out_main", "Main", "B", "Galla", 1)

    @Before fun setUp() {
        val dir = System.getenv("PARCHI_SHOTS")
        assumeTrue("PARCHI_SHOTS not set", !dir.isNullOrBlank())
        out = File(dir!!).apply { mkdirs() }
        Store.closeForTests()
        c = RuntimeEnvironment.getApplication()
        c.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        c.deleteDatabase("parchi.db")
        c.getSharedPreferences("parchipos", Context.MODE_PRIVATE).edit().clear().commit()
        CloudApi.transport = object : CloudApi.Transport {
            override fun request(method: String, url: String, bearer: String?, body: String?): Pair<Int, String> {
                val msgs = JSONObject(body ?: "{}").optJSONArray("messages") ?: JSONArray()
                val changes = (0 until msgs.length()).map { msgs.getJSONObject(it) }.firstOrNull { it.optString("type") == "push" }?.getJSONArray("changes") ?: JSONArray()
                val acks = JSONArray().apply { for (i in 0 until changes.length()) changes.getJSONObject(i).let { put(JSONObject().put("kind", it.getString("kind")).put("id", it.getString("id")).put("seq", 1000 + i)) } }
                return 200 to JSONObject().put("protocol", 1).put("messages", JSONArray()
                    .put(JSONObject().put("type", "hello").put("serverTime", System.currentTimeMillis()).put("invoiceSerials", JSONObject()))
                    .put(JSONObject().put("type", "ack").put("results", acks))
                    .put(JSONObject().put("type", "changes").put("records", JSONArray()).put("last", 900).put("more", false))).toString()
            }
        }
        seed()
    }

    @After fun tearDown() {
        CloudApi.transport = CloudApi.Http
        Store.closeForTests()
    }

    // ---- data ----

    private var seq = 1L
    private fun apply(kind: String, id: String, outletId: String?, data: JSONObject, byDevice: String? = null) {
        SyncEngine.applyRecord(c, me, JSONObject().put("kind", kind).put("id", id).put("outletId", outletId ?: JSONObject.NULL)
            .put("seq", seq++).put("updatedAt", System.currentTimeMillis() - 3_600_000).put("deleted", false).put("data", data)
            .put("byDevice", byDevice ?: JSONObject.NULL))
    }

    private data class Item(val id: String, val name: String, val price: Double, val variant: String?, val tax: Double?)
    private val items = mutableListOf<Item>()

    private fun seed() {
        Cloud.saveMe(c, me)
        Store.put(c, "cloud.windowDone", "1")
        Store.put(c, "cloud.devices", JSONArray()
            .put(JSONObject().put("id", "dev_tab").put("name", "Tab").put("letter", "A").put("outletId", me.outletId).put("status", "active"))
            .put(JSONObject().put("id", me.deviceId).put("name", "Galla").put("letter", "B").put("outletId", me.outletId).put("status", "active"))
            .put(JSONObject().put("id", "dev_jatin").put("name", "Jatin Phone").put("letter", "C").put("outletId", me.outletId).put("status", "active"))
            .toString())
        val shop = System.getenv("PARCHI_SHOP")?.let { File(it) }?.takeIf { it.exists() }?.let { JSONObject(it.readText()) }
        if (shop != null) {
            // The built-in sample menu goes; the account's comes in as a connected device gets it.
            for (kind in listOf("menuItem", "category")) Store.records(c, kind, includeDeleted = true).forEach { Store.deleteRow(c, kind, it.id) }
            for (kind in listOf("rules", "category", "menuItem", "customer")) {
                val recs = shop.optJSONArray(kind) ?: continue
                for (i in 0 until recs.length()) recs.getJSONObject(i).let { if (!it.optBoolean("deleted")) apply(kind, it.getString("id"), it.optString("outletId").takeIf { o -> o.isNotEmpty() && o != "null" }?.let { me.outletId }, it.getJSONObject("data")) }
            }
            val outlet = (0 until shop.getJSONArray("outlet").length()).map { shop.getJSONArray("outlet").getJSONObject(it) }.first { it.getJSONObject("data").optString("name") == "Main" }
            apply("outlet", me.outletId, me.outletId, outlet.getJSONObject("data").put("serviceCharge", JSONObject().put("pct", 5).put("label", "Service charge")))
            val menu = shop.getJSONArray("menuItem")
            for (i in 0 until menu.length()) {
                val m = menu.getJSONObject(i)
                if (m.optBoolean("deleted")) continue
                val d = m.getJSONObject("data")
                val tax = if (d.has("taxRate") && !d.isNull("taxRate")) d.getDouble("taxRate") else null
                val vs = d.optJSONArray("variants")
                if (vs != null && vs.length() > 0) vs.getJSONObject(0).let { items += Item(m.getString("id"), d.getString("name"), it.getLong("price") / 100.0, it.getString("label"), tax) }
                else items += Item(m.getString("id"), d.getString("name"), d.getLong("price") / 100.0, null, tax)
            }
        } else {
            Menu.replaceAll(c, listOf(MenuItem("", "KitKat Shake", 100.0, "Shakes"), MenuItem("", "Cheese Balls", 90.0, "Cheese Balls"), MenuItem("", "Paan Scoop", 50.0, "Scoops")))
            items += Item("", "KitKat Shake", 100.0, null, null)
            items += Item("", "Cheese Balls", 90.0, null, null)
            items += Item("", "Paan Scoop", 50.0, null, null)
        }
        for ((i, t) in listOf("T1", "T2", "T3", "T4", "T5", "T6").withIndex()) {
            apply("table", "tbl_$i", me.outletId, JSONObject().put("name", t).put("area", if (i < 4) "Inside" else "Terrace").put("seats", 4).put("sort", i + 1).put("active", true))
        }

        // Today's counter bills from three devices, a void, a credit bill, a discount.
        val now = System.currentTimeMillis()
        val customers = shop?.optJSONArray("customer")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { r -> r.getString("id") to r.getJSONObject("data").getString("name") } } }.orEmpty()
        val pays = listOf("CASH", "UPI", "UPI", "CASH", "UPI")
        for (k in 0 until 14) {
            val dev = listOf("dev_tab" to ("A" to "Tab"), me.deviceId to ("B" to "Galla"), "dev_jatin" to ("C" to "Jatin Phone"))[k % 3]
            val t = now - (14 - k) * 17 * 60_000L
            val lines = (0..(k % 3)).map { j -> items[(k * 7 + j * 5) % items.size].let { SaleLine(it.name, it.price, 1 + (k + j) % 2, itemId = it.id.ifEmpty { null }, variant = it.variant, taxRate = it.tax) } }
            val credit = k == 9 && customers.isNotEmpty()
            val sale = Sale(
                id = Sales.billId(t, dev.second.first, k / 3 + 1), token = k / 3 + 1, time = t, updatedAt = t, lines = lines,
                payment = if (credit) CREDIT else pays[k % pays.size], customer = if (credit) customers[0].second else "",
                customerId = if (credit) customers[0].first else null, letter = dev.second.first,
                voided = k == 5, voidReason = if (k == 5) "Customer left" else "",
                discount = if (k == 7) 20.0 else 0.0, discountReason = if (k == 7) "Regular" else "",
                deviceId = dev.first, origin = dev.second.second, phone = if (dev.first == me.deviceId) "" else dev.second.second,
            )
            if (dev.first == me.deviceId) Sales.record(c, sale)
            else apply("bill", sale.uid, me.outletId, Bills.toData(sale, null), dev.first)
        }

        // Tables: T1 eating (two rounds), T2 bill printed, T5 just seated.
        val tables = Tables.tables(c)
        val t1 = Tables.open(c, tables.first { it.name == "T1" }, 4)
        Tables.send(c, t1.id, listOf(line(0, 2), line(3, 1, "less spicy"), line(6, 2)))
        Tables.send(c, t1.id, listOf(line(9, 2)))
        val t2 = Tables.open(c, tables.first { it.name == "T2" }, 2)
        Tables.send(c, t2.id, listOf(line(1, 1), line(4, 2)))
        val r2 = Tables.rounds(c, setOf(t2.id))
        Tables.bill(c, Tables.order(c, t2.id)!!, "T2", Tables.net(r2).first, r2.map { it.id }, 0.0, "", 5.0, emptyList())
        val t5 = Tables.open(c, tables.first { it.name == "T5" }, null)
        Tables.send(c, t5.id, listOf(line(2, 1)))
    }

    private fun line(i: Int, qty: Int, note: String = "") = items[i % items.size].let { Tables.Line(it.name, it.price, qty, note, itemId = it.id.ifEmpty { null }, variant = it.variant, taxRate = it.tax) }

    // ---- rendering ----

    private fun idle() {
        repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun save(name: String, v: View) {
        idle()
        val dm = c.resources.displayMetrics
        if (v.width == 0 || v.height == 0) {
            v.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.AT_MOST))
            v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        }
        val bmp = Bitmap.createBitmap(maxOf(1, v.width), maxOf(1, v.height), Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.TRANSPARENT)
        v.draw(Canvas(bmp))
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shot(name: String, a: Activity) = save(name, a.window.decorView)
    private fun shot(name: String, d: Dialog) = save(name, d.window!!.decorView)

    private fun find(v: View, pred: (View) -> Boolean): View? {
        if (pred(v)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), pred)?.let { return it }
        return null
    }

    private fun tap(root: View, prefix: String): Boolean {
        var v: View? = find(root) { it is TextView && it.text.toString().startsWith(prefix) && it.isShown } ?: return false
        while (v != null && !v.hasOnClickListeners()) v = v.parent as? View
        v?.performClick() ?: return false
        idle()
        return true
    }

    private fun latestDialog(): Dialog? = ShadowAlertDialog.getLatestAlertDialog() ?: org.robolectric.shadows.ShadowDialog.getLatestDialog()

    private fun all(prefix: String, full: Boolean) {
        // The till: empty, then a cart with a single-price item and a size picked.
        val till = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shot("${prefix}01-till", till)
        val root = till.window.decorView
        val single = items.filter { it.variant == null }
        single.take(2).forEach { tap(root, it.name) }
        single.firstOrNull()?.let { tap(root, it.name) }
        shot("${prefix}02-till-cart", till)
        items.firstOrNull { it.variant != null }?.let { v ->
            if (tap(root, v.name)) latestDialog()?.let { d -> shot("${prefix}03-size-picker", d); d.dismiss(); idle() }
        }

        val grid = Robolectric.buildActivity(TablesActivity::class.java).setup().get()
        shot("${prefix}04-tables", grid)

        val t1 = Tables.orders(c).first { o -> Tables.tables(c).first { it.id == o.tableId }.name == "T1" }
        val order = Robolectric.buildActivity(OrderActivity::class.java, Intent(c, OrderActivity::class.java).putExtra(OrderActivity.EXTRA_ORDER, t1.id)).setup().get()
        shot("${prefix}05-order", order)
        if (full && tap(order.window.decorView, "Print bill")) {
            repeat(10) { idle() }
            latestDialog()?.let { shot("${prefix}06-bill-preview", it); it.dismiss() }
        }
        val tableTill = Robolectric.buildActivity(MainActivity::class.java, Intent(c, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TABLE, t1.tableId).putExtra(MainActivity.EXTRA_TABLE_NAME, "T1").putExtra(MainActivity.EXTRA_ORDER, t1.id)).setup().get()
        single.take(1).forEach { tap(tableTill.window.decorView, it.name) }
        shot("${prefix}07-till-table", tableTill)

        shot("${prefix}08-sales", Robolectric.buildActivity(SalesActivity::class.java).setup().get())
        if (!full) return
        shot("${prefix}09-dues", Robolectric.buildActivity(LedgerActivity::class.java).setup().get())
        shot("${prefix}10-menu", Robolectric.buildActivity(MenuActivity::class.java).setup().get())
        shot("${prefix}11-settings", Robolectric.buildActivity(SettingsActivity::class.java).setup().get())
        shot("${prefix}12-cloud", Robolectric.buildActivity(CloudActivity::class.java).setup().get())
    }

    @Test @Config(qualifiers = "w393dp-h852dp-xhdpi") fun phone() = all("phone-", true)
    @Test @Config(qualifiers = "w393dp-h852dp-night-xhdpi") fun phoneDark() = all("phone-dark-", true)
    @Test @Config(qualifiers = "w690dp-h830dp-xhdpi") fun foldOpen() = all("fold-", false)
    @Test @Config(qualifiers = "w1280dp-h800dp-land-mdpi") fun tablet() = all("tablet-", false)
}
