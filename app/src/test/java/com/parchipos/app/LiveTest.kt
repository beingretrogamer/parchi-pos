package com.parchipos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * The live socket against a small WebSocket server in the test: the handshake,
 * a sale's round over the socket instead of HTTP, a record another device wrote
 * arriving on its own, and keep-alives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveTest {

    /** Speaks just enough of Parchi Cloud's live socket. */
    private class Server : AutoCloseable {
        val socket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val pushed = CopyOnWriteArrayList<String>()
        val frames = CopyOnWriteArrayList<String>()
        @Volatile var auth = ""
        @Volatile private var out: OutputStream? = null

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val s = try { socket.accept() } catch (_: Exception) { break }
                    thread(isDaemon = true) { serve(s) }
                }
            }
        }

        private fun serve(s: Socket) {
            val input = BufferedInputStream(s.getInputStream())
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
            val lines = head.split("\r\n")
            auth = lines.firstOrNull { it.startsWith("Authorization:", true) }?.substringAfter(':')?.trim() ?: ""
            val key = lines.first { it.startsWith("Sec-WebSocket-Key:", true) }.substringAfter(':').trim()
            val o = s.getOutputStream()
            o.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${WsCodec.acceptFor(key)}\r\n\r\n".toByteArray())
            out = o
            send(envelope(hello()))
            val reader = WsCodec.Reader(input)
            while (true) {
                val m = try { reader.next() } catch (_: Exception) { null } ?: break
                if (m.opcode != WsCodec.TEXT) continue
                frames += m.text
                if (m.text == "ping") { send("pong"); continue }
                val msgs = JSONObject(m.text).getJSONArray("messages")
                val replies = JSONArray().put(hello())
                for (i in 0 until msgs.length()) {
                    val msg = msgs.getJSONObject(i)
                    when (msg.optString("type")) {
                        "push" -> {
                            val changes = msg.getJSONArray("changes")
                            val acks = JSONArray()
                            for (j in 0 until changes.length()) changes.getJSONObject(j).let {
                                pushed += it.getString("id")
                                acks.put(JSONObject().put("kind", it.getString("kind")).put("id", it.getString("id")).put("seq", 100 + pushed.size))
                            }
                            replies.put(JSONObject().put("type", "ack").put("results", acks))
                        }
                        "pull" -> replies.put(JSONObject().put("type", "changes").put("records", JSONArray()).put("last", msg.optLong("after")).put("more", false))
                    }
                }
                send(JSONObject().put("protocol", 1).put("messages", replies).toString())
            }
        }

        fun hello() = JSONObject().put("type", "hello").put("serverTime", System.currentTimeMillis()).put("invoiceSerials", JSONObject())
        fun envelope(vararg m: JSONObject) = JSONObject().put("protocol", 1).put("messages", JSONArray().apply { m.forEach { put(it) } }).toString()

        @Synchronized fun send(text: String) {
            val p = text.toByteArray()
            val f = ByteArrayOutputStream()
            f.write(0x81)
            when {
                p.size < 126 -> f.write(p.size)
                p.size <= 0xFFFF -> { f.write(126); f.write(p.size shr 8); f.write(p.size and 0xFF) }
                else -> { f.write(127); for (i in 7 downTo 0) f.write(((p.size.toLong() shr (8 * i)) and 0xFF).toInt()) }
            }
            f.write(p)
            out?.apply { write(f.toByteArray()); flush() }
        }

        override fun close() = socket.close()
    }

    private lateinit var c: Context
    private lateinit var server: Server
    private val httpCalls = CopyOnWriteArrayList<String>()

    @Before fun setUp() {
        Store.closeForTests()
        c = RuntimeEnvironment.getApplication()
        c.deleteDatabase("parchi.db")
        server = Server()
        Cloud.saveMe(c, Cloud.Me("http://127.0.0.1:${server.socket.localPort}", "dev_counter", "pk_acc_x_secret", "acc_x", "Shop", "out_main", "Main", "A", "Counter", 1))
        Store.put(c, "cloud.windowDone", "1")
        // Anything that goes over HTTP is noted and refused: in this test it should all go over the socket.
        CloudApi.transport = object : CloudApi.Transport {
            override fun request(method: String, url: String, bearer: String?, body: String?): Pair<Int, String> {
                httpCalls += url
                return 503 to "{}"
            }
        }
    }

    @After fun tearDown() {
        Live.stop()
        server.close()
        CloudApi.transport = CloudApi.Http
        Store.closeForTests()
    }

    private fun waitFor(what: String, ms: Long = 5_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    @Test fun roundsGoOverTheSocketAndOtherDevicesChangesArriveOnTheirOwn() {
        Live.start(c)
        waitFor("the socket to open") { Live.ready }
        assertEquals("Bearer pk_acc_x_secret", server.auth)

        // A sale at the counter: its round goes over the socket, and the server's ack cleans it.
        val sale = Sale(id = "261128-A001", token = 1, time = System.currentTimeMillis(), lines = listOf(SaleLine("Chai", 20.0, 2)), payment = "CASH", customer = "", deviceId = "dev_counter")
        Sales.record(c, sale)
        SyncEngine.kick(c)
        waitFor("the push") { sale.uid in server.pushed }
        waitFor("the ack") { Store.outboxCount(c) == 0 }

        // Another device's bill arrives by itself, the moment the server sends it.
        val data = JSONObject().put("billNo", "261128-B001").put("token", 1).put("letter", "B").put("day", "20261128").put("time", System.currentTimeMillis())
            .put("deviceId", "dev_window").put("origin", "Window").put("payment", "UPI").put("lines", JSONArray().put(JSONObject().put("name", "Kulfi").put("price", 6000).put("qty", 1)))
        server.send(server.envelope(JSONObject().put("type", "live").put("records", JSONArray().put(
            JSONObject().put("kind", "bill").put("id", "bill-window-1").put("outletId", "out_main").put("seq", 7).put("updatedAt", 1L).put("deleted", false).put("byDevice", "dev_window").put("data", data),
        ))))
        waitFor("the live bill") { Store.bill(c, "bill-window-1") != null }
        assertEquals("UPI", Store.bill(c, "bill-window-1")!!.payment)

        // Keep-alives get their answer, and nothing went over HTTP.
        assertTrue(httpCalls.toString(), httpCalls.isEmpty())
    }

    @Test fun withoutTheSocketSyncCarriesOnOverHttp() {
        assertEquals(null, Live.round(JSONArray()))
        SyncEngine.runOnce(c)
        assertTrue(httpCalls.any { it.endsWith("/api/sync") })
    }
}
