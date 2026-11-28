package com.parchipos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Dine-in (plan §15): tables set up in the portal, running orders on them,
 * and the rounds each order sent to the kitchen.
 *
 * A round never changes once sent; taking an item back is a new round with a
 * negative line. So two devices adding to one table never overwrite each
 * other, and the kitchen's slips always match the record. The bill is made
 * from the rounds at the end, printed, and settled when the guest pays.
 *
 * All of it is records in [Store], synced like the menu; only a connected
 * device has tables.
 */
object Tables {

    data class Table(val id: String, val name: String, val area: String, val seats: Int?, val sort: Double, val active: Boolean)

    /** A line of a round, rupees like the rest of the app's screens. A negative [qty] takes back [cancelOf]. */
    data class Line(
        val name: String,
        val price: Double,
        val qty: Int,
        val note: String = "",
        val itemId: String? = null,
        val variant: String? = null,
        val custom: Boolean = false,
        val taxRate: Double? = null,
        val cancelOf: Pair<String, Int>? = null,
        val reason: String = "",
    ) {
        /** Lines that are the same dish at the same price with the same note add up on the bill. */
        val key: String get() = listOf(itemId ?: "", variant ?: "", name, Protocol.paise(price).toString(), note).joinToString("\u0001")

        fun toSaleLine(qty: Int = this.qty) = SaleLine(name, price, qty, note, itemId = itemId, variant = variant, custom = custom, taxRate = taxRate)
    }

    data class Round(val id: String, val orderId: String, val deviceId: String?, val origin: String, val time: Long, val lines: List<Line>, val synced: Boolean)

    data class Order(
        val id: String,
        val tableId: String,
        val openedAt: Long,
        val deviceId: String?,
        val covers: Int?,
        val note: String,
        val status: String,
        val billIds: List<String>,
        val mergedInto: String?,
        val cancelReason: String,
        val rec: Store.Rec,
    ) {
        val open: Boolean get() = status == OPEN
    }

    /** One line of what a table has had, every round added up and every take-back taken off. */
    data class Net(val line: Line, val qty: Int) {
        val amount: Double get() = line.price * qty
    }

    /** How a table looks on the grid. */
    data class State(
        val table: Table,
        /** Open orders on it (two parties at one table are two), each with every order merged into it. */
        val running: List<Order>,
        /** Rupees sent to the kitchen on them so far. */
        val sent: Double,
        val covers: Int,
        val since: Long?,
        /** Bills printed for it and not paid yet. */
        val toSettle: List<Sale>,
        /** Rounds that reached a billed order after its bill was printed. */
        val notOnBill: Int,
    ) {
        val free: Boolean get() = running.isEmpty() && toSettle.isEmpty() && notOnBill == 0
    }

    const val OPEN = "open"
    const val BILLED = "billed"
    const val CANCELLED = "cancelled"

    private val GROUPS = listOf("place", "status", "info")

    // ── reading ─────────────────────────────────────────────────────────

    fun tables(c: Context, includeOff: Boolean = false): List<Table> {
        val outlet = Cloud.me(c)?.outletId ?: return emptyList()
        return Store.records(c, "table").filter { it.outletId == outlet }.map { r ->
            val d = r.data
            Table(r.id, d.optString("name"), d.optString("area", "").trim(), d.optInt("seats", 0).takeIf { it > 0 }, d.optDouble("sort", 0.0), d.optBoolean("active", true))
        }.filter { includeOff || it.active }.sortedWith(compareBy<Table> { it.sort }.thenBy(NUMERIC) { it.name })
    }

    /** The till offers Tables only to a connected device whose outlet has some in use. */
    fun enabled(c: Context): Boolean = Cloud.connected(c) && tables(c).isNotEmpty()

    fun orders(c: Context): List<Order> {
        val outlet = Cloud.me(c)?.outletId ?: return emptyList()
        return Store.records(c, "order").filter { it.outletId == outlet }.map(::toOrder)
    }

    fun order(c: Context, id: String): Order? = Store.record(c, "order", id)?.takeIf { !it.deleted }?.let(::toOrder)

