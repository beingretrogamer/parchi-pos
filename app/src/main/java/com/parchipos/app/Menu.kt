package com.parchipos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/** One size or flavour of an item: "Half", "Cone", "Mint". */
data class Variant(val label: String, val price: Double, val id: String = "")

data class MenuItem(
    val id: String,
    val name: String,
    /** Rupees, GST included by default — the price on the board is the price charged. */
    val price: Double,
    val category: String,
    /**
     * Empty for a single-price item. Otherwise tapping the tile asks which
     * one, so Half/Full momos are one tile rather than two near-identical ones.
     */
    val variants: List<Variant> = emptyList(),
    /** Order within its category. */
    val sort: Double = 0.0,
    val categoryId: String? = null,
    /** GST percent rung up with it: its own rate, else its category's. Read-only here; set in the portal. */
    val tax: Double? = null,
    /** Greyed out on every device of the outlet until it is back (Parchi Cloud). */
    val soldOut: Boolean = false,
)

/**
 * The menu: category and menu item records in parchi.db, the same records
 * Parchi Cloud syncs. Items are listed category by category, each category in
 * its own order.
 *
 * Saving keeps whatever this version doesn't edit (a tax rate set in the
 * portal, for one), so the phone never erases it.
 */
object Menu {

    fun all(c: Context): List<MenuItem> {
        val cats = Store.records(c, "category").associateBy { it.id }
        val outlet = Cloud.me(c)?.outletId
        val soldOut = if (outlet == null) emptySet() else Store.records(c, "itemStatus")
            .filter { it.outletId == outlet && it.data.optBoolean("soldOut") }
            .mapTo(HashSet()) { it.data.optString("itemId") }
        return Store.records(c, "menuItem")
            .map { fromRecord(it, cats).let { m -> if (m.id in soldOut) m.copy(soldOut = true) else m } }
            .sortedWith(compareBy<MenuItem>({ cats[it.categoryId]?.data?.optDouble("sort")?.takeUnless(Double::isNaN) ?: Double.MAX_VALUE }, { it.sort }, { it.name.lowercase(Locale.ROOT) }))
    }

    /** Categories that have items, in order. */
    fun categories(c: Context): List<String> =
        all(c).map { it.category }.filter { it.isNotBlank() }.distinct()

    fun upsert(c: Context, item: MenuItem) {
        Store.tx(c) { save(c, item) }
    }

    fun addAll(c: Context, new: List<MenuItem>) {
        Store.tx(c) { new.forEach { save(c, it) } }
    }

    /** Archived, not erased: the record stays (deleted), so the deletion reaches every phone. */
    fun delete(c: Context, id: String) {
        Store.tx(c) { remove(c, id) }
    }

    /** Swaps in the menu from the boards, for a fresh start. */
    fun resetToDefault(c: Context) = replaceAll(c, parseLines(DEFAULT_MENU).items)

    /**
     * The whole menu becomes [items], in their order, under their categories
     * in the order they first appear. Every current item and category is
     * archived first. Past bills are not affected: their lines are copies.
     */
    fun replaceAll(c: Context, items: List<MenuItem>) {
        Store.tx(c) {
            all(c).forEach { remove(c, it.id) }
            Store.records(c, "category").forEach { put(c, it.copy(deleted = true, updatedAt = maxOf(System.currentTimeMillis(), it.updatedAt + 1), dirty = true)) }
            items.forEach { save(c, it.copy(id = it.id.ifBlank { newId() })) }
        }
    }

    /** Moves one item up or down within its category, so the counter can put the bestsellers first. */
    fun move(c: Context, id: String, delta: Int) {
        Store.tx(c) {
            val items = all(c)
            val item = items.find { it.id == id } ?: return@tx
            val siblings = items.filter { it.categoryId == item.categoryId }.toMutableList()
            val from = siblings.indexOf(item)
            val to = from + delta
            if (to !in siblings.indices) return@tx
            siblings.add(to, siblings.removeAt(from))
            val now = System.currentTimeMillis()
            siblings.forEachIndexed { i, s ->
                val sort = (i + 1).toDouble()
                if (s.sort == sort) return@forEachIndexed
                val rec = Store.record(c, "menuItem", s.id) ?: return@forEachIndexed
                put(c, rec.copy(data = JSONObject(rec.data.toString()).put("sort", sort), updatedAt = maxOf(now, rec.updatedAt + 1), dirty = true))
            }
        }
    }

