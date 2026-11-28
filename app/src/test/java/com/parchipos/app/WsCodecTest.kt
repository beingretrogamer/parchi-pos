package com.parchipos.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** The WebSocket frame format the live socket relies on (RFC 6455). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WsCodecTest {

    private val mask = byteArrayOf(0x37, 0xfa.toByte(), 0x21, 0x3d)

    /** A server frame: unmasked, possibly a fragment. */
    private fun serverFrame(opcode: Int, payload: ByteArray, fin: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((if (fin) 0x80 else 0) or opcode)
        when {
            payload.size < 126 -> out.write(payload.size)
            payload.size <= 0xFFFF -> { out.write(126); out.write(payload.size shr 8); out.write(payload.size and 0xFF) }
            else -> { out.write(127); for (i in 7 downTo 0) out.write(((payload.size.toLong() shr (8 * i)) and 0xFF).toInt()) }
        }
        out.write(payload)
        return out.toByteArray()
    }

    @Test fun theAcceptKeyIsTheRfcsExample() =
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WsCodec.acceptFor("dGhlIHNhbXBsZSBub25jZQ=="))

    @Test fun aMaskedHelloIsTheRfcsExample() {
        // RFC 6455 §5.7: a masked "Hello" from a client.
        assertArrayEquals(byteArrayOf(0x81.toByte(), 0x85.toByte(), 0x37, 0xfa.toByte(), 0x21, 0x3d, 0x7f, 0x9f.toByte(), 0x4d, 0x51, 0x58),
            WsCodec.encode(WsCodec.TEXT, "Hello".toByteArray(), mask))
    }

    @Test fun everyLengthRoundTrips() {
        for (n in listOf(0, 1, 125, 126, 127, 65_535, 65_536, 200_000)) {
            val text = "x".repeat(n)
            val m = WsCodec.Reader(ByteArrayInputStream(WsCodec.encode(WsCodec.TEXT, text.toByteArray(), mask))).next()!!
            assertEquals("length $n", text, m.text)
        }
    }

    @Test fun fragmentsJoinAndControlFramesSlipBetween() {
        val bytes = ByteArrayOutputStream().apply {
            write(serverFrame(WsCodec.TEXT, "{\"a\":".toByteArray(), fin = false))
            write(serverFrame(WsCodec.PING, "hi".toByteArray()))
            write(serverFrame(0x0, "1}".toByteArray(), fin = true))
            write(serverFrame(WsCodec.CLOSE, byteArrayOf(0x0f, 0xa1.toByte()) + "removed".toByteArray()))
        }.toByteArray()
        val r = WsCodec.Reader(ByteArrayInputStream(bytes))
        val ping = r.next()!!
        assertEquals(WsCodec.PING, ping.opcode)
        assertEquals("hi", ping.text)
        assertEquals("{\"a\":1}", r.next()!!.text)
        val close = r.next()!!
        assertEquals(WsCodec.CLOSE, close.opcode)
        assertEquals(4001, ((close.payload[0].toInt() and 0xFF) shl 8) or (close.payload[1].toInt() and 0xFF))
        assertNull(r.next())
    }
}