    private fun toOrder(r: Store.Rec): Order {
        val d = r.data
        val bills = d.optJSONArray("billIds")
        return Order(
            id = r.id, tableId = d.optString("tableId"), openedAt = d.optLong("openedAt"), deviceId = d.optStringOrNull("deviceId"),
            covers = d.optInt("covers", 0).takeIf { it > 0 }, note = d.optString("note", ""), status = d.optString("status", OPEN),
            billIds = (0 until (bills?.length() ?: 0)).map { bills!!.getString(it) },
            mergedInto = d.optStringOrNull("mergedInto"), cancelReason = d.optString("cancelReason", ""), rec = r,
        )
    }

    fun rounds(c: Context, orderIds: Set<String>): List<Round> = Store.records(c, "round").mapNotNull { r ->
        val d = r.data
        val orderId = d.optString("orderId")
        if (orderId !in orderIds) return@mapNotNull null
        val arr = d.optJSONArray("lines") ?: JSONArray()
        Round(r.id, orderId, d.optStringOrNull("deviceId"), d.optString("origin", ""), d.optLong("time"),
            (0 until arr.length()).map { lineFrom(arr.getJSONObject(it)) }, synced = r.seq > 0 && !r.dirty)
    }.sortedWith(compareBy<Round> { it.time }.thenBy { it.id })

    private fun lineFrom(l: JSONObject) = Line(
        name = l.optString("name"),
        price = Protocol.rupees(l.optLong("price")),
        qty = l.optInt("qty"),
        note = l.optString("note", ""),
        itemId = l.optStringOrNull("itemId"),
        variant = l.optStringOrNull("variant"),
        custom = l.optBoolean("custom", false),
        taxRate = if (l.has("taxRate") && !l.isNull("taxRate")) l.getDouble("taxRate") else null,
        cancelOf = l.optJSONObject("cancelOf")?.let { it.optString("roundId") to it.optInt("idx") },
        reason = l.optString("reason", ""),
    )

    /** Follows merges to the order a party's bill comes from (a chain can't loop forever). */
    fun root(all: Map<String, Order>, o: Order): Order {
        var cur = o
        repeat(10) {
            val next = cur.mergedInto?.let(all::get) ?: return cur
            if (next.status == CANCELLED) return cur
            cur = next
        }
        return cur
    }

    /** [root] and every order merged into it, cancelled ones left out. */
    fun group(all: Map<String, Order>, root: Order): List<Order> =
        all.values.filter { it.status != CANCELLED && root(all, it).id == root.id }

    /** Rounds of [orders] not on a live bill yet. */
    fun unbilled(c: Context, orders: List<Order>): List<Round> {
        val ids = orders.map { it.id }.toSet()
        val billed = billsOf(c, ids).filter { !it.voided }.flatMap { it.roundIds }.toSet()
        return rounds(c, ids).filter { it.id !in billed }
    }

    /** Bills made from [orderIds], voided ones too. */
    fun billsOf(c: Context, orderIds: Set<String>): List<Sale> =
        if (orderIds.isEmpty()) emptyList()
        else Store.bills(c, "data LIKE '%\"orderId\"%'").filter { it.orderId in orderIds }

    /**
     * What [rounds] add up to: lines of the same dish at the same price and note combined,
     * take-backs taken off. A dish taken back more often than it was sent (two devices
     * offline) never goes below none; [overCancelled] says it happened.
     */
    fun net(rounds: List<Round>): Pair<List<Net>, Boolean> {
        val qty = LinkedHashMap<String, Int>()
        val first = HashMap<String, Line>()
        for (r in rounds) for (l in r.lines) {
            qty[l.key] = (qty[l.key] ?: 0) + l.qty
            if (l.qty > 0) first.putIfAbsent(l.key, l)
            else first.putIfAbsent(l.key, l.copy(cancelOf = null, reason = ""))
        }
        val over = qty.values.any { it < 0 }
        return qty.mapNotNull { (k, n) -> if (n > 0) Net(first.getValue(k).copy(cancelOf = null, reason = ""), n) else null } to over
    }

