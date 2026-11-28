package com.parchipos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.URL

/**
 * The live socket against a real Parchi Cloud (wrangler dev or staging). Skipped
 * without PARCHI_E2E_URL, PARCHI_E2E_ADMIN and PARCHI_E2E_PASSWORD.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveE2ETest {
    private val server = System.getenv("PARCHI_E2E_URL")
    private lateinit var c: Context
    private var cookie = ""

    @Before fun setUp() {
        assumeTrue(server != null)
        Store.closeForTests()
        c = RuntimeEnvironment.getApplication()
        c.deleteDatabase("parchi.db")
    }

    @After fun tearDown() {
        if (server == null) return
        Live.stop()
        Store.closeForTests()
    }

    private fun call(method: String, path: String, body: JSONObject?, bearer: String? = null): JSONObject {
        val conn = (URL(server + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("content-type", "application/json")
            setRequestProperty("cf-connecting-ip", "10.1.${(0..250).random()}.${(0..250).random()}")
            if (cookie.isNotEmpty()) setRequestProperty("cookie", cookie)
            if (bearer != null) setRequestProperty("authorization", "Bearer $bearer")
            if (body != null) { doOutput = true; outputStream.use { it.write(body.toString().toByteArray()) } }
        }
        check(conn.responseCode in 200..299) { "$path → ${conn.responseCode}: ${conn.errorStream?.bufferedReader()?.readText()}" }
        conn.getHeaderField("set-cookie")?.let { cookie = it.substringBefore(';') }
        return JSONObject(conn.inputStream.bufferedReader().readText())
    }

    private fun waitFor(what: String, ms: Long = 8_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(25)
        }
    }

    @Test fun aBillOnAnotherDeviceArrivesOnItsOwnAndThisDevicesSaleGoesOverTheSocket() {
        val stamp = System.currentTimeMillis()
        call("POST", "/api/auth/login", JSONObject().put("login", System.getenv("PARCHI_E2E_ADMIN")).put("password", System.getenv("PARCHI_E2E_PASSWORD")))
        val acc = call("POST", "/api/admin/accounts", JSONObject().put("name", "Live E2E $stamp").put("maxDevices", 3)
            .put("outlet", JSONObject().put("name", "Main")).put("owner", JSONObject().put("login", "live-$stamp").put("name", "Owner").put("password", "owner-pass-123")))
        val accountId = acc.getJSONObject("account").getString("id")
        val outletId = acc.getString("outletId")
        fun pair(name: String): JSONObject {
            val code = call("POST", "/api/accounts/$accountId/codes", JSONObject().put("outletId", outletId)).getString("code")
            return call("POST", "/api/pair", JSONObject().put("code", code).put("deviceName", name).put("appVersion", "0.9.0"))
        }
        val counter = pair("Counter")
        val window = pair("Window")

        // This device, on the counter's key.
        Cloud.saveMe(c, Cloud.Me(server!!, counter.getString("deviceId"), counter.getString("deviceKey"), accountId, "Live E2E", outletId, "Main", counter.getString("letter"), "Counter", 1))
        Store.put(c, "cloud.windowDone", "1")
        Live.start(c)
        waitFor("the socket") { Live.ready }

        // The window device rings up a bill over HTTP: it lands here by itself.
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        val t = System.currentTimeMillis()
        val bill = JSONObject().put("kind", "bill").put("id", "e2e-window-$stamp").put("updatedAt", t)
            .put("fieldTimes", JSONObject().put("void", t).put("payment", t).put("date", t).put("content", t))
            .put("data", JSONObject().put("billNo", "${day.substring(2)}-${window.getString("letter")}001").put("token", 1).put("letter", window.getString("letter"))
                .put("day", day).put("time", t).put("deviceId", window.getString("deviceId")).put("payment", "UPI")
                .put("lines", JSONArray().put(JSONObject().put("name", "Kulfi").put("price", 6000).put("qty", 1))))
        val started = System.currentTimeMillis()
        call("POST", "/api/sync", JSONObject().put("protocol", 1).put("messages", JSONArray().put(JSONObject().put("type", "push").put("req", 1).put("changes", JSONArray().put(bill)))), window.getString("deviceKey"))
        waitFor("the window's bill") { Store.bill(c, "e2e-window-$stamp") != null }
        println("LIVE: the window's bill reached this device ${System.currentTimeMillis() - started} ms after it was pushed")

        // A sale here goes up over the socket.
        val sale = Sale(id = "${day.substring(2)}-${counter.getString("letter")}001", token = 1, time = System.currentTimeMillis(), lines = listOf(SaleLine("Chai", 20.0, 2)),
            payment = "CASH", customer = "", deviceId = counter.getString("deviceId"), letter = counter.getString("letter"))
        Sales.record(c, sale)
        SyncEngine.kick(c)
        waitFor("the sale to be taken") { Store.outboxCount(c) == 0 }
        val bills = call("GET", "/api/accounts/$accountId/bills?from=$day", null).getJSONArray("bills")
        assertEquals(setOf("e2e-window-$stamp", sale.uid), (0 until bills.length()).map { bills.getJSONObject(it).getString("id") }.toSet())
    }
}
