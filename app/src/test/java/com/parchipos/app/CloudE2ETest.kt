package com.parchipos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * This phone's real sync code against a real Parchi Cloud server: start
 * `npm run dev` in parchi-cloud, then
 *
 *   PARCHI_E2E_URL=http://127.0.0.1:8787 PARCHI_E2E_ADMIN=admin PARCHI_E2E_PASSWORD=… gradle testDebugUnitTest --tests '*CloudE2ETest*'
 *
 * Skipped when PARCHI_E2E_URL is not set (as in CI). Each run makes a fresh
 * account. The other phone is played over plain HTTP, like scripts/fake-phone.ts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudE2ETest {

    private val server = System.getenv("PARCHI_E2E_URL")
    private lateinit var c: Context
    private val now = System.currentTimeMillis()
    private val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(now))

    @Before fun setUp() {
        assumeTrue("set PARCHI_E2E_URL to run against a server", server != null)
        Store.closeForTests()
        c = RuntimeEnvironment.getApplication()
        c.filesDir.listFiles()?.forEach { it.delete() }
        c.deleteDatabase("parchi.db")
    }

    @After fun tearDown() = Store.closeForTests()

    // ── a tiny HTTP client for the portal and the other phone ───────────

    private class Web(val base: String) {
        var cookie = ""
        fun call(method: String, path: String, body: Any? = null, bearer: String? = null): Pair<Int, JSONObject> {
            val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                setRequestProperty("content-type", "application/json")
                if (cookie.isNotEmpty()) setRequestProperty("cookie", cookie)
                if (bearer != null) setRequestProperty("authorization", "Bearer $bearer")
                if (body != null || method != "GET") { doOutput = true; outputStream.use { it.write((body ?: JSONObject()).toString().toByteArray()) } }
            }
            val code = conn.responseCode
            conn.getHeaderField("set-cookie")?.let { cookie = it.substringBefore(';') }
            val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: "{}"
            return code to (try { JSONObject(text) } catch (_: Exception) { JSONObject() })
        }
        fun ok(method: String, path: String, body: Any? = null, bearer: String? = null): JSONObject {
            val (code, json) = call(method, path, body, bearer)
            check(code in 200..299) { "$method $path → $code $json" }
            return json
        }
    }

    private fun v06Files() {
        val t = now - 3_600_000
        File(c.filesDir, "sales.jsonl").writeText(listOf(
            """{"id":"x-A001","token":1,"time":$t,"payment":"CASH","customer":"","voided":false,"lines":[{"name":"Veg Momos (Half)","price":45.5,"qty":2}],"uid":"e2e-own-1-$now","updatedAt":$t,"letter":"A","phone":""}""",
            """{"id":"x-A002","token":2,"time":${t + 60_000},"payment":"CREDIT","customer":"Ramesh ","voided":false,"lines":[{"name":"Oreo Shake","price":110,"qty":1}],"uid":"e2e-own-2-$now","updatedAt":${t + 60_000},"letter":"A","phone":""}""",
            """{"id":"x-B009","token":9,"time":${t + 120_000},"payment":"UPI","customer":"","voided":false,"lines":[{"name":"Masala Chai","price":20,"qty":1}],"uid":"e2e-other-$now","updatedAt":${t + 120_000},"letter":"B","phone":"Anil"}""",
        ).joinToString("\n") + "\n")
        File(c.filesDir, "receipts.jsonl").writeText("""{"uid":"e2e-r-$now","time":${t + 180_000},"customer":"ramesh","amount":50,"mode":"UPI"}""" + "\n")
        File(c.filesDir, "menu.json").writeText("""[{"id":"e2e-m1-$now","name":"Vanilla","price":40,"category":"Scoops","variants":[{"label":"Cup","price":40},{"label":"Cone","price":50}]},
            {"id":"e2e-m2-$now","name":"Oreo Shake","price":110,"category":"Shakes","variants":[]}]""")
    }

    @Test fun aPhoneConnectsUploadsItsOwnHistoryAndSyncsWithAnother() {
        val admin = Web(server!!)
        admin.ok("POST", "/api/auth/login", JSONObject().put("login", System.getenv("PARCHI_E2E_ADMIN")).put("password", System.getenv("PARCHI_E2E_PASSWORD")))
        val ownerLogin = "e2e-owner-$now"
        val acc = admin.ok("POST", "/api/admin/accounts", JSONObject().put("name", "E2E $now").put("maxDevices", 3)
            .put("outlet", JSONObject().put("name", "Main")).put("owner", JSONObject().put("login", ownerLogin).put("name", "Owner").put("password", "owner-pass-123")))
        val accountId = acc.getJSONObject("account").getString("id")
        val outletId = acc.getString("outletId")
        val owner = Web(server)
        owner.ok("POST", "/api/auth/login", JSONObject().put("login", ownerLogin).put("password", "owner-pass-123"))
        owner.ok("POST", "/api/me/pin", JSONObject().put("password", "owner-pass-123").put("pin", "2468"))
        val codeA = owner.ok("POST", "/api/accounts/$accountId/codes", JSONObject().put("outletId", outletId)).getString("code")
        val codeB = owner.ok("POST", "/api/accounts/$accountId/codes", JSONObject().put("outletId", outletId)).getString("code")

        // Phone A: this app, with v0.6 history, connects.
        v06Files()
        Prefs.setLetter(c, "A")
        assertEquals(CloudSetup.Upload(2, 1, today, today).bills, CloudSetup.pendingUpload(c).bills)
        val me = CloudSetup.connect(c, server, codeA.lowercase().replace("-", ""), "Counter")
        assertEquals("keeps its old letter", "A", me.letter)
        assertEquals("", SyncEngine.runOnce(c))
        assertEquals("not held back as too old", Cloud.State.LIVE, Cloud.status(c).state)
        assertEquals("everything sent", 0, Store.outboxCount(c))

        val bills = owner.ok("GET", "/api/accounts/$accountId/bills?from=$today&to=$today").getJSONArray("bills")
        val ids = (0 until bills.length()).map { bills.getJSONObject(it).getString("id") }.toSet()
        assertEquals("its own bills go, another phone's don't", setOf("e2e-own-1-$now", "e2e-own-2-$now"), ids)
        val credit = (0 until bills.length()).map { bills.getJSONObject(it) }.first { it.getString("id") == "e2e-own-2-$now" }
        assertEquals(Customers.idFor(outletId, "ramesh"), credit.getJSONObject("data").getString("customerId"))
        assertEquals(me.deviceId, credit.getJSONObject("data").getString("deviceId"))
        assertEquals(9100, (0 until bills.length()).map { bills.getJSONObject(it) }.first { it.getString("id") == "e2e-own-1-$now" }.getJSONObject("figures").getInt("total"))
        assertEquals("the account had no menu: the phone offers its own", "ask", Store.get(c, "cloud.menuChoice"))
        assertNotNull("staff and rules came down", Store.record(c, "rules", "rules"))

        // The owner's PIN, checked offline, uploads the menu.
        val (staff, key) = CloudSetup.checkPin(c, "2468")!!
        assertNull(CloudSetup.checkPin(c, "1111"))
        CloudSetup.uploadMenu(c, staff, key)
        assertEquals("", SyncEngine.runOnce(c))
        assertEquals("menu accepted with the PIN proof", 0, Store.outboxCount(c))
        assertEquals(2, owner.ok("GET", "/api/accounts/$accountId/records?kind=menuItem").getJSONArray("records").length())

        // Phone B, played over HTTP, rings up a bill; A gets it under B's name.
        val phoneB = Web(server)
        val pb = phoneB.ok("POST", "/api/pair", JSONObject().put("code", codeB).put("deviceName", "Window").put("appVersion", "0.7.0"))
        val keyB = pb.getString("deviceKey")
        val tB = System.currentTimeMillis()
        val billB = JSONObject().put("kind", "bill").put("id", "e2e-b-$now").put("updatedAt", tB)
            .put("fieldTimes", JSONObject().put("void", tB).put("payment", tB).put("date", tB).put("content", tB))
            .put("data", JSONObject().put("billNo", "x-B001").put("token", 1).put("letter", pb.getString("letter")).put("day", today).put("time", tB)
                .put("deviceId", pb.getString("deviceId")).put("origin", "Window").put("payment", "CASH").put("customer", "").put("voided", false)
                .put("lines", JSONArray().put(JSONObject().put("name", "Masala Chai").put("price", 2000).put("qty", 1))))
        phoneB.ok("POST", "/api/sync", JSONObject().put("protocol", 1).put("messages", JSONArray().put(JSONObject().put("type", "push").put("req", 1).put("changes", JSONArray().put(billB)))), keyB)
        assertEquals("", SyncEngine.runOnce(c))
        val got = Store.bill(c, "e2e-b-$now")!!
        assertEquals("Window", got.phone)
        assertEquals(20.0, got.total, 0.0)

        // A voids it while B moves it to UPI: both survive, on both.
        Sales.setVoided(c, "e2e-b-$now", true, "Wrong item")
        val tB2 = System.currentTimeMillis() + 5
        val upi = JSONObject(billB.toString()).put("updatedAt", tB2)
        upi.getJSONObject("fieldTimes").put("payment", tB2)
        upi.getJSONObject("data").put("payment", "UPI")
        phoneB.ok("POST", "/api/sync", JSONObject().put("protocol", 1).put("messages", JSONArray().put(JSONObject().put("type", "push").put("req", 2).put("changes", JSONArray().put(upi)))), keyB)
        assertEquals("", SyncEngine.runOnce(c))
        val both = Store.bill(c, "e2e-b-$now")!!
        assertTrue(both.voided)
        assertEquals("UPI", both.payment)
        assertEquals(0, Store.outboxCount(c))
        val onServer = owner.ok("GET", "/api/accounts/$accountId/history?kind=bill&id=e2e-b-$now").getJSONArray("entries").getJSONObject(0).getJSONObject("data")
        assertTrue(onServer.getBoolean("voided"))
        assertEquals("UPI", onServer.getString("payment"))

        // A new bill on A gets the next invoice number.
        val inv = Cloud.nextInvoice(c, today)!!
        assertTrue(inv, Regex("^A/\\d{2}-\\d{2}/000001$").matches(inv))
        val t = Cloud.now(c)
        Sales.record(c, Sale(id = Sales.billId(t, "A", 3), token = 3, time = t, letter = "A", lines = listOf(SaleLine("Mango", 50.0, 1)),
            payment = "CASH", customer = "", updatedAt = t, deviceId = me.deviceId, origin = me.name, invoiceNo = inv))
        assertEquals("", SyncEngine.runOnce(c))
        val bills2 = owner.ok("GET", "/api/accounts/$accountId/bills?from=$today&to=$today").getJSONArray("bills")
        assertTrue((0 until bills2.length()).any { bills2.getJSONObject(it).getJSONObject("data").optString("invoiceNo") == inv })

        // The portal voids A's credit bill; A picks it up.
        owner.ok("POST", "/api/accounts/$accountId/changes", JSONObject().put("changes", JSONArray().put(JSONObject().put("kind", "bill").put("id", "e2e-own-2-$now").put("data", JSONObject().put("voided", true).put("voidReason", "Portal test")))))
        assertEquals("", SyncEngine.runOnce(c))
        assertTrue(Store.bill(c, "e2e-own-2-$now")!!.voided)
        assertFalse(Cloud.status(c).state == Cloud.State.ERROR)
    }
}