    fun states(c: Context): List<State> {
        val all = orders(c).associateBy { it.id }
        val unsettled = Store.bills(c, "payment = '' AND voided = 0").filter { it.orderId != null }
        val dayAgo = Cloud.now(c) - 24 * 3600_000L
        return tables(c, includeOff = true).mapNotNull { t ->
            val roots = all.values.filter { it.open && it.mergedInto == null && it.tableId == t.id }.sortedBy { it.openedAt }
            val toSettle = unsettled.filter { b -> all[b.orderId]?.let { root(all, it).tableId } == t.id }
            val billedLately = all.values.filter { it.status == BILLED && it.mergedInto == null && it.tableId == t.id && it.openedAt >= dayAgo }
            val late = billedLately.sumOf { o -> unbilled(c, group(all, o)).size }
            if (!t.active && roots.isEmpty() && toSettle.isEmpty() && late == 0) return@mapNotNull null
            val groups = roots.map { group(all, it) }
            val sent = groups.sumOf { g -> net(unbilled(c, g)).first.sumOf { it.amount } }
            val covers = groups.sumOf { g -> g.sumOf { it.covers ?: 0 } }
            State(t, roots, sent, covers, roots.minOfOrNull { it.openedAt }, toSettle, late)
        }
    }

    // ── writing ─────────────────────────────────────────────────────────