    /** Whether an item can move one place that way within its category. */
    fun canMove(items: List<MenuItem>, item: MenuItem, delta: Int): Boolean {
        val siblings = items.filter { it.categoryId == item.categoryId }
        return siblings.indexOf(item) + delta in siblings.indices
    }

    /**
     * Sold out, or back, on every device of this outlet. Signed by whoever
     * approved it, when the till rules want a manager for it.
     */
    fun setSoldOut(c: Context, itemId: String, soldOut: Boolean, approval: Manager.Approval?) {
        val outlet = Cloud.me(c)?.outletId ?: return
        Store.tx(c) {
            val id = "$outlet:$itemId"
            val old = Store.record(c, "itemStatus", id)
            val rec = Store.Rec(
                kind = "itemStatus",
                id = id,
                data = JSONObject().put("itemId", itemId).put("soldOut", soldOut),
                outletId = outlet,
                updatedAt = maxOf(Cloud.now(c), (old?.updatedAt ?: 0) + 1),
                seq = old?.seq ?: 0,
                dirty = true,
            )
            Store.saveRecord(c, Manager.signedBy(c, rec, approval))
        }
    }

    fun newId(): String = UUID.randomUUID().toString()

    /** Connected, the menu is the account's: an edit goes up signed by the owner or manager who unlocked. */
    private fun put(c: Context, rec: Store.Rec) = Store.saveRecord(c, Manager.signed(c, rec))

    /** Category names match ignoring case and extra spaces. */
    fun catKey(name: String): String = name.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    private fun save(c: Context, item: MenuItem) {
        val existing = Store.record(c, "menuItem", item.id)
        val catId = item.category.trim().takeIf { it.isNotEmpty() }?.let { categoryId(c, it) }
        val sameCategory = existing != null && !existing.deleted && existing.data.optStringOrNull("categoryId") == catId
        val sort = if (sameCategory) existing!!.data.optDouble("sort", 0.0)
            else (all(c).filter { it.categoryId == catId && it.id != item.id }.maxOfOrNull { it.sort } ?: 0.0) + 1
        // Keep each size's id where the label is the same, so cloud records don't churn.
        val oldVariants = existing?.data?.optJSONArray("variants")
        val variants = item.variants.map { v ->
            val match = (0 until (oldVariants?.length() ?: 0)).map { oldVariants!!.getJSONObject(it) }
                .find { it.optString("label").equals(v.label, ignoreCase = true) }
            v.copy(id = v.id.ifEmpty { match?.optString("id") ?: Store.randomId("v") })
        }
        val now = System.currentTimeMillis()
        put(c, Store.Rec(
            kind = "menuItem",
            id = item.id,
            data = itemData(item.copy(sort = sort, variants = variants), catId, existing?.data),
            updatedAt = maxOf(now, (existing?.updatedAt ?: 0) + 1),
            dirty = true,
        ))
    }

    private fun remove(c: Context, id: String) {
        val rec = Store.record(c, "menuItem", id) ?: return
        put(c, rec.copy(deleted = true, updatedAt = maxOf(System.currentTimeMillis(), rec.updatedAt + 1), dirty = true))
    }

    /** The category with this name, created if there is none. Its id comes from the name, so two phones make the same one. */
    private fun categoryId(c: Context, name: String): String {
        val key = catKey(name)
        Store.records(c, "category").find { catKey(it.data.optString("name")) == key }?.let { return it.id }
        val id = Store.stableId("cat", key)
        val sort = (Store.records(c, "category").maxOfOrNull { it.data.optDouble("sort", 0.0) } ?: 0.0) + 1
        val old = Store.record(c, "category", id)
        put(c, Store.Rec(
            kind = "category",
            id = id,
            data = (old?.data?.let { JSONObject(it.toString()) } ?: JSONObject()).put("name", name).put("sort", sort),
            updatedAt = maxOf(System.currentTimeMillis(), (old?.updatedAt ?: 0) + 1),
            dirty = true,
        ))
        return id
    }

