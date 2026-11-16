package com.parchipos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

/** One size or flavour of an item: "Half", "Cone", "Mint". */
data class Variant(val label: String, val price: Double)

data class MenuItem(
    val id: String,
    val name: String,
    /** Rupees, GST included — the price on the board is the price charged. */
    val price: Double,
    val category: String,
    /**
     * Empty for a single-price item. Otherwise tapping the tile asks which
     * one, so Half/Full momos are one tile rather than two near-identical ones.
     */
    val variants: List<Variant> = emptyList(),
)

/**
 * The menu, kept as one small JSON file.
 *
 * org.json ships with Android, so this costs no dependency, and a menu of a
 * hundred items is rewritten whole on every change without anyone noticing.
 */
object Menu {
    private const val FILE = "menu.json"

    @Synchronized
    fun all(c: Context): List<MenuItem> {
        val f = File(c.filesDir, FILE)
        // First launch only: an existing file — even an emptied one — is the
        // shop's own menu and is never replaced.
        if (!f.exists()) {
            val seed = parseLines(DEFAULT_MENU).items
            write(c, seed)
            return seed
        }
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val va = o.optJSONArray("variants") ?: JSONArray()
                MenuItem(
                    o.getString("id"),
                    o.getString("name"),
                    o.getDouble("price"),
                    o.optString("category", ""),
                    (0 until va.length()).map { j ->
                        Variant(va.getJSONObject(j).getString("label"), va.getJSONObject(j).getDouble("price"))
                    },
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun categories(c: Context): List<String> =
        all(c).map { it.category }.filter { it.isNotBlank() }.distinct()

    @Synchronized
    fun upsert(c: Context, item: MenuItem) {
        val items = all(c).toMutableList()
        val i = items.indexOfFirst { it.id == item.id }
        if (i >= 0) items[i] = item else items.add(item)
        write(c, items)
    }

    @Synchronized
    fun addAll(c: Context, new: List<MenuItem>) = write(c, all(c) + new)

    @Synchronized
    fun delete(c: Context, id: String) = write(c, all(c).filterNot { it.id == id })

    /** Swaps in the menu from the boards, for a fresh start. */
    @Synchronized
    fun resetToDefault(c: Context) = write(c, parseLines(DEFAULT_MENU).items)

    /** Moves one item up or down, so the counter can put the bestsellers first. */
    @Synchronized
    fun move(c: Context, id: String, delta: Int) {
        val items = all(c).toMutableList()
        val from = items.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in items.indices) return
        items.add(to, items.removeAt(from))
        write(c, items)
    }

    fun newId(): String = UUID.randomUUID().toString()

    // ── Text format ───────────────────────────────────────────────────
    //
    // One item per line, comma or tab separated: name, price, category.
    // The price is either a number or sizes separated by "/":
    //
    //   French Fries, 60, Sides
    //   Veg Steam Momos, Half 40 / Full 80, Momos
    //
    // Used by "Paste a list", by the price field when editing, and for the
    // built-in menu below, so all three accept exactly the same thing.

    /** "60" → no variants; "Half 40 / Full 80" → two. Null if unreadable. */
    fun parsePrice(raw: String): Pair<Double, List<Variant>>? {
        val s = raw.trim()
        if (!s.contains('/')) {
            val p = number(s) ?: return null
            return p to emptyList()
        }
        val variants = s.split('/').map { part ->
            val bits = part.trim().split(Regex("\\s+"))
            val price = number(bits.last()) ?: return null
            val label = bits.dropLast(1).joinToString(" ")
            if (label.isEmpty()) return null
            Variant(label, price)
        }
        return variants.first().price to variants
    }

    fun formatPrice(item: MenuItem): String =
        if (item.variants.isEmpty()) plain(item.price)
        else item.variants.joinToString(" / ") { "${it.label} ${plain(it.price)}" }

    class Parsed(val items: List<MenuItem>, val skipped: Int)

    fun parseLines(text: String): Parsed {
        val items = ArrayList<MenuItem>()
        var skipped = 0
        for (raw in text.lines()) {
            if (raw.isBlank()) continue
            val parts = raw.split(',', '\t').map { it.trim() }
            val price = parts.getOrNull(1)?.let { parsePrice(it) }
            if (parts[0].isEmpty() || price == null) { skipped++; continue }
            items.add(MenuItem(newId(), parts[0], price.first, parts.getOrNull(2) ?: "", price.second))
        }
        return Parsed(items, skipped)
    }

    private fun number(s: String): Double? =
        s.removePrefix("₹").removePrefix("Rs.").removePrefix("Rs").trim().toDoubleOrNull()?.takeIf { it >= 0 }

    private fun plain(n: Double): String =
        if (n == Math.floor(n)) n.toLong().toString() else String.format(Locale.US, "%.2f", n)

    private fun write(c: Context, items: List<MenuItem>) {
        val arr = JSONArray()
        items.forEach { m ->
            val va = JSONArray()
            m.variants.forEach { va.put(JSONObject().put("label", it.label).put("price", it.price)) }
            arr.put(JSONObject().put("id", m.id).put("name", m.name).put("price", m.price)
                .put("category", m.category).put("variants", va))
        }
        // Write-then-rename, so a crash mid-save cannot leave half a menu.
        val tmp = File(c.filesDir, "$FILE.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(File(c.filesDir, FILE))
    }

    /** TUB's × Scoops & Sips, from the boards at the counter. */
    private val DEFAULT_MENU = """
        Vanilla, Cup 40 / Cone 50 / Double 70, Scoops
        Strawberry, Cup 40 / Cone 50 / Double 70, Scoops
        Butterscotch, Cup 40 / Cone 50 / Double 70, Scoops
        Chocolate, Cup 40 / Cone 50 / Double 70, Scoops
        Kesar Pista, Cup 50 / Cone 60 / Double 90, Scoops
        Paan, Cup 50 / Cone 60 / Double 90, Scoops
        Mango, Cup 50 / Cone 60 / Double 90, Scoops
        Fruit n Nut, Cup 50 / Cone 60 / Double 90, Scoops
        Kunafa Waffle, Mini 139 / 2 Pc 259 / 4 Pc 480, Waffles
        Biscoff Waffle, Mini 129 / 2 Pc 239 / 4 Pc 440, Waffles
        Triple Chocolate Waffle, Mini 129 / 2 Pc 229 / 4 Pc 440, Waffles
        Dark Chocolate Waffle, Mini 99 / 2 Pc 179 / 4 Pc 320, Waffles
        Hershey's Chocolate Waffle, Mini 99 / 2 Pc 179 / 4 Pc 320, Waffles
        Nutella Waffle, Mini 99 / 2 Pc 179 / 4 Pc 320, Waffles
        Oreo Waffle, Mini 99 / 2 Pc 179 / 4 Pc 320, Waffles
        KitKat Waffle, Mini 99 / 2 Pc 179 / 4 Pc 320, Waffles
        Crunchy Dark Chocolate Waffle, Mini 129 / 2 Pc 229 / 4 Pc 440, Waffles
        Toffee Caramel Waffle, Mini 129 / 2 Pc 229 / 4 Pc 440, Waffles
        Coffee Spread Waffle, Mini 99 / 2 Pc 179 / 4 Pc 320, Waffles
        Crunchy Cotton Candy Waffle, Mini 99 / 2 Pc 179 / 4 Pc 320, Waffles
        Crunchy Bubblegum Waffle, Mini 129 / 2 Pc 229 / 4 Pc 440, Waffles
        Nutella Coconut Waffle, Mini 99 / 2 Pc 179 / 4 Pc 320, Waffles
        Blueberry Waffle, Mini 99 / 2 Pc 179 / 4 Pc 320, Waffles
        Pineapple Waffle, Mini 99 / 2 Pc 179 / 4 Pc 320, Waffles
        Pineapple Coconut Waffle, Mini 99 / 2 Pc 179 / 4 Pc 320, Waffles
        Kesar Badam Kulfi Shake, 90, Shakes
        Rajwadi Kulfi Shake, 90, Shakes
        Rose Kulfi Shake, 100, Shakes
        Kesar Pista Kulfi Shake, 100, Shakes
        Paan Shake, 90, Shakes
        KitKat Shake, 100, Shakes
        Oreo Shake, 110, Shakes
        Lotus Biscoff Shake, 130, Shakes
        Strawberry Shake, 90, Shakes
        Chocolate Shake, 90, Shakes
        Pista Shake, 90, Shakes
        American Nuts Shake, 90, Shakes
        Tutti Frutty Shake, 90, Shakes
        Butterscotch Shake, 90, Shakes
        Nestle Classic Cold Coffee, Classic 80 / Loaded 90, Cold Coffee
        Hazelnut Cold Coffee, Classic 90 / Loaded 100, Cold Coffee
        Davidoff Cold Coffee, Classic 130 / Loaded 140, Cold Coffee
        Nescafe Gold Cold Coffee, Classic 130 / Loaded 140, Cold Coffee
        Mojito, Mint 60 / Watermelon 60 / Lychee 60 / Green Apple 60 / Strawberry 60 / Blue Lagoon 60 / Orange 60 / Pineapple 60 / Kala Khatta 60 / Blue Curacao 60, Mojito
        Veg Steam Momos, Half 40 / Full 80, Momos
        Paneer Steam Momos, Half 50 / Full 100, Momos
        Beetroot Momos, Half 60 / Full 120, Momos
        Kurkure Veg Momos, Half 70 / Full 140, Momos
        Kurkure Paneer Momos, Half 80 / Full 150, Momos
        Tub Aloo Tikki Burger, 50, Burgers
        Tub Cheese & Corn Burger, 60, Burgers
        Tub Makhani Paneer Burger, 70, Burgers
        Tub Cheese Overloaded Burger, 80, Burgers
        Tub Aloo Tikki Combo, 140, Combos
        Tub Cheese & Corn Combo, 145, Combos
        Tub Makhani Paneer Combo, 150, Combos
        Tub Cheese Overloaded Combo, 160, Combos
        Veg Noodles, Half 60 / Full 110, Noodles
        Hakka Noodles, Half 80 / Full 150, Noodles
        Chilly Garlic Noodles, Half 70 / Full 130, Noodles
        White Sauce Pasta, Half 65 / Full 110, Pasta
        Mix Sauce Pasta, Half 65 / Full 110, Pasta
        Mac & Cheese, Half 100 / Full 170, Pasta
        Veg Maggi, 50, Maggi
        Cheese and Butter Maggi, 70, Maggi
        Butter Masala Sweet Corn, 45, Sweet Corn
        Chili Garlic Sweet Corn, 55, Sweet Corn
        Cheese & Corn Balls, Half 90 / Full 180, Cheese Balls
        Cheese & Veggies Balls, Half 90 / Full 180, Cheese Balls
        Crispy Paneer Roll, 100, Rolls
        French Fries, 60, Sides
        Water Bottle Small, 10, Drinks
        Cold Drink Can, 70, Drinks
    """.trimIndent()
}
