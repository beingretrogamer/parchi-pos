package com.parchipos.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The live socket to Parchi Cloud (plan §5, milestone 5), open while a Parchi
 * screen is on show. It carries the same rounds as HTTP sync (one at a time,
 * each answered in order), and every change another device or the portal makes
 * arrives on it the moment it is written. When it can't connect, sync carries
 * on over HTTP as before, and the socket is tried again later.
 */
object Live {
    private const val PING_MS = 25_000L
    /** No frame at all for this long (pongs included) and the socket is taken for dead. */
    private const val DEAD_MS = 70_000L
    private val BACKOFF_MS = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)
    /** After this many failures in a row, HTTP polling carries the device and the socket is tried every 2 minutes. */
    private const val GIVE_UP_AFTER = 3
    private const val RETRY_LATER_MS = 120_000L

    private val ui by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var client: WsClient? = null
    /** Open and greeted (the server's hello came): rounds may go over it. */
    @Volatile var ready = false
        private set
    @Volatile private var wanted = false
    private var failures = 0
    private var app: Context? = null
    private val roundLock = Any()
    @Volatile private var reply: ArrayBlockingQueue<JSONArray>? = null

    private val keepAlive = object : Runnable {
        override fun run() {
            val c = client ?: return
            if (ready && System.currentTimeMillis() - c.lastFrameAt > DEAD_MS) { c.close(); return }
            if (ready) c.send("ping")
            ui.postDelayed(this, PING_MS)
        }
    }
    private val reconnect = Runnable { open() }

    /** A Parchi screen came on show: keep the socket open (connected devices only). */
    fun start(c: Context) {
        app = c.applicationContext
        wanted = true
        if (client == null) open()
    }

    /** The last screen left a while ago: close it. Sync falls back to its HTTP job. */
    fun stop() {
        wanted = false
        ui.removeCallbacks(reconnect)
        ui.removeCallbacks(keepAlive)
        client?.close()
        client = null
        ready = false
    }

    private fun open() {
        val c = app ?: return
        if (!wanted || client != null) return
        val me = Cloud.me(c) ?: return
        if (Store.get(c, "cloud.removed") == "1") return
        val ws = WsClient(me.server.trimEnd('/') + "/api/sync", me.deviceKey, object : WsClient.Listener {
            override fun onOpen() {}

            override fun onText(text: String) = received(c, text)

            override fun onClosed(code: Int, reason: String, status: Int) {
                client = null
                ready = false
                reply?.offer(JSONArray()) // a round waiting on it goes over HTTP instead
                ui.removeCallbacks(keepAlive)
                if (status == 401 || status == 403 || code == 4001) {
                    // Removed, replaced or the account closed: HTTP says which and shows it.
                    failures = GIVE_UP_AFTER
                    SyncEngine.kick(c)
                } else failures++
                if (wanted) ui.postDelayed(reconnect, if (failures >= GIVE_UP_AFTER) RETRY_LATER_MS else BACKOFF_MS[minOf(failures, BACKOFF_MS.size) - 1])
            }
        })
        client = ws
        ws.connect()
    }

    private fun received(c: Context, text: String) {
        if (text == "pong") return
        val messages = try { JSONObject(text).optJSONArray("messages") ?: return } catch (_: Exception) { return }
        val types = (0 until messages.length()).map { messages.optJSONObject(it)?.optString("type") ?: "" }
        if ("revoked" in types) { client?.close(); return }
        if (types.isNotEmpty() && types.all { it == "live" }) {
            applyLive(c, messages)
            return
        }
        if (!ready) {
            // The server's hello as the socket opens: from now on rounds go over it.
            ready = true
            failures = 0
            ui.removeCallbacks(keepAlive)
            ui.postDelayed(keepAlive, PING_MS)
            SyncEngine.kick(c) // send what is waiting, fetch what was missed
            return
        }
        reply?.offer(messages)
    }

    /** Records another device or the portal just wrote: in at once. The cursor moves only on pulls, so a gap is never skipped. */
    private fun applyLive(c: Context, messages: JSONArray) {
        val me = Cloud.me(c) ?: return
        try {
            Store.tx(c) {
                for (i in 0 until messages.length()) {
                    val recs = messages.getJSONObject(i).optJSONArray("records") ?: continue
                    for (j in 0 until recs.length()) SyncEngine.applyRecord(c, me, recs.getJSONObject(j))
                }
            }
        } catch (e: Exception) {
            PrintLog.append("Live update not applied: ${e.message}")
            return
        }
        SyncEngine.changed()
    }

    /**
     * One sync round over the socket: the same messages as HTTP, and the server's replies.
     * Null when the socket isn't ready or doesn't answer in time; the caller goes over HTTP.
     */
    fun round(messages: JSONArray, timeoutMs: Long = 15_000): JSONArray? {
        if (!ready) return null
        synchronized(roundLock) {
            val ws = client ?: return null
            val slot = ArrayBlockingQueue<JSONArray>(1)
            reply = slot
            try {
                if (!ws.send(JSONObject().put("protocol", 1).put("messages", messages).toString())) return null
                val got = slot.poll(timeoutMs, TimeUnit.MILLISECONDS)
                if (got == null) { ws.close(); return null }
                return got.takeIf { it.length() > 0 }
            } finally {
                reply = null
            }
        }
    }
}