    private fun day(at: Long) = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(at))

    private fun newId(prefix: String) = prefix + UUID.randomUUID().toString().replace("-", "").take(16)

    /** Seats a party at [table]: an order with no rounds yet (it goes up with its first round). */
    fun open(c: Context, table: Table, covers: Int?): Order {
        val me = Cloud.me(c) ?: error("not connected")
        val now = Cloud.now(c)
        val data = JSONObject().put("tableId", table.id).put("openedAt", now).put("day", day(now))
            .put("deviceId", me.deviceId).put("status", OPEN).put("billIds", JSONArray())
        if (covers != null && covers > 0) data.put("covers", covers)
        val rec = Store.Rec("order", newId("ord_"), data, outletId = me.outletId, updatedAt = now, dirty = true, fieldTimes = GROUPS.associateWith { now })
        Store.saveRecord(c, rec)
        return toOrder(rec)
    }

    /** Sends [lines] to the kitchen as one round of [orderId]. */
    fun send(c: Context, orderId: String, lines: List<Line>, approvals: List<Manager.Approval> = emptyList()): Round {
        val me = Cloud.me(c) ?: error("not connected")
        val now = Cloud.now(c)
        val id = newId("rnd_")
        val data = JSONObject().put("orderId", orderId).put("deviceId", me.deviceId).put("origin", me.name)
            .put("time", now).put("day", day(now)).put("lines", JSONArray().apply { lines.forEach { put(lineJson(it)) } })
        if (approvals.isNotEmpty()) data.put("approvals", JSONArray().apply { approvals.forEach { put(it.toJson(id)) } })
        Store.saveRecord(c, Store.Rec("round", id, data, outletId = me.outletId, updatedAt = now, dirty = true))
        return Round(id, orderId, me.deviceId, me.name, now, lines, synced = false)
    }

    private fun lineJson(l: Line) = JSONObject().apply {
        put("name", l.name)
        put("price", Protocol.paise(l.price))
        put("qty", l.qty)
        if (l.note.isNotBlank()) put("note", l.note)
        if (l.itemId != null) put("itemId", l.itemId)
        if (l.variant != null) put("variant", l.variant)
        if (l.custom) put("custom", true)
        if (l.taxRate != null) put("taxRate", l.taxRate)
        l.cancelOf?.let { put("cancelOf", JSONObject().put("roundId", it.first).put("idx", it.second)) }
        if (l.reason.isNotBlank()) put("reason", l.reason)
    }

    /** Takes back [qty] of a dish sent to the kitchen: a round of its own, which the kitchen gets as a CANCEL slip. */
    fun takeBack(c: Context, orderId: String, round: Round, idx: Int, qty: Int, reason: String, approval: Manager.Approval?): Round {
        val l = round.lines[idx]
        return send(c, orderId, listOf(l.copy(qty = -qty, cancelOf = round.id to idx, reason = reason)), listOfNotNull(approval))
    }

    /** One edit of an order: the groups it touches move to now, always after the state it was made on. */
    private fun edit(c: Context, o: Order, groups: Set<String>, change: (JSONObject) -> Unit) {
        val prev = Store.record(c, "order", o.id) ?: return
        val now = maxOf(Cloud.now(c), prev.updatedAt + 1, (prev.fieldTimes?.values?.maxOrNull() ?: 0) + 1)
        val data = JSONObject(prev.data.toString()).also(change)
        val times = (prev.fieldTimes ?: GROUPS.associateWith { prev.updatedAt }) + groups.associateWith { now }
        Store.saveRecord(c, prev.copy(data = data, updatedAt = now, dirty = true, fieldTimes = times, byUser = null, proof = null))
    }

    fun move(c: Context, o: Order, to: Table) = edit(c, o, setOf("place")) { it.put("tableId", to.id) }

    /** Puts [o]'s party on [into]'s bill: nothing is rewritten, [o] just points at it (like customer merges). */
    fun merge(c: Context, o: Order, into: Order) = edit(c, o, setOf("place")) { it.put("tableId", into.tableId).put("mergedInto", into.id) }

    fun setCovers(c: Context, o: Order, covers: Int?) = edit(c, o, setOf("info")) { if (covers != null && covers > 0) it.put("covers", covers) else it.remove("covers") }

    fun cancel(c: Context, o: Order, reason: String, approval: Manager.Approval?) = edit(c, o, setOf("status")) {
        it.put("status", CANCELLED).put("cancelReason", reason)
        if (approval != null) it.put("approvals", (it.optJSONArray("approvals") ?: JSONArray()).put(approval.toJson(o.id)))
    }

    /**
     * Prints the bill for [root]'s party: one bill with every round not billed yet, its
     * token and invoice number taken now. It isn't paid yet ([Sale.unsettled]) until
     * [Sales.settle]. Every order of the party is then billed.
     */
    fun bill(
        c: Context, root: Order, tableName: String, net: List<Net>, roundIds: List<String>,
        discount: Double, discountReason: String, serviceChargePct: Double, approvals: List<Manager.Approval>,
    ): Sale = Store.tx(c) {
        val me = Cloud.me(c) ?: error("not connected")
        val all = orders(c).associateBy { it.id }
        val party = group(all, root)
        val now = Cloud.now(c)
        val today = day(now)
        val letter = Cloud.letter(c)
        val token = Prefs.nextToken(c, atLeast = Sales.maxToken(c, today, "", letter) + 1)
        val uid = UUID.randomUUID().toString()
        val sale = Sale(
            uid = uid, id = Sales.billId(now, letter, token), token = token, letter = letter, time = now, updatedAt = now,
            lines = net.map { it.line.toSaleLine(it.qty) }, payment = "", customer = "",
            discount = discount, discountReason = discountReason,
            deviceId = me.deviceId, origin = me.name, invoiceNo = Cloud.nextInvoice(c, today),
            approvals = approvals.map { ApprovalNote.from(it.toJson(uid)) },
            orderId = root.id, table = tableName, roundIds = roundIds,
            covers = party.sumOf { it.covers ?: 0 }.takeIf { it > 0 }, serviceChargePct = serviceChargePct,
        )
        Sales.record(c, sale)
        for (o in party) edit(c, o, setOf("status")) { d ->
            d.put("status", BILLED)
            if (o.id == root.id) d.put("billIds", JSONArray(o.billIds + uid))
        }
        sale
    }

    /** Reopens a printed table bill: it is voided (its invoice number stays used) and the party's orders are open again. */
    fun reopen(c: Context, sale: Sale, approval: Manager.Approval?) = Store.tx(c) {
        Sales.setVoided(c, sale.uid, true, "Reopened", approval)
        val all = orders(c).associateBy { it.id }
        val root = sale.orderId?.let(all::get) ?: return@tx
        for (o in group(all, root(all, root))) edit(c, o, setOf("status")) { it.put("status", OPEN) }
    }

    private val NUMERIC = Comparator<String> { a, b ->
        val re = Regex("(\\d+)|(\\D+)")
        val pa = re.findAll(a.lowercase()).map { it.value }.toList()
        val pb = re.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(pa.size, pb.size)) {
            val x = pa[i]
            val y = pb[i]
            val d = if (x[0].isDigit() && y[0].isDigit()) (x.toBigInteger() - y.toBigInteger()).signum() else x.compareTo(y)
            if (d != 0) return@Comparator d
        }
        pa.size - pb.size
    }
}
