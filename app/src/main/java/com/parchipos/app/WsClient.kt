package com.parchipos.app

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/**
 * The WebSocket frame format (RFC 6455), as pure functions so it can be tested
 * without a network: what a client sends (always masked), what it reads.
 */
object WsCodec {
    const val TEXT = 0x1
    const val CLOSE = 0x8
    const val PING = 0x9
    const val PONG = 0xA
    private const val CONTINUATION = 0x0
    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    /** What the server must answer to our Sec-WebSocket-Key. */
    fun acceptFor(key: String): String =
        Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII)), Base64.NO_WRAP)

    /** One whole frame from a client: FIN set, masked with [mask] (4 bytes). */
    fun encode(opcode: Int, payload: ByteArray, mask: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 14)
        out.write(0x80 or opcode)
        val n = payload.size
        when {
            n < 126 -> out.write(0x80 or n)
            n <= 0xFFFF -> { out.write(0x80 or 126); out.write(n ushr 8); out.write(n and 0xFF) }
            else -> { out.write(0x80 or 127); for (i in 7 downTo 0) out.write(((n.toLong() ushr (8 * i)) and 0xFF).toInt()) }
        }
        out.write(mask, 0, 4)
        for (i in 0 until n) out.write(payload[i].toInt() xor mask[i and 3].toInt())
        return out.toByteArray()
    }

    /** A complete message: a text (joined from its fragments) or a control frame. */
    data class Message(val opcode: Int, val payload: ByteArray) {
        val text: String get() = String(payload, Charsets.UTF_8)
    }

    /**
     * Reads messages off [input]: fragments of a text are joined, control frames come as
     * they are (they may arrive between fragments). Server frames are unmasked, but a
     * masked one is unmasked too. Returns null at the end of the stream.
     */
    class Reader(private val input: InputStream, private val maxBytes: Int = 16 shl 20) {
        private var partial: ByteArrayOutputStream? = null
        private var partialOpcode = 0

        fun next(): Message? {
            while (true) {
                val b0 = input.read()
                if (b0 < 0) return null
                val b1 = readByte()
                val fin = b0 and 0x80 != 0
                val opcode = b0 and 0x0F
                val masked = b1 and 0x80 != 0
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) len = ((readByte() shl 8) or readByte()).toLong()
                else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or readByte().toLong() } }
                if (len < 0 || len > maxBytes) throw java.io.IOException("frame too big ($len bytes)")
                val mask = if (masked) ByteArray(4).also { readFully(it) } else null
                val payload = ByteArray(len.toInt()).also { readFully(it) }
                if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
                when {
                    opcode >= 0x8 -> return Message(opcode, payload)
                    opcode == CONTINUATION -> {
                        val p = partial ?: throw java.io.IOException("continuation without a start")
                        p.write(payload)
                        if (p.size() > maxBytes) throw java.io.IOException("message too big")
                        if (fin) { partial = null; return Message(partialOpcode, p.toByteArray()) }
                    }
                    fin -> return Message(opcode, payload)
                    else -> { partial = ByteArrayOutputStream().apply { write(payload) }; partialOpcode = opcode }
                }
            }
        }

        private fun readByte(): Int {
            val b = input.read()
            if (b < 0) throw java.io.EOFException()
            return b
        }

        private fun readFully(buf: ByteArray) {
            var off = 0
            while (off < buf.size) {
                val n = input.read(buf, off, buf.size - off)
                if (n < 0) throw java.io.EOFException()
                off += n
            }
        }
    }
}

/**
 * A minimal WebSocket client for Parchi Cloud's live socket, over TLS (https
 * servers) or plain TCP (http, for tests). One reader thread delivers messages
 * to [listener]; [send] may be called from any thread.
 */
class WsClient(private val url: String, private val bearer: String, private val listener: Listener) {

    interface Listener {
        fun onOpen()
        fun onText(text: String)
        /** The socket ended: closed by either side, failed to open ([status] is the HTTP status then, else 0), or broke. */
        fun onClosed(code: Int, reason: String, status: Int)
    }

    @Volatile private var socket: Socket? = null
    private var out: OutputStream? = null
    private val writeLock = Any()
    private val random = SecureRandom()
    @Volatile var lastFrameAt = 0L
        private set

