package com.parchipos.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
import java.io.File

/**
 * What a device takes into an account and out of it again. The case that
 * started this: a phone took over a demo device's slot (Replace), got that
 * device's bills as its own, left, joined a new account, and uploaded them
 * there as its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProvenanceTest {

    private lateinit var c: Context
    private val t0 = 1_795_700_000_000L
    private val demo = Cloud.Me("https://cloud.test", "dev_slotA", "pk_acc_demo_key", "acc_demo", "Demo shop", "out_demo", "Main", "A", "Galla", 1)
    private val mine = Cloud.Me("https://cloud.test", "dev_new", "pk_acc_mine_key", "acc_mine", "Scoops and Sips", "out_mine", "Main", "A", "Galla", 2)

    @Before fun setUp() {
        Store.closeForTests()
        c = RuntimeEnvironment.getApplication()
        c.filesDir.listFiles()?.forEach { it.delete() }
        c.deleteDatabase("parchi.db")
        CloudApi.transport = object : CloudApi.Transport {
            override fun request(method: String, url: String, bearer: String?, body: String?): Pair<Int, String> {
                // An empty account: everything pushed is taken, and the first download finds nothing.
                val msgs = JSONObject(body ?: "{}").optJSONArray("messages") ?: JSONArray()
                val changes = (0 until msgs.length()).map { msgs.getJSONObject(it) }.firstOrNull { it.optString("type") == "push" }?.getJSONArray("changes") ?: JSONArray()
                val acks = JSONArray().apply { for (i in 0 until changes.length()) changes.getJSONObject(i).let { put(JSONObject().put("kind", it.getString("kind")).put("id", it.getString("id")).put("seq", i + 1)) } }
                val reply = JSONObject().put("protocol", 1).put("messages", JSONArray()
                    .put(JSONObject().put("type", "hello").put("serverTime", System.currentTimeMillis()).put("invoiceSerials", JSONObject()))
                    .put(JSONObject().put("type", "ack").put("results", acks))
                    .put(JSONObject().put("type", "changes").put("records", JSONArray()).put("window", true).put("upTo", 5).put("last", 5).put("more", false)))
                return 200 to reply.toString()
            }
        }
    }

    @After fun tearDown() {
        CloudApi.transport = CloudApi.Http
        Store.closeForTests()
    }

    private fun file(name: String, text: String) = File(c.filesDir, name).writeText(text)

    private fun src(table: String, id: String): String =
        Store.db(c).rawQuery("SELECT src FROM $table WHERE id = ?", arrayOf(id)).use { it.moveToFirst(); it.getString(0) }

    private fun sale(uid: String, at: Long, letter: String = "", device: String? = null, origin: String = "", payment: String = "CASH", customer: String = "") =
        Sale(id = "B-$uid", token = 1, time = at, letter = letter, lines = listOf(SaleLine("Kunafa Waffle", 139.0, 1)),
            payment = payment, customer = customer, uid = uid, updatedAt = at, deviceId = device, origin = origin)

    private fun record(kind: String, id: String, data: JSONObject, seq: Long, outletId: String? = null, byDevice: String? = null) = JSONObject()
        .put("kind", kind).put("id", id).put("outletId", outletId ?: JSONObject.NULL).put("seq", seq).put("updatedAt", t0 + seq)
        .put("byDevice", byDevice ?: JSONObject.NULL).put("deleted", false).put("data", data)

    private fun connect(me: Cloud.Me) = Store.tx(c) {
        Cloud.saveMe(c, me)
        CloudSetup.prepare(c, me)
    }

    @Test fun importedBillsKnowWhoseTheyAre() {
        file("sales.jsonl", listOf(
            """{"id":"261127-001","token":1,"time":$t0,"payment":"CASH","customer":"","lines":[{"name":"Chai","price":20,"qty":1}]}""",
            """{"id":"261127-B005","token":5,"time":${t0 + 1},"payment":"UPI","customer":"","lines":[{"name":"Chai","price":20,"qty":1}],"uid":"u-feed","letter":"B","phone":"Anil"}""",
        ).joinToString("\n"))
        val bills = Store.bills(c)
        assertEquals(Src.OWN, src("bills", bills.first { it.phone.isEmpty() }.uid))
        assertEquals(Src.FEED, src("bills", "u-feed"))
        assertEquals(1, CloudSetup.pendingUpload(c).bills)
    }

    @Test fun aDeviceThatTookOverASlotTakesOnlyItsOwnBillsIntoTheNextAccount() {
        Sales.record(c, sale("real-1", t0))
        Sales.record(c, sale("real-2", t0 + 60_000, payment = CREDIT, customer = "Ramesh"))
        // The demo account, through the demo device A's slot.
        connect(demo)
        assertEquals(demo.deviceId, Store.bill(c, "real-1")!!.deviceId)
        // Down come device A's old bills (this device's now, by the slot), device B's, a payment, and the account's records.
        val down = listOf(
            record("bill", "demoA-1", Bills.toData(sale("demoA-1", t0 - 86_400_000, "A", demo.deviceId, "Counter"), null), 10, demo.outletId, demo.deviceId),
            record("bill", "demoA-2", Bills.toData(sale("demoA-2", t0 - 3_600_000, "A", demo.deviceId, "Counter"), null), 11, demo.outletId, demo.deviceId),
            record("bill", "demoB-1", Bills.toData(sale("demoB-1", t0 - 7_200_000, "B", "dev_slotB", "Window"), null), 12, demo.outletId, "dev_slotB"),
            record("receipt", "rcp-demo", Receipts.toData(Receipt(uid = "rcp-demo", time = t0, customer = "Ramesh Gupta", amount = 250.0, deviceId = "dev_slotB"), null), 13, demo.outletId, "dev_slotB"),
            record("category", "cat_demo", JSONObject().put("name", "Waffles").put("sort", 1), 14),
            record("menuItem", "itm_demo", JSONObject().put("name", "Biscoff Waffle").put("price", 12900).put("categoryId", "cat_demo").put("sort", 1), 15),
            record("staff", "usr_anil", JSONObject().put("name", "Anil").put("role", "owner").put("active", true), 16),
            record("rules", "rules", JSONObject().put("priceEdit", "manager"), 17),
            record("outlet", demo.outletId, JSONObject().put("name", "Main"), 18, demo.outletId),
            record("customer", "cust_demo", JSONObject().put("name", "Priya (Tower C)"), 19, demo.outletId),
            record("itemStatus", "${demo.outletId}:itm_demo", JSONObject().put("itemId", "itm_demo").put("soldOut", true), 20, demo.outletId),
        )
        Store.tx(c) { down.forEach { SyncEngine.applyRecord(c, demo, it) } }
        // By the slot, device A's bills show as this device's, but they came from the account.
        assertEquals("", Store.bill(c, "demoA-1")!!.phone)
        assertEquals(Src.CLOUD, src("bills", "demoA-1"))
        assertEquals("Window", Store.bill(c, "demoB-1")!!.phone)
        // An ack for this device's own bill keeps it its own.
        Store.tx(c) { SyncEngine.applyRecord(c, demo, record("bill", "real-1", Store.raw(c, "bill", "real-1")!!.data, 30, demo.outletId, demo.deviceId)) }
        assertEquals(Src.OWN, src("bills", "real-1"))
        Store.put(c, "inv.26-27", "57")

        val leave = CloudSetup.leaving(c)
        assertEquals(CloudSetup.Leave(bills = 2, payments = 0, otherBills = 3, otherPayments = 1), leave)
        Store.put(c, "cloud.server", "https://staging.test")
        CloudSetup.disconnect(c)

        assertFalse(Cloud.connected(c))
        assertEquals(listOf("real-1", "real-2"), Store.bills(c).map { it.uid })
        assertTrue(Store.bills(c).all { it.phone.isEmpty() })
        assertEquals(0, Store.receipts(c).size)
        for (kind in listOf("staff", "rules", "outlet", "customer", "itemStatus")) assertTrue("$kind is gone", Store.records(c, kind, includeDeleted = true).isEmpty())
        // The menu stays as the till had it, now this device's own.
        val item = Store.record(c, "menuItem", "itm_demo")!!
        assertEquals(0L, item.seq)
        assertNull(item.proof)
        assertEquals("https://staging.test", Store.get(c, "cloud.server"))
        assertNull(Store.get(c, "inv.26-27"))

        // The next account gets this device's two bills, under its new device id, and nothing else.
        assertEquals(2, CloudSetup.pendingUpload(c).bills)
        connect(mine)
        val out = Store.outbox(c, 100)
        assertEquals(setOf("real-1", "real-2", Customers.idFor(mine.outletId, "Ramesh")), out.map { it.id }.toSet())
        assertTrue(out.filter { it.kind == "bill" }.all { it.data.getString("deviceId") == mine.deviceId })
        assertEquals(Customers.idFor(mine.outletId, "Ramesh"), Store.bill(c, "real-2")!!.customerId)
        // The new account has no menu, so the device offers its own (which it can upload: no stale sequence numbers).
        assertEquals("", SyncEngine.runOnce(c))
        assertEquals("ask", Store.get(c, "cloud.menuChoice"))
        assertTrue(Store.records(c, "menuItem").all { it.seq == 0L })
    }

    @Test fun theUpgradeSortsOutADeviceThatSyncedBeforeSourcesWereKept() {
        // parchi.db as version 1 left it, on a device now in its second account.
        val path = c.getDatabasePath("parchi.db").apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE kv (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL("""CREATE TABLE bills (id TEXT PRIMARY KEY, bill_no TEXT NOT NULL, day TEXT NOT NULL, time INTEGER NOT NULL,
                letter TEXT NOT NULL DEFAULT '', token INTEGER NOT NULL, phone TEXT NOT NULL DEFAULT '', payment TEXT NOT NULL,
                customer TEXT NOT NULL DEFAULT '', customer_id TEXT, voided INTEGER NOT NULL DEFAULT 0, total INTEGER NOT NULL,
                data TEXT NOT NULL, updated_at INTEGER NOT NULL, field_times TEXT, seq INTEGER NOT NULL DEFAULT 0,
                dirty INTEGER NOT NULL DEFAULT 0, sync_error TEXT)""")
            db.execSQL("""CREATE TABLE receipts (id TEXT PRIMARY KEY, day TEXT NOT NULL, time INTEGER NOT NULL, customer TEXT NOT NULL DEFAULT '',
                customer_id TEXT, voided INTEGER NOT NULL DEFAULT 0, data TEXT NOT NULL, updated_at INTEGER NOT NULL, field_times TEXT,
                seq INTEGER NOT NULL DEFAULT 0, dirty INTEGER NOT NULL DEFAULT 0, sync_error TEXT)""")
            db.execSQL("""CREATE TABLE records (kind TEXT NOT NULL, id TEXT NOT NULL, outlet_id TEXT, data TEXT NOT NULL,
                deleted INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL, by_user TEXT, proof TEXT, seq INTEGER NOT NULL DEFAULT 0,
                dirty INTEGER NOT NULL DEFAULT 0, sync_error TEXT, PRIMARY KEY (kind, id))""")
            db.execSQL("INSERT INTO kv VALUES ('imported_v1', '1')")
            db.execSQL("INSERT INTO kv VALUES ('cloud.me', ?)", arrayOf(JSONObject().put("server", mine.server).put("deviceId", mine.deviceId).put("deviceKey", mine.deviceKey)
                .put("accountId", mine.accountId).put("outletId", mine.outletId).put("letter", "A").put("name", "Galla").toString()))
            fun bill(s: Sale, phone: String, seq: Long) = db.execSQL(
                "INSERT INTO bills (id, bill_no, day, time, letter, token, phone, payment, total, data, updated_at, seq) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf(s.uid, s.id, s.day, s.time, s.letter, s.token, phone, s.payment, 13900, Bills.toData(s, null).toString(), s.updatedAt, seq))
            // Real bills from before the cloud, stamped by the first account; one shows under its old name after coming back.
            bill(sale("real-uid", t0, device = demo.deviceId, origin = "Galla"), "Galla", 40)
            bill(sale("real-nouid", t0 + 1, device = demo.deviceId, origin = "Galla"), "", 41)
            // The demo slot's own bills, uploaded again as this device's.
            bill(sale("demoA", t0 - 5, "A", demo.deviceId, "Counter"), "", 42)
            // Another demo device's bill, reset to sequence 0 by the second connect.
            bill(sale("demoB", t0 - 6, "B", "dev_slotB", "Window"), "Window", 0)
            // Rung up under the current connection, never near the cloud, and a feed bill.
            bill(sale("now", t0 + 2, "A", mine.deviceId, "Galla"), "", 43)
            bill(sale("local", t0 + 3), "", 0)
            bill(sale("feed", t0 + 4, "B", origin = "Anil"), "Anil", 0)
            fun receipt(r: Receipt, seq: Long) = db.execSQL(
                "INSERT INTO receipts (id, day, time, customer, data, updated_at, seq) VALUES (?,?,?,?,?,?,?)",
                arrayOf(r.uid, r.day, r.time, r.customer, Receipts.toData(r, null).toString(), r.updatedAt, seq))
            receipt(Receipt(uid = "rcp-real", time = t0, customer = "Ramesh", amount = 50.0, deviceId = demo.deviceId), 44)
            receipt(Receipt(uid = "rcp-demo", time = t0, customer = "Ramesh Gupta", amount = 250.0, deviceId = "dev_slotB"), 45)
            db.version = 1
        }
        file("sales.jsonl.imported", listOf(
            """{"id":"B-real-uid","token":1,"time":$t0,"payment":"CASH","customer":"","lines":[],"uid":"real-uid"}""",
            // Old lines had no uid: the bill number and time find it.
            """{"id":"B-real-nouid","token":1,"time":${t0 + 1},"payment":"CASH","customer":"","lines":[]}""",
        ).joinToString("\n"))
        file("receipts.jsonl.imported", """{"uid":"rcp-real","time":$t0,"customer":"Ramesh","amount":50}""")

        val expect = mapOf("real-uid" to Src.OWN, "real-nouid" to Src.OWN, "demoA" to Src.CLOUD, "demoB" to Src.CLOUD, "now" to Src.OWN, "local" to Src.OWN, "feed" to Src.FEED)
        assertEquals(expect, expect.keys.associateWith { src("bills", it) })
        assertEquals("", Store.bill(c, "real-uid")!!.phone)
        assertEquals(Src.OWN, src("receipts", "rcp-real"))
        assertEquals(Src.CLOUD, src("receipts", "rcp-demo"))

        CloudSetup.disconnect(c)
        assertEquals(setOf("real-uid", "real-nouid", "now", "local", "feed"), Store.bills(c).map { it.uid }.toSet())
        assertEquals(listOf("rcp-real"), Store.receipts(c).map { it.uid })
    }

    @Test fun eachConnectKeepsItsOwnMenuBackupAndAnyCanBeRestored() {
        file("menu.json.imported", """[
            {"id":"m1","name":"Vanilla","price":40,"category":"Scoops","variants":[{"label":"Cup","price":40},{"label":"Cone","price":50}]},
            {"id":"m2","name":"Cheese Balls","price":120,"category":"Snacks","variants":[]},
            {"id":"m3","name":"Mango","price":50,"category":"Scoops","variants":[]}
        ]""")
        Menu.replaceAll(c, listOf(MenuItem("", "Biscoff Waffle", 129.0, "Waffles")))
        connect(demo)
        CloudSetup.disconnect(c)
        Menu.replaceAll(c, listOf(MenuItem("", "Oreo Shake", 110.0, "Shakes"), MenuItem("", "Cold Coffee", 80.0, "Shakes")))
        connect(mine)
        CloudSetup.disconnect(c)

        // The menu on the till now is the second backup's, so that one isn't offered.
        assertEquals(1, MenuBackups.list(c).count { it.file.name.startsWith("menu.backup") })
        Menu.replaceAll(c, listOf(MenuItem("", "Masala Chai", 20.0, "Drinks")))
        val saved = MenuBackups.list(c)
        assertEquals(3, saved.size)
        // The device's original comes first, whatever its date; then newest first.
        assertEquals("menu.json.imported", saved.first().file.name)
        assertEquals(listOf(listOf("Oreo Shake", "Cold Coffee"), listOf("Biscoff Waffle")), saved.drop(1).map { b -> b.items.map { it.name } })
        assertTrue(saved.any { it.title.contains("Scoops and Sips") })
        val before = saved.first()

        Menu.replaceAll(c, before.items)
        assertEquals(listOf("Vanilla", "Mango", "Cheese Balls"), Menu.all(c).map { it.name })
        assertEquals(listOf("Scoops", "Snacks"), Menu.categories(c))
        assertEquals(listOf("Cup", "Cone"), Menu.all(c).first().variants.map { it.label })
        // Restoring the same menu twice gives the same menu, not two of each.
        Menu.replaceAll(c, before.items)
        assertEquals(3, Menu.all(c).size)
    }
}
