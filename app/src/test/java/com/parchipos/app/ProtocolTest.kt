package com.parchipos.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shared fixtures from parchi-cloud/protocol/fixtures: the server passes
 * the same cases, so a phone and the server always agree on a merge, a total
 * and an invoice number.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProtocolTest {

    private fun fixture(name: String) = JSONObject(javaClass.classLoader!!.getResourceAsStream("protocol/$name")!!.bufferedReader().readText())

    private fun version(o: JSONObject) = Protocol.Version(
        updatedAt = o.getLong("updatedAt"),
        fieldTimes = o.optJSONObject("fieldTimes")?.let { ft -> ft.keys().asSequence().associateWith { ft.getLong(it) } },
        byDevice = o.optStringOrNull("byDevice"),
        deleted = o.optBoolean("deleted", false),
        data = o.getJSONObject("data"),
    )

    private fun assertSame(name: String, expected: JSONObject, got: Protocol.Version) {
        assertEquals("$name: updatedAt", expected.getLong("updatedAt"), got.updatedAt)
        val ft = expected.optJSONObject("fieldTimes")
        assertEquals("$name: fieldTimes", ft?.let { o -> o.keys().asSequence().associateWith { o.getLong(it) } }, got.fieldTimes)
        assertEquals("$name: data", Protocol.canonical(expected.getJSONObject("data")), Protocol.canonical(got.data))
    }

    @Test fun mergeFixtures() {
        val cases = fixture("merge.json").getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val kind = c.getString("kind")
            val a = version(c.getJSONObject("a"))
            val b = version(c.getJSONObject("b"))
            val expected = c.getJSONObject("expected")
            val ab = Protocol.merge(kind, a, b)
            assertSame("$name (a,b)", expected, ab)
            assertSame("$name (b,a)", expected, Protocol.merge(kind, b, a))
            // Idempotent: merging the result with either side changes nothing.
            assertSame("$name (m,a)", expected, Protocol.merge(kind, ab, a))
            assertSame("$name (m,b)", expected, Protocol.merge(kind, ab, b))
        }
    }

    @Test fun totalsFixtures() {
        val cases = fixture("totals.json").getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val arr = c.getJSONArray("lines")
            val lines = (0 until arr.length()).map { arr.getJSONObject(it).let { l -> Protocol.Line(l.getLong("price"), l.getInt("qty"), l.optDouble("taxRate", 0.0)) } }
            val t = Protocol.billTotals(lines, c.getLong("discount"), c.getBoolean("pricesIncludeTax"), c.optDouble("serviceChargePct", 0.0))
            val e = c.getJSONObject("expected")
            val name = c.getString("name")
            assertEquals(name, e.getLong("subtotal"), t.subtotal)
            assertEquals(name, e.getLong("discount"), t.discount)
            assertEquals(name, e.getLong("tax"), t.tax)
            assertEquals(name, e.getLong("total"), t.total)
            assertEquals(name, e.getLong("serviceCharge"), t.serviceCharge)
            assertEquals(name, e.getLong("serviceChargeTax"), t.serviceChargeTax)
            val el = e.getJSONArray("lines")
            assertEquals(name, (0 until el.length()).map { el.getJSONObject(it).let { l -> Protocol.LineFigures(l.getLong("net"), l.getLong("tax")) } }, t.lines)
        }
    }

    @Test fun invoiceFixtures() {
        val f = fixture("invoice.json")
        val fy = f.getJSONArray("fy")
        for (i in 0 until fy.length()) fy.getJSONObject(i).let { assertEquals(it.getString("day"), it.getString("fy"), Protocol.fyOf(it.getString("day"))) }
        val fmt = f.getJSONArray("format")
        for (i in 0 until fmt.length()) {
            val o = fmt.getJSONObject(i)
            val prefix = o.optStringOrNull("prefix")
            assertEquals(o.getString("invoice"), Protocol.formatInvoice(o.getString("letter"), o.getString("fy"), o.getInt("serial"), prefix))
            assertEquals(Protocol.Invoice(prefix, o.getString("letter"), o.getString("fy"), o.getInt("serial")), Protocol.parseInvoice(o.getString("invoice")))
        }
        val bad = f.getJSONArray("invalid")
        for (i in 0 until bad.length()) assertNull(bad.getString(i), Protocol.parseInvoice(bad.getString(i)))
    }

    @Test fun canonicalJsonIsWhatJavaScriptWrites() {
        val o = JSONObject("""{"b":1,"a":[1.5,"x\"y",null,true,4550.0],"c":{"z":"é</\n","y":-0.25}}""")
        // JSON.stringify keeps "</" and "é" as they are, writes 4550.0 as 4550, and escapes the newline.
        assertEquals("""{"a":[1.5,"x\"y",null,true,4550],"b":1,"c":{"y":-0.25,"z":"é</\n"}}""", Protocol.canonical(o))
        assertEquals("[]", Protocol.canonical(JSONArray()))
        assertEquals(""""\u001f"""", Protocol.canonical("\u001f"))
    }

    @Test fun paiseRoundTripsWhatPeopleType() {
        for (r in listOf(0.0, 0.01, 19.99, 45.5, 99.95, 1234.56, 100000.0)) {
            assertEquals(r, Protocol.rupees(Protocol.paise(r)), 0.0)
        }
        assertEquals(1999L, Protocol.paise(19.99))
        assertEquals(4550L, Protocol.paise(45.5))
    }

    @Test fun versions() {
        assertEquals(-1, Protocol.compareVersions("0.6.2", "0.7.0"))
        assertEquals(0, Protocol.compareVersions("0.7", "0.7.0"))
        assertEquals(1, Protocol.compareVersions("1.0.0", "0.9.9"))
    }
}