    /** Opens on a background thread; [Listener.onOpen] or [Listener.onClosed] follows. */
    fun connect(timeoutMs: Int = 15_000) {
        thread(name = "parchi-live") {
            var status = 0
            try {
                val uri = URI(url)
                val tls = uri.scheme == "https" || uri.scheme == "wss"
                val host = uri.host
                val port = if (uri.port > 0) uri.port else if (tls) 443 else 80
                val raw = Socket().apply { connect(InetSocketAddress(host, port), timeoutMs); soTimeout = timeoutMs; tcpNoDelay = true }
                val s: Socket = if (tls) {
                    // The factory sends the host name (SNI); the name is then checked against the certificate.
                    val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true) as SSLSocket
                    ssl.startHandshake()
                    if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) throw java.io.IOException("certificate is not for $host")
                    ssl
                } else raw
                socket = s
                val key = Base64.encodeToString(ByteArray(16).also(random::nextBytes), Base64.NO_WRAP)
                val path = (uri.rawPath ?: "/").ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
                val o = s.getOutputStream()
                o.write((
                    "GET $path HTTP/1.1\r\nHost: $host\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\nAuthorization: Bearer $bearer\r\nUser-Agent: ParchiPOS\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII))
                o.flush()
                val input = java.io.BufferedInputStream(s.getInputStream())
                val head = readHead(input)
                status = head.firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: 0
                if (status != 101) throw java.io.IOException("server answered $status")
                val accept = head.drop(1).firstOrNull { it.startsWith("sec-websocket-accept:", ignoreCase = true) }?.substringAfter(':')?.trim()
                if (accept != WsCodec.acceptFor(key)) throw java.io.IOException("bad Sec-WebSocket-Accept")
                out = o
                s.soTimeout = 0 // the keep-alive notices a dead socket
                lastFrameAt = System.currentTimeMillis()
                listener.onOpen()
                read(WsCodec.Reader(input))
            } catch (e: Exception) {
                close()
                listener.onClosed(0, e.message ?: e.javaClass.simpleName, status)
            }
        }
    }

    private fun read(reader: WsCodec.Reader) {
        while (true) {
            val m = reader.next() ?: break
            lastFrameAt = System.currentTimeMillis()
            when (m.opcode) {
                WsCodec.TEXT -> listener.onText(m.text)
                WsCodec.PING -> sendFrame(WsCodec.PONG, m.payload)
                WsCodec.CLOSE -> {
                    val code = if (m.payload.size >= 2) ((m.payload[0].toInt() and 0xFF) shl 8) or (m.payload[1].toInt() and 0xFF) else 1005
                    val reason = if (m.payload.size > 2) String(m.payload, 2, m.payload.size - 2, Charsets.UTF_8) else ""
                    runCatching { sendFrame(WsCodec.CLOSE, m.payload.copyOf(minOf(2, m.payload.size))) }
                    close()
                    listener.onClosed(code, reason, 101)
                    return
                }
            }
        }
        close()
        listener.onClosed(1006, "connection lost", 101)
    }

    /** Sends a text message; false if the socket isn't open. */
    fun send(text: String): Boolean = try {
        sendFrame(WsCodec.TEXT, text.toByteArray(Charsets.UTF_8))
        true
    } catch (_: Exception) {
        false
    }

    private fun sendFrame(opcode: Int, payload: ByteArray) {
        val o = out ?: throw java.io.IOException("not open")
        val frame = WsCodec.encode(opcode, payload, ByteArray(4).also(random::nextBytes))
        synchronized(writeLock) { o.write(frame); o.flush() }
    }

    fun close() {
        out = null
        runCatching { socket?.close() }
        socket = null
    }

    /** The status line and headers, up to the blank line. */
    private fun readHead(input: InputStream): List<String> {
        val lines = ArrayList<String>()
        val line = StringBuilder()
        var total = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw java.io.EOFException("closed during the handshake")
            if (++total > 16_384) throw java.io.IOException("handshake too long")
            if (b == '\n'.code) {
                val l = line.toString().trimEnd('\r')
                if (l.isEmpty()) return lines
                lines += l
                line.setLength(0)
            } else line.append(b.toChar())
        }
    }
}