    private fun fromRecord(r: Store.Rec, cats: Map<String, Store.Rec>): MenuItem {
        val d = r.data
        val catId = d.optStringOrNull("categoryId")
        val cat = cats[catId]
        val va = d.optJSONArray("variants") ?: JSONArray()
        val own = d.optDouble("taxRate").takeUnless(Double::isNaN)
        return MenuItem(
            id = r.id,
            name = d.optString("name"),
            price = Protocol.rupees(d.optLong("price")),
            category = cat?.data?.optString("name") ?: "",
            variants = (0 until va.length()).map { va.getJSONObject(it).let { v -> Variant(v.optString("label"), Protocol.rupees(v.optLong("price")), v.optString("id")) } },
            sort = d.optDouble("sort", 0.0),
            categoryId = if (cat != null) catId else null,
            tax = own ?: cat?.data?.optDouble("taxRate")?.takeUnless(Double::isNaN),
        )
    }

    /** The menu item record, written over [base] so fields the phone doesn't edit (taxRate) survive. */
    internal fun itemData(item: MenuItem, categoryId: String?, base: JSONObject?): JSONObject {
        val o = if (base != null) JSONObject(base.toString()) else JSONObject()
        o.put("name", item.name)
        o.put("price", Protocol.paise(item.variants.firstOrNull()?.price ?: item.price))
        o.putOrRemove("categoryId", categoryId)
        o.put("variants", JSONArray().apply {
            item.variants.forEach { put(JSONObject().put("id", it.id.ifEmpty { Store.randomId("v") }).put("label", it.label).put("price", Protocol.paise(it.price))) }
        })
        o.put("sort", item.sort)
        return o
    }

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

    /** TUB's × Scoops & Sips, from the boards at the counter. */
    internal val DEFAULT_MENU = """
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

/**
 * Menus kept as files on the device: the one it had before the SQLite store
 * (menu.json.imported) and one saved each time it connects to an account,
 * whose menu then replaces it. A device of its own can restore any of them.
 */
object MenuBackups {
    data class Saved(val file: java.io.File, val title: String, val savedAt: Long, val items: List<MenuItem>)

    fun save(c: Context, items: List<MenuItem>, account: String) {
        val now = System.currentTimeMillis()
        val list = JSONArray()
        items.forEach { m ->
            list.put(JSONObject().put("id", m.id).put("name", m.name).put("price", m.price).put("category", m.category)
                .put("variants", JSONArray().apply { m.variants.forEach { put(JSONObject().put("label", it.label).put("price", it.price)) } }))
        }
        var stamp = now
        while (java.io.File(c.filesDir, "menu.backup-$stamp.json").exists()) stamp++ // two connects in one millisecond keep both
        java.io.File(c.filesDir, "menu.backup-$stamp.json").writeText(JSONObject().put("savedAt", now).put("account", account).put("items", list).toString())
    }

    /** Newest first; empty or unreadable files are left out. */
    fun list(c: Context): List<Saved> {
        val files = c.filesDir.listFiles { f -> f.name == "menu.json.imported" || (f.name.startsWith("menu.backup") && f.name.endsWith(".json")) } ?: return emptyList()
        return files.mapNotNull { read(c, it) }.filter { it.items.isNotEmpty() }.sortedByDescending { it.savedAt }
    }

    private fun read(c: Context, f: java.io.File): Saved? = try {
        val text = f.readText().trim()
        // menu.json and the first backups are a bare list; later backups say when and for which account.
        val wrapped = if (text.startsWith("{")) JSONObject(text) else null
        val arr = wrapped?.getJSONArray("items") ?: JSONArray(text)
        val items = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val va = o.optJSONArray("variants") ?: JSONArray()
            MenuItem(
                o.optString("id", "").ifBlank { Menu.newId() }, o.getString("name"), o.optDouble("price", 0.0).takeUnless(Double::isNaN) ?: 0.0,
                o.optString("category", ""),
                (0 until va.length()).map { j -> va.getJSONObject(j).let { v -> Variant(v.getString("label"), v.getDouble("price")) } },
            )
        }
        val account = wrapped?.optString("account")?.ifBlank { null }
        val title = when {
            f.name == "menu.json.imported" -> c.getString(R.string.backup_before_cloud)
            account != null -> c.getString(R.string.backup_connecting_to, account)
            else -> c.getString(R.string.backup_connecting)
        }
        Saved(f, title, wrapped?.optLong("savedAt", f.lastModified()) ?: f.lastModified(), items)
    } catch (_: Exception) {
        null
    }
}
