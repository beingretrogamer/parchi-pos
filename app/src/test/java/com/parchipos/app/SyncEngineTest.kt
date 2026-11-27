package com.parchipos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The sync engine against a scripted server: the cases a real one rarely shows on demand. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncEngineTest {

    private lateinit var c: Context
    private val requests = ArrayList<JSONObject>()
    /** Builds the reply to one /api/sync request. */
    private var server: (JSONObject) -> Pair<Int, JSONObject> = { 200 to reply(hello()) }
    private val me = Cloud.Me("https://cloud.test", "dev_me", "pk_acc_x_key", "acc_x", "Shop", "out_1", "Main", "A", "Counter", 1)
    private val t0 = 1_800_000_000_000L

    @Before fun setUp() {
        Store.closeForTests()
        c = RuntimeEnvironment.getApplication()
        c.filesDir.listFiles()?.forEach { it.delete() }
        c.deleteDatabase("parchi.db")
        Cloud.saveMe(c, me)
        Store.put(c, "cloud.windowDone", "1")
        CloudApi.transport = object : CloudApi.Transport {
            override fun request(method: String, url: String, bearer: String?, body: String?): Pair<Int, String> {
                val req = JSONObject(body ?: "{}")
                requests.add(req)
                val (code, json) = server(req)
                return code to json.toString()
            }
        }
    }

    @After fun tearDown() {
        CloudApi.transport = CloudApi.Http
        Store.closeForTests()
    }

    private fun hello() = JSONObject().put("type", "hello").put("serverTime", System.currentTimeMillis()).put("minAppVersion", "0.7.0").put("latestAppVersion", "0.7.0")
        .put("device", JSONObject().put("id", me.deviceId).put("name", me.name).put("letter", "A").put("status", "active"))
        .put("devices", JSONArray().put(JSONObject().put("id", "dev_b").put("name", "Window").put("letter", "B").put("status", "active")))
        .put("invoiceSerials", JSONObject())

    private fun reply(vararg m: JSONObject) = JSONObject().put("protocol", 1).put("messages", JSONArray().apply { m.forEach { put(it) } })

    private fun push(req: JSONObject): JSONArray? {
        val msgs = req.getJSONArray("messages")
        return (0 until msgs.length()).map { msgs.getJSONObject(it) }.firstOrNull { it.optString("type") == "push" }?.getJSONArray("changes")
    }

    private fun pull(req: JSONObject): JSONObject? {
        val msgs = req.getJSONArray("messages")
        return (0 until msgs.length()).map { msgs.getJSONObject(it) }.firstOrNull { it.optString("type") == "pull" }
    }

    private fun ackAll(changes: JSONArray?) = JSONObject().put("type", "ack").put("results", JSONArray().apply {
        if (changes != null) for (i in 0 until changes.length()) changes.getJSONObject(i).let { put(JSONObject().put("kind", it.getString("kind")).put("id", it.getString("id")).put("seq", 100 + i)) }
    })

    private fun empty(after: Long = 0) = JSONObject().put("type", "changes").put("records", JSONArray()).put("last", after).put("more", false)

    private fun bill(uid: String, at: Long = t0) = Sale(id = "261127-A00$uid", token = 1, time = at, letter = "A", lines = listOf(SaleLine("Chai", 20.0, 1)),
        payment = "CASH", customer = "", uid = uid, updatedAt = at, deviceId = me.deviceId, origin = me.name)

    private fun dirty(id: String) = Store.raw(c, "bill", id)!!.dirty

    @Test fun anAckCleansOnlyWhatWasSent() {
        Sales.record(c, bill("1"))
        Sales.record(c, bill("2"))
        server = { req ->
            // While the push is in flight, bill 2 is voided at the counter.
            if (push(req) != null && requests.size == 1) Sales.setVoided(c, "2", true, "Wrong item")
            200 to reply(hello(), ackAll(push(req)), empty())
        }
        assertEquals("", SyncEngine.runOnce(c))
        assertFalse(dirty("1"))
        assertEquals(100L, Store.raw(c, "bill", "1")!!.seq)
        // The void went out in the next round, because the first ack was for the state before it.
        assertEquals(2, requests.count { push(it) != null })
        assertFalse(dirty("2"))
        assertTrue(Store.bill(c, "2")!!.voided)
    }

    @Test fun aRefusedChangeIsKeptMarkedAndNotRetried() {
        Sales.record(c, bill("1"))
        server = { req ->
            val p = push(req)
            200 to reply(hello(), JSONObject().put("type", "ack").put("results", JSONArray().apply {
                if (p != null) put(JSONObject().put("kind", "bill").put("id", "1").put("rejected", "discount is more than the bill"))
            }), empty())
        }
        assertEquals("", SyncEngine.runOnce(c))
        assertFalse(dirty("1"))
        assertEquals(listOf("Bill 261127-A001: discount is more than the bill"), Store.rejected(c))
        requests.clear()
        SyncEngine.runOnce(c)
        assertTrue("not sent again", requests.none { push(it) != null })
        assertTrue("still on the phone", Store.bill(c, "1") != null)
    }

    @Test fun whenTheServerHoldsMoreTheAckCarriesItAndItIsTaken() {
        Sales.record(c, bill("1"))
        server = { req ->
            val p = push(req)
            val results = JSONArray()
            if (p != null) {
                val sent = p.getJSONObject(0)
                val data = JSONObject(sent.getJSONObject("data").toString()).put("voided", true).put("voidReason", "Voided in the portal")
                results.put(JSONObject().put("kind", "bill").put("id", "1").put("seq", 7).put("record", JSONObject()
                    .put("kind", "bill").put("id", "1").put("seq", 7).put("updatedAt", t0 + 500).put("byDevice", "portal")
                    .put("fieldTimes", JSONObject().put("void", t0 + 500).put("payment", t0).put("date", t0).put("content", t0)).put("data", data)))
            }
            200 to reply(hello(), JSONObject().put("type", "ack").put("results", results), empty())
        }
        SyncEngine.runOnce(c)
        val b = Store.bill(c, "1")!!
        assertTrue(b.voided)
        assertEquals("Voided in the portal", b.voidReason)
        assertFalse(dirty("1"))
    }

    @Test fun aPulledEditMergesWithAnEditMadeHereMeanwhile() {
        val s = bill("1")
        Sales.record(c, s)
        Store.markSent(c, "bill", "1", s.updatedAt, 3) // on the server already
        var voidAt = 0L
        server = { req ->
            if (requests.size == 1) {
                // While this round is in flight, the counter voids the bill...
                Sales.setVoided(c, "1", true, "Customer left")
                voidAt = Store.raw(c, "bill", "1")!!.fieldTimes!!.getValue("void")
                // ...and the reply brings the other phone's payment change to it.
                val remote = Bills.toData(s.copy(payment = "UPI"), null)
                200 to reply(hello(), JSONObject().put("type", "changes").put("more", false).put("last", 9).put("records", JSONArray().put(JSONObject()
                    .put("kind", "bill").put("id", "1").put("seq", 9).put("updatedAt", s.updatedAt + 10).put("byDevice", "dev_b")
                    .put("fieldTimes", JSONObject().put("void", s.updatedAt).put("payment", s.updatedAt + 10).put("date", s.updatedAt).put("content", s.updatedAt)).put("data", remote))))
            } else {
                200 to reply(hello(), ackAll(push(req)), empty(9))
            }
        }
        SyncEngine.runOnce(c)
        val b = Store.bill(c, "1")!!
        assertTrue("the void made here survives", b.voided)
        assertEquals("the payment change from the other phone survives", "UPI", b.payment)
        assertTrue(voidAt > 0)
        val sent = push(requests[1])!!.getJSONObject(0)
        assertEquals("the merged state is what goes up", "UPI", sent.getJSONObject("data").getString("payment"))
        assertTrue(sent.getJSONObject("data").getBoolean("voided"))
        assertFalse(dirty("1"))
        assertEquals("Window", Cloud.phones(c).first().name)
    }

    @Test fun anOlderPageNeverUndoesANewerAck() {
        val s = bill("1")
        Sales.record(c, s)
        Store.markSent(c, "bill", "1", s.updatedAt, 50)
        server = { 200 to reply(hello(), JSONObject().put("type", "changes").put("more", false).put("last", 9).put("records", JSONArray().put(JSONObject()
            .put("kind", "bill").put("id", "1").put("seq", 9).put("updatedAt", s.updatedAt - 10).put("data", Bills.toData(s.copy(payment = "UPI"), null))))) }
        SyncEngine.runOnce(c)
        assertEquals("CASH", Store.bill(c, "1")!!.payment)
    }

    @Test fun theFirstDownloadIsAWindowThenTheCursorCarriesOn() {
        Store.put(c, "cloud.windowDone", null)
        server = { req ->
            val p = pull(req)!!
            when (requests.size) {
                1 -> {
                    assertTrue(p.has("window"))
                    assertFalse(p.has("upTo"))
                    200 to reply(hello(), JSONObject().put("type", "changes").put("window", true).put("records", JSONArray()).put("last", 10).put("upTo", 20).put("more", true))
                }
                2 -> {
                    assertEquals(10L, p.getLong("after"))
                    assertEquals(20L, p.getLong("upTo"))
                    200 to reply(hello(), JSONObject().put("type", "changes").put("window", true).put("records", JSONArray()).put("last", 15).put("upTo", 20).put("more", false))
                }
                else -> {
                    assertFalse(p.has("window"))
                    assertEquals(20L, p.getLong("after"))
                    200 to reply(hello(), empty(20))
                }
            }
        }
        SyncEngine.runOnce(c)
        assertEquals("1", Store.get(c, "cloud.windowDone"))
        assertEquals("20", Store.get(c, "cloud.cursor"))
        SyncEngine.runOnce(c)
        assertEquals(3, requests.size)
    }

    @Test fun anOldAppStopsSyncingButKeepsEverything() {
        Sales.record(c, bill("1"))
        server = { 200 to reply(hello(), JSONObject().put("type", "upgrade").put("minAppVersion", "0.8.0")) }
        SyncEngine.runOnce(c)
        assertEquals(Cloud.State.UPGRADE, Cloud.status(c).state)
        assertTrue(dirty("1"))
        assertEquals(1, requests.size)
    }

    @Test fun aRemovedKeyStopsAndSaysSo() {
        server = { 401 to JSONObject().put("error", "unknown_key") }
        val err = SyncEngine.runOnce(c)
        assertTrue(err, err.contains("removed or replaced"))
        assertEquals(Cloud.State.REMOVED, Cloud.status(c).state)
    }

    @Test fun noNetworkIsOfflineNotAnError() {
        CloudApi.transport = object : CloudApi.Transport {
            override fun request(method: String, url: String, bearer: String?, body: String?): Pair<Int, String> = throw java.net.UnknownHostException("cloud.test")
        }
        SyncEngine.runOnce(c)
        assertEquals(Cloud.State.OFFLINE, Cloud.status(c).state)
    }

    @Test fun invoiceSerialsCarryOnFromTheServer() {
        server = { 200 to reply(hello().put("invoiceSerials", JSONObject().put("26-27", 41)), empty()) }
        SyncEngine.runOnce(c)
        assertEquals("A/26-27/000042", Cloud.nextInvoice(c, "20261128"))
        assertEquals("A/26-27/000043", Cloud.nextInvoice(c, "20270331"))
        assertEquals("a new financial year starts again", "A/27-28/000001", Cloud.nextInvoice(c, "20270401"))
    }

    @Test fun pinsMatchTheServersDerivation() {
        // From parchi-cloud's own auth.ts: pinKey('2468', salt), sha256Hex(key), signProof(key, 'menuItem', 'mi_test', 1795800000000).
        val salt = "00112233445566778899aabbccddeeff"
        val key = Pin.key("2468", salt)
        assertEquals("680d7e5099c8e9f511552c28df2c4359668a6e546e808107b35f83172fdc44da", key)
        assertEquals("86cc279a4f58d6fa06154c2fff6ec018857581082f4629cbd7acd75ba8505616", Pin.verifier(key))
        assertEquals("7f35065ee8161697a417379d3ac87e58511c5f86d76b3b96d1d973a4c5c97283", Pin.proof(key, "menuItem", "mi_test", 1795800000000))
        assertEquals(key, Pin.matches("2468", salt, "86cc279a4f58d6fa06154c2fff6ec018857581082f4629cbd7acd75ba8505616"))
        assertEquals(null, Pin.matches("2469", salt, "86cc279a4f58d6fa06154c2fff6ec018857581082f4629cbd7acd75ba8505616"))
    }
}
