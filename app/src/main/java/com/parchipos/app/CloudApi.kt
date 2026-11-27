package com.parchipos.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Parchi Cloud's HTTP API, as the phone uses it: pair once, then sync.
 * Blocking calls; the sync engine runs them on its own thread.
 */
object CloudApi {

    /** The network, behind an interface so tests can play the server. */
    interface Transport {
        fun request(method: String, url: String, bearer: String?, body: String?): Pair<Int, String>
    }

    object Http : Transport {
        override fun request(method: String, url: String, bearer: String?, body: String?): Pair<Int, String> {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 10_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/json")
                if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            try {
                if (body != null) conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                return code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
            } finally {
                conn.disconnect()
            }
        }
    }

    @Volatile var transport: Transport = Http

    /** What went wrong, in words the counter can act on. [offline] means try again later. */
    class ApiError(val status: Int, val code: String, message: String) : Exception(message) {
        val offline: Boolean get() = status == 0 || status >= 500 || status == 429
    }

    private fun call(method: String, url: String, bearer: String?, body: JSONObject?): JSONObject {
        val (status, text) = try {
            transport.request(method, url, bearer, body?.toString())
        } catch (e: Exception) {
            throw ApiError(0, "offline", "No internet, or Parchi Cloud can't be reached (${e.javaClass.simpleName})")
        }
        val json = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
        if (status !in 200..299) {
            throw ApiError(status, json.optString("error", "http_$status"), json.optString("message").ifBlank { json.optString("error").ifBlank { "Parchi Cloud answered $status" } })
        }
        return json
    }

    data class Paired(
        val deviceKey: String, val deviceId: String, val letter: String,
        val accountId: String, val accountName: String, val outletId: String, val outletName: String, val serverTime: Long,
    )

    /** Redeems a pairing code. Errors carry the server's own words ("This code was already used"). */
    fun pair(server: String, code: String, deviceName: String, letter: String?, appVersion: String): Paired {
        val o = call("POST", server.trimEnd('/') + "/api/pair", null, JSONObject()
            .put("code", code).put("deviceName", deviceName).put("appVersion", appVersion).apply { if (!letter.isNullOrBlank()) put("letter", letter) })
        return Paired(
            o.getString("deviceKey"), o.getString("deviceId"), o.getString("letter"),
            o.getJSONObject("account").getString("id"), o.getJSONObject("account").optString("name"),
            o.getJSONObject("outlet").getString("id"), o.getJSONObject("outlet").optString("name"),
            o.optLong("serverTime"),
        )
    }

    /** One round trip of sync messages; the reply's messages, in order. */
    fun sync(server: String, key: String, messages: JSONArray): JSONArray =
        call("POST", server.trimEnd('/') + "/api/sync", key, JSONObject().put("protocol", Protocol.VERSION).put("messages", messages))
            .optJSONArray("messages") ?: JSONArray()

    /** One older day's bills, for a day before this phone's download window. */
    fun day(server: String, key: String, day: String): JSONArray =
        call("GET", server.trimEnd('/') + "/api/sync/day?day=" + URLEncoder.encode(day, "UTF-8"), key, null)
            .optJSONArray("messages") ?: JSONArray()
}
