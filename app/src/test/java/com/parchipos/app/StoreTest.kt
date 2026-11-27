package com.parchipos.app

import android.content.Context
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** parchi.db: the one-time import from v0.6's files, and the screens' functions on top of it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoreTest {

    private lateinit var c: Context
    private val day: Long = Calendar.getInstance().apply { clear(); set(2026, Calendar.NOVEMBER, 27, 13, 0) }.timeInMillis
    private val dayKey = "20261127"

    @Before fun setUp() {
        Store.closeForTests()
        c = RuntimeEnvironment.getApplication()
        c.filesDir.listFiles()?.forEach { it.delete() }
        c.deleteDatabase("parchi.db")
    }

    @After fun tearDown() = Store.closeForTests()

    private fun file(name: String, text: String) = File(c.filesDir, name).writeText(text)

    private fun raw(table: String, id: String): JSONObject =
        Store.db(c).rawQuery("SELECT data FROM $table WHERE id = ?", arrayOf(id)).use { it.moveToFirst(); JSONObject(it.getString(0)) }

    private fun writeV06Files() {
        file("sales.jsonl", listOf(
            // Before cloud sync: no uid yet.
            """{"id":"261127-001","token":1,"time":${day},"payment":"CASH","customer":"","voided":false,"lines":[{"name":"Veg Momos (Half)","price":45.5,"qty":2,"note":"less spicy"}]}""",
            """{"id":"261127-A002","token":2,"time":${day + 60_000},"payment":"CREDIT","customer":"Ramesh ","voided":false,"lines":[{"name":"Oreo Shake","price":110,"qty":1}],"uid":"u-2","updatedAt":${day + 60_000},"letter":"A","phone":"","discount":12.5,"discountReason":"Regular"}""",
            "{ not json",
            """{"id":"261127-B005","token":5,"time":${day + 120_000},"payment":"UPI","customer":"","voided":true,"voidReason":"Wrong item","lines":[{"name":"Masala Chai","price":19.99,"qty":3}],"uid":"u-3","updatedAt":${day + 180_000},"letter":"B","phone":"Anil"}""",
        ).joinToString("\n") + "\n")
        file("receipts.jsonl", """{"uid":"r-1","time":${day + 240_000},"customer":"ramesh","amount":50,"discount":0.5,"mode":"UPI","note":"","voided":false,"voidReason":""}""" + "\n")
        file("menu.json", """[
            {"id":"m1","name":"Vanilla","price":40,"category":"Scoops","variants":[{"label":"Cup","price":40},{"label":"Cone","price":50}]},
            {"id":"m2","name":"Oreo Shake","price":110,"category":"Shakes","variants":[]},
            {"id":"m3","name":"Mango","price":50,"category":"Scoops","variants":[]},
            {"id":"m4","name":"Water","price":20,"category":"","variants":[]}
        ]""")
    }

    @Test fun importsV06FilesOnceInPaiseAndKeepsThemAsBackup() {
        writeV06Files()
        val sales = Sales.all(c)
        assertEquals("the damaged line costs one bill, never the history", 3, sales.size)

        val first = sales[0]
        assertTrue("a bill with no uid gets one", first.uid.isNotEmpty())
        assertEquals(45.5, first.lines[0].price, 0.0)
        assertEquals(4550, raw("bills", first.uid).getJSONArray("lines").getJSONObject(0).getInt("price"))
        assertEquals("less spicy", first.lines[0].note)

        val credit = Sales.all(c).first { it.uid == "u-2" }
        assertEquals(12.5, credit.discount, 0.0)
        assertEquals(1250, raw("bills", "u-2").getInt("discount"))
        assertEquals(97.5, credit.total, 0.0)

        val chai = Sales.all(c).first { it.uid == "u-3" }
        assertEquals(19.99, chai.lines[0].price, 0.0)
        assertTrue(chai.voided)
        assertEquals("Wrong item", chai.voidReason)
        assertEquals("Anil", chai.phone)
        assertEquals("another phone's bill remembers whose it was", "Anil", chai.origin)

        for (name in listOf("sales.jsonl", "receipts.jsonl", "menu.json")) {
            assertFalse("$name is moved aside", File(c.filesDir, name).exists())
            assertTrue("$name is kept as a backup", File(c.filesDir, "$name.imported").exists())
        }

        // Opening again imports nothing twice, and the generated uid stays put.
        Store.closeForTests()
        assertEquals(3, Sales.all(c).size)
        assertEquals(first.uid, Sales.all(c)[0].uid)

        val receipts = Ledger.receipts(c)
        assertEquals(1, receipts.size)
        assertEquals(50.0, receipts[0].amount, 0.0)
        assertEquals(dayKey, receipts[0].day)

        // Ramesh owes 97.50, paid 50 and was let off 0.50, whatever the spelling.
        val ramesh = Ledger.accounts(c).single()
        assertEquals(47.0, ramesh.balance, 0.001)
    }

    @Test fun importedMenuKeepsCategoryOrderAndItemOrder() {
        writeV06Files()
        val menu = Menu.all(c)
        assertEquals(listOf("Vanilla", "Mango", "Oreo Shake", "Water"), menu.map { it.name })
        assertEquals(listOf("Scoops", "Shakes"), Menu.categories(c))
        assertEquals(listOf("Cup", "Cone"), menu[0].variants.map { it.label })
        assertEquals(50.0, menu[0].variants[1].price, 0.0)
        assertTrue("every size gets an id for the cloud", menu[0].variants.all { it.id.isNotEmpty() })
    }

    @Test fun aFreshInstallGetsTheStarterMenu() {
        val menu = Menu.all(c)
        assertTrue(menu.size > 20)
        assertEquals("Scoops", Menu.categories(c).first())
    }

    @Test fun tokensCountPerPhone() {
        Sales.record(c, sale(1, letter = "A"))
        Sales.record(c, sale(2, letter = "A"))
        Sales.record(c, sale(5, letter = "B", phone = "Anil"))
        assertEquals(2, Sales.maxToken(c, dayKey, "", "A"))
        assertEquals(5, Sales.maxToken(c, dayKey, "Anil", "B"))
        assertEquals(0, Sales.maxToken(c, "20261128", "", "A"))
        assertEquals(3, Sales.forDay(c, dayKey).size)
    }

    @Test fun aVoidStampsOnlyTheVoidGroup() {
        val s = sale(1)
        Sales.record(c, s)
        Sales.setVoided(c, s.uid, true, "Customer left")
        val v = Store.bill(c, s.uid)!!
        assertTrue(v.voided)
        assertEquals("Customer left", v.voidReason)
        assertTrue(v.fieldTimes.getValue("void") > s.updatedAt)
        assertEquals(s.updatedAt, v.fieldTimes.getValue("payment"))
        assertEquals(s.updatedAt, v.fieldTimes.getValue("content"))
        assertTrue("an edit is unsent until the cloud acks it", dirty("bills", s.uid))

        Sales.setVoided(c, s.uid, false)
        val r = Store.bill(c, s.uid)!!
        assertFalse(r.voided)
        assertEquals("", r.voidReason)
        assertFalse(raw("bills", s.uid).has("voidReason"))

        Sales.setPayment(c, s.uid, "UPI")
        assertTrue(Store.bill(c, s.uid)!!.fieldTimes.getValue("payment") > s.updatedAt)
    }

    @Test fun movingADayTakesTheNextTokenThereAndStampsTheDate() {
        Sales.record(c, sale(1))
        val next = Calendar.getInstance().apply { timeInMillis = day; add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
        Sales.record(c, sale(4, time = next))
        val s = sale(2)
        Sales.record(c, s)
        val moved = Sales.moveToDay(c, s.uid, next)!!
        assertEquals(5, moved.token)
        assertEquals("20261128", moved.day)
        assertEquals(Sales.billId(moved.time, "A", 5), moved.id)
        assertTrue(moved.fieldTimes.getValue("date") > s.updatedAt)
        assertEquals(s.updatedAt, moved.fieldTimes.getValue("void"))
    }

    @Test fun savingKeepsFieldsThisVersionDoesNotKnow() {
        val s = sale(1)
        Sales.record(c, s)
        // As if the portal or a newer phone added approvals and something new.
        val data = raw("bills", s.uid).put("approvals", org.json.JSONArray().put(JSONObject().put("action", "priceEdit").put("userId", "usr_x").put("at", 1)))
            .put("futureField", "keep me")
        Store.db(c).execSQL("UPDATE bills SET data = ? WHERE id = ?", arrayOf(data.toString(), s.uid))
        Sales.setPayment(c, s.uid, "UPI")
        val after = raw("bills", s.uid)
        assertEquals("UPI", after.getString("payment"))
        assertEquals("keep me", after.getString("futureField"))
        assertEquals("priceEdit", after.getJSONArray("approvals").getJSONObject(0).getString("action"))
    }

    @Test fun billLinesCarryTheMenuItemSizeAndTax() {
        val s = sale(1).copy(lines = listOf(
            SaleLine("Veg Momos (Half)", 40.0, 2, itemId = "m1", variant = "Half", taxRate = 5.0),
            SaleLine("Birthday candle", 20.0, 1, custom = true),
        ))
        Sales.record(c, s)
        val lines = raw("bills", s.uid).getJSONArray("lines")
        assertEquals("m1", lines.getJSONObject(0).getString("itemId"))
        assertEquals("Half", lines.getJSONObject(0).getString("variant"))
        assertEquals(5.0, lines.getJSONObject(0).getDouble("taxRate"), 0.0)
        assertTrue(lines.getJSONObject(1).getBoolean("custom"))
        assertFalse(lines.getJSONObject(1).has("itemId"))
        val back = Store.bill(c, s.uid)!!
        assertEquals(s.lines, back.lines)
    }

    @Test fun menuEditsKeepThePortalsTaxRateAndSizeIds() {
        Menu.all(c) // starter menu
        val item = MenuItem(Menu.newId(), "Paneer Momos", 50.0, "Momos", listOf(Variant("Half", 50.0), Variant("Full", 100.0)))
        Menu.upsert(c, item)
        val rec = Store.record(c, "menuItem", item.id)!!
        val sizeIds = (0 until 2).map { rec.data.getJSONArray("variants").getJSONObject(it).getString("id") }
        // The portal sets GST on the item and its category.
        Store.saveRecord(c, rec.copy(data = JSONObject(rec.data.toString()).put("taxRate", 12)))
        val cat = Store.record(c, "category", rec.data.getString("categoryId"))!!
        Store.saveRecord(c, cat.copy(data = JSONObject(cat.data.toString()).put("taxRate", 5)))

        Menu.upsert(c, item.copy(price = 60.0, variants = listOf(Variant("Half", 60.0), Variant("Full", 110.0))))
        val after = Store.record(c, "menuItem", item.id)!!
        assertEquals(12, after.data.getInt("taxRate"))
        assertEquals(sizeIds, (0 until 2).map { after.data.getJSONArray("variants").getJSONObject(it).getString("id") })
        assertEquals(6000, after.data.getJSONArray("variants").getJSONObject(0).getInt("price"))
        assertEquals(12.0, Menu.all(c).first { it.id == item.id }.tax!!, 0.0)

        // Saving into an existing category matches it by name, ignoring case.
        val other = MenuItem(Menu.newId(), "Cheese Momos", 70.0, "  momos ")
        Menu.upsert(c, other)
        assertEquals(rec.data.getString("categoryId"), Store.record(c, "menuItem", other.id)!!.data.getString("categoryId"))
        assertEquals("the category's rate applies when the item has none", 5.0, Menu.all(c).first { it.id == other.id }.tax!!, 0.0)

        Menu.delete(c, item.id)
        assertNull(Menu.all(c).find { it.id == item.id })
        assertTrue("archived, not erased", Store.record(c, "menuItem", item.id)!!.deleted)
    }

    @Test fun itemsMoveWithinTheirCategory() {
        writeV06Files()
        Menu.move(c, "m3", -1) // Mango above Vanilla
        assertEquals(listOf("Mango", "Vanilla", "Oreo Shake", "Water"), Menu.all(c).map { it.name })
        Menu.move(c, "m3", -1) // already first in Scoops: nothing happens
        assertEquals(listOf("Mango", "Vanilla", "Oreo Shake", "Water"), Menu.all(c).map { it.name })
        val items = Menu.all(c)
        assertFalse(Menu.canMove(items, items.first { it.id == "m2" }, 1))
    }

    @Test fun theIceCreamOsFeedStillMergesNewestFirst() {
        val s = sale(1)
        Sales.record(c, s)
        val newer = s.copy(payment = "UPI", updatedAt = s.updatedAt + 1000)
        assertEquals(setOf(s.uid), Sales.applyRemote(c, listOf(newer)))
        assertEquals("UPI", Store.bill(c, s.uid)!!.payment)
        val older = s.copy(payment = "CASH", updatedAt = s.updatedAt - 1000)
        assertEquals(emptySet<String>(), Sales.applyRemote(c, listOf(older)))
        assertEquals("UPI", Store.bill(c, s.uid)!!.payment)
    }

    @Test fun csvExportIsUnchanged() {
        Sales.record(c, sale(1).copy(lines = listOf(SaleLine("Tea, hot", 19.99, 2))))
        val csv = Sales.toCsv(Sales.all(c)).lines()
        assertEquals("date,time,token,bill_id,phone,customer,payment,status,void_reason,item,note,qty,rate,amount,bill_discount,discount_reason,bill_total", csv[0])
        assertTrue(csv[1].contains("\"Tea, hot\",,2,19.99,39.98,0.00,,39.98"))
    }

    private fun dirty(table: String, id: String) =
        Store.db(c).rawQuery("SELECT dirty FROM $table WHERE id = ?", arrayOf(id)).use { it.moveToFirst(); it.getInt(0) == 1 }

    private fun sale(token: Int, letter: String = "A", phone: String = "", time: Long = day + token * 60_000L): Sale {
        val now = time
        return Sale(
            id = Sales.billId(time, letter, token), token = token, time = time, letter = letter, phone = phone,
            lines = listOf(SaleLine("Masala Chai", 20.0, 1)), payment = "CASH", customer = "", updatedAt = now,
        )
    }

    @Suppress("unused")
    private fun key(t: Long) = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(t))
}
