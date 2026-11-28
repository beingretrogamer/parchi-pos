package com.parchipos.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * One party's order: every round it sent to the kitchen (from any device),
 * what that adds up to, and what can be done with it: more items, the bill,
 * settling it, moving or combining tables, taking an item back, cancelling.
 * A party merged into another shows as part of the other's order.
 */
class OrderActivity : ParchiActivity() {

    private lateinit var body: LinearLayout
    private lateinit var actions: LinearLayout
    private var orderId = ""
    private val refresh: () -> Unit = { if (!isFinishing) render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        orderId = intent.getStringExtra(EXTRA_ORDER) ?: run { finish(); return }
        val pad = resources.getDimensionPixelSize(R.dimen.side_padding)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
        }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(12), pad, dp(16))
        }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, dp(8), pad, dp(12))
            setBackgroundColor(getColor(R.color.card))
        }
        root.addView(actions)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        SyncEngine.listen(refresh)
        SyncEngine.kick(this)
        render()
    }

    override fun onPause() {
        super.onPause()
        SyncEngine.unlisten(refresh)
    }

    private class Party(val root: Tables.Order, val orders: List<Tables.Order>, val table: Tables.Table?, val rounds: List<Tables.Round>, val unbilled: List<Tables.Round>, val bills: List<Sale>)

    private fun party(): Party? {
        val all = Tables.orders(this).associateBy { it.id }
        val o = all[orderId] ?: return null
        val root = Tables.root(all, o)
        val orders = Tables.group(all, root).ifEmpty { listOf(root) }
        val ids = orders.map { it.id }.toSet()
        return Party(root, orders, Tables.tables(this, includeOff = true).find { it.id == root.tableId },
            Tables.rounds(this, ids), Tables.unbilled(this, orders), Tables.billsOf(this, ids).sortedBy { it.time })
    }

    private fun render() {
        body.removeAllViews()
        actions.removeAllViews()
        val p = party() ?: run { finish(); return }
        val name = p.table?.name ?: "?"
        orderId = p.root.id

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(smallButton(getString(R.string.btn_back_tables)) { finish() })
        top.addView(text(name, 26f, bold = true).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(top)
        val covers = p.orders.sumOf { it.covers ?: 0 }
        body.addView(text(getString(R.string.order_opened, time(p.root.openedAt), deviceName(p.root.deviceId)) + "   ·   " +
            (if (covers > 0) getString(R.string.order_guests, covers) else getString(R.string.order_guests_none)), 14f, R.color.ink_soft).apply {
            setPadding(0, dp(4), 0, dp(8))
            setOnClickListener { if (p.root.open) askGuests(p) }
        })
        p.orders.filter { it.id != p.root.id }.forEach { m ->
            val from = Tables.tables(this, includeOff = true).find { it.id == m.tableId }?.name
            body.addView(text(getString(R.string.order_merged_in, from ?: "?"), 13f, R.color.ink_soft))
        }

        val unsettled = p.bills.filter { it.unsettled && !it.voided }
        for (b in unsettled) body.addView(banner(getString(R.string.order_billed, b.label, MainActivity.rs(b.total)), R.color.warn))
        val billed = p.root.status == Tables.BILLED
        if (billed && p.unbilled.isNotEmpty()) body.addView(banner(getString(R.string.order_late_rounds, p.unbilled.size), R.color.danger))
        if (p.root.status == Tables.CANCELLED) body.addView(banner(p.root.cancelReason, R.color.ink_soft))

        // Every round, oldest first, each as the kitchen got it.
        if (p.rounds.isEmpty()) body.addView(text(getString(R.string.order_empty), 15f, R.color.ink_soft).apply { setPadding(0, dp(16), 0, 0) })
        var no = 0
        for (r in p.rounds) {
            val back = r.lines.all { it.qty < 0 }
            if (!back) no++
            val head = if (back) getString(R.string.round_takeback, time(r.time), r.origin) else getString(R.string.round_header, no, time(r.time), r.origin)
            body.addView(text(head + if (!r.synced) "   " + getString(R.string.round_unsynced) else "", 12f, R.color.ink_soft).apply {
                setPadding(0, dp(14), 0, dp(4)); letterSpacing = 0.04f
            })
            val onBill = r !in p.unbilled
            r.lines.forEachIndexed { i, l ->
                val row = lineRow(l, struck = l.qty < 0)
                if (l.qty > 0 && p.root.open && !onBill) row.setOnClickListener { takeBack(p, name, r, i) }
                body.addView(row)
            }
        }

        // What the next bill comes to.
        val (net, over) = Tables.net(p.unbilled)
        if (net.isNotEmpty()) {
            body.addView(text(getString(R.string.order_so_far).uppercase(), 12f, R.color.ink_soft).apply { setPadding(0, dp(20), 0, dp(4)); letterSpacing = 0.08f })
            net.forEach { body.addView(lineRow(it.line.copy(qty = it.qty), struck = false)) }
            body.addView(text(getString(R.string.order_total, net.sumOf { it.qty }, MainActivity.rs(net.sumOf { it.amount })), 18f, bold = true).apply {
                gravity = Gravity.END; setPadding(0, dp(8), 0, 0)
            })
        }
        if (over) body.addView(text(getString(R.string.order_overcancelled), 13f, R.color.danger).apply { setPadding(0, dp(8), 0, 0) })

        // What can be done now.
        when {
            p.root.open -> {
                action(getString(R.string.btn_order_add_items), primary = false) { startActivity(p.table?.let { MainActivity.forTable(this, it, p.root.id, null) }) }
                if (net.isNotEmpty()) action(getString(R.string.btn_print_bill), primary = true) { printBill(p, name) }
                action(getString(R.string.btn_more), primary = false) { more(p, name) }
            }
            unsettled.isNotEmpty() -> {
                action(getString(R.string.btn_reopen), primary = false) { reopen(p, name, unsettled.last()) }
                if (p.unbilled.isNotEmpty()) action(getString(R.string.btn_bill_rest), primary = false) { printBill(p, name) }
                action(getString(R.string.btn_settle), primary = true) { TableUi.settle(this, unsettled.first(), name) { render() } }
            }
            billed && p.unbilled.isNotEmpty() -> {
                p.bills.lastOrNull { !it.voided }?.let { b -> action(getString(R.string.btn_reopen), primary = false) { reopen(p, name, b) } }
                action(getString(R.string.btn_bill_rest), primary = true) { printBill(p, name) }
            }
        }
    }

    // ── actions ─────────────────────────────────────────────────────────

    /**
     * The bill: sync first so every device's rounds are in, then a preview with the
     * discount and the outlet's service charge (which the guest may refuse), then print.
     */
    private fun printBill(p: Party, name: String) {
        Toast.makeText(this, R.string.syncing_first, Toast.LENGTH_SHORT).show()
        thread {
            val err = SyncEngine.runOnce(this)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (err.isEmpty()) preview(name) else AlertDialog.Builder(this)
                    .setTitle(R.string.bill_offline_title)
                    .setMessage(getString(R.string.bill_offline_body) + "\n\n" + err)
                    .setPositiveButton(R.string.btn_print_anyway) { _, _ -> preview(name) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun preview(name: String) {
        val p = party() ?: return
        val net = Tables.net(p.unbilled).first
        if (net.isEmpty()) { render(); return }
        val sc = Cloud.outlet(this)?.optJSONObject("serviceCharge")
        val outletPct = sc?.optDouble("pct", 0.0)?.takeIf { !it.isNaN() && it > 0 } ?: 0.0
        val label = sc?.optString("label")?.ifBlank { null } ?: "Service charge"
        var pct = outletPct
        var discount = 0.0
        var reason = ""
        val approvals = ArrayList<Manager.Approval>()
        val subtotal = net.sumOf { it.amount }

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        val figures = text("", 15f)
        fun draw() {
            val sale = Sale(id = "", token = 0, time = 0, lines = net.map { it.line.toSaleLine(it.qty) }, payment = "", customer = "", discount = discount, serviceChargePct = pct)
            figures.text = buildString {
                net.forEach { append("${it.qty} × ${it.line.name}   ${MainActivity.rs(it.amount)}\n") }
                append("\n")
                if (discount > 0 || pct > 0) append("Subtotal   ${MainActivity.rs(subtotal)}\n")
                if (discount > 0) append(getString(R.string.discount_line, MainActivity.rs(discount), reason) + "\n")
                if (pct > 0) append(getString(R.string.bill_service_charge, label, Sales.money(pct).removeSuffix(".00")) + "   " + MainActivity.rs(sale.serviceCharge) + "\n")
                else if (outletPct > 0) append(getString(R.string.bill_service_off) + "\n")
                append("\nTOTAL   ${MainActivity.rs(sale.total)}")
            }
        }
        draw()
        box.addView(figures)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        buttons.addView(smallButton(getString(R.string.btn_discount_short)) {
            askDiscount(subtotal) { off, why, approval ->
                discount = off; reason = why
                approvals.removeAll { it.action == Rules.DISCOUNT }
                approval?.let(approvals::add)
                draw()
            }
        })
        if (outletPct > 0) {
            lateinit var toggle: Button
            toggle = smallButton(getString(R.string.btn_service_charge_off)) {
                pct = if (pct > 0) 0.0 else outletPct
                toggle.text = getString(if (pct > 0) R.string.btn_service_charge_off else R.string.btn_service_charge_on)
                draw()
            }
            buttons.addView(toggle)
        }
        box.addView(buttons)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.bill_preview_title, name))
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(R.string.btn_print_bill) { _, _ ->
                val sale = Tables.bill(this, p.root, name, net, p.unbilled.map { it.id }, discount, reason, pct, approvals)
                Printer.sale(this, sale, true, false) { failed ->
                    if (failed.isNotEmpty() && !isFinishing) Toast.makeText(this, getString(R.string.reprint_failed, failed.joinToString("; ") { "${it.printer}: ${it.reason}" }), Toast.LENGTH_LONG).show()
                }
                Toast.makeText(this, getString(R.string.bill_printed, name), Toast.LENGTH_LONG).show()
                Sync.kick(this)
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** "50" is ₹50 off, "10%" is 10%. Over the till rules' limit, a PIN first. */
    private fun askDiscount(subtotal: Double, done: (Double, String, Manager.Approval?) -> Unit) {
        val amount = EditText(this).apply { hint = "₹ or %"; inputType = InputType.TYPE_CLASS_TEXT }
        val why = EditText(this).apply { hint = getString(R.string.hint_disc_reason) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0); addView(amount); addView(why) }
        AlertDialog.Builder(this).setTitle(R.string.title_discount).setView(box)
            .setPositiveButton(R.string.btn_apply) { _, _ ->
                val t = amount.text.toString().trim()
                val off = (if (t.endsWith("%")) t.dropLast(1).trim().toDoubleOrNull()?.let { subtotal * it / 100 } else t.toDoubleOrNull())
                    ?.coerceIn(0.0, subtotal)?.let { Math.round(it * 100) / 100.0 } ?: return@setPositiveButton
                if (Rules.discountNeedsPin(this, off, subtotal)) Manager.approve(this, Rules.DISCOUNT) { done(off, why.text.toString().trim(), it) }
                else done(off, why.text.toString().trim(), null)
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    /** One dish back from the kitchen: how many, and why (the kitchen's CANCEL slip says). */
    private fun takeBack(p: Party, name: String, r: Tables.Round, idx: Int) {
        val l = r.lines[idx]
        Manager.approve(this, Rules.CANCEL_SENT) { approval ->
            val qty = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(if (l.qty == 1) "1" else ""); hint = getString(R.string.takeback_qty, l.qty) }
            val why = EditText(this).apply { hint = getString(R.string.takeback_reason) }
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0); addView(qty); addView(why) }
            val d = AlertDialog.Builder(this).setTitle(getString(R.string.takeback_title, l.name)).setView(box)
                .setPositiveButton(R.string.btn_takeback, null).setNegativeButton(android.R.string.cancel, null).create()
            d.show()
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = qty.text.toString().trim().toIntOrNull()
                val reason = why.text.toString().trim()
                when {
                    n == null || n < 1 || n > l.qty -> qty.error = getString(R.string.takeback_qty, l.qty)
                    reason.isEmpty() -> why.error = getString(R.string.takeback_reason)
                    else -> {
                        d.dismiss()
                        val round = Tables.takeBack(this, p.root.id, r, idx, n, reason, approval)
                        Printer.round(this, name, round, 0, null) { err -> if (err != null) Toast.makeText(this, getString(R.string.kitchen_print_failed, name, err), Toast.LENGTH_LONG).show() }
                        Toast.makeText(this, getString(R.string.taken_back, l.name), Toast.LENGTH_SHORT).show()
                        Sync.kick(this)
                        render()
                    }
                }
            }
        }
    }

    private fun more(p: Party, name: String) {
        val items = listOf(
            getString(R.string.order_more_move) to { move(p, name) },
            getString(R.string.order_more_merge) to { merge(p, name) },
            getString(R.string.order_more_guests) to { askGuests(p) },
            getString(R.string.order_more_reprint_round) to { reprintRound(p, name) },
            getString(R.string.order_more_cancel) to { cancel(p, name) },
        )
        AlertDialog.Builder(this).setTitle(name).setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
    }

    private fun move(p: Party, name: String) {
        val free = Tables.states(this).filter { it.free && it.table.active && it.table.id != p.root.tableId }.map { it.table }
        if (free.isEmpty()) { Toast.makeText(this, R.string.no_free_tables, Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setTitle(getString(R.string.pick_table_move, name)).setItems(free.map { it.name }.toTypedArray()) { _, i ->
            Store.tx(this) { p.orders.forEach { Tables.move(this, it, free[i]) } }
            Toast.makeText(this, getString(R.string.moved_to, free[i].name), Toast.LENGTH_SHORT).show()
            Sync.kick(this)
            render()
        }.show()
    }

    /** This party bills with another table's: it points at that order, and its rounds go on that bill. */
    private fun merge(p: Party, name: String) {
        val others = Tables.states(this).flatMap { s -> s.running.map { s.table to it } }.filter { it.second.id != p.root.id }
        if (others.isEmpty()) { Toast.makeText(this, R.string.no_other_orders, Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setTitle(getString(R.string.pick_table_merge, name)).setItems(others.map { it.first.name }.toTypedArray()) { _, i ->
            val (table, into) = others[i]
            Tables.merge(this, p.root, into)
            Toast.makeText(this, getString(R.string.merged_with, name, table.name), Toast.LENGTH_SHORT).show()
            Sync.kick(this)
            orderId = into.id
            render()
        }.show()
    }

    private fun askGuests(p: Party) {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(p.root.covers?.toString() ?: ""); selectAll() }
        val box = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }
        AlertDialog.Builder(this).setTitle(getString(R.string.covers_title, p.table?.name ?: "")).setView(box)
            .setPositiveButton(R.string.btn_ok) { _, _ ->
                Tables.setCovers(this, p.root, input.text.toString().trim().toIntOrNull()?.takeIf { it in 1..999 })
                Sync.kick(this)
                render()
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun reprintRound(p: Party, name: String) {
        if (p.rounds.isEmpty()) return
        var n = 0
        val labels = p.rounds.map { r -> if (r.lines.all { it.qty < 0 }) getString(R.string.round_takeback, time(r.time), r.origin) else getString(R.string.round_header, ++n, time(r.time), r.origin) }
        AlertDialog.Builder(this).setTitle(R.string.order_more_reprint_round).setItems(labels.toTypedArray()) { _, i ->
            val r = p.rounds[i]
            val no = p.rounds.take(i + 1).count { x -> x.lines.any { it.qty > 0 } }
            Printer.round(this, name, r, no, p.orders.sumOf { it.covers ?: 0 }.takeIf { it > 0 }) { err ->
                Toast.makeText(this, err?.let { getString(R.string.kitchen_print_failed, name, it) } ?: getString(R.string.reprint_ok, name), Toast.LENGTH_SHORT).show()
            }
        }.show()
    }

    private fun cancel(p: Party, name: String) {
        val go = { approval: Manager.Approval? ->
            Dialogs.askReason(this, getString(R.string.cancel_order_title, name) + "\n" + getString(R.string.cancel_order_body), getString(R.string.order_more_cancel)) { reason ->
                Store.tx(this) { p.orders.forEach { Tables.cancel(this, it, reason, approval) } }
                Toast.makeText(this, getString(R.string.order_cancelled, name), Toast.LENGTH_SHORT).show()
                Sync.kick(this)
                finish()
            }
        }
        // Nothing sent yet costs the kitchen nothing; otherwise it is a take-back of everything.
        if (p.rounds.isEmpty()) go(null) else Manager.approve(this, Rules.CANCEL_SENT) { go(it) }
    }

    private fun reopen(p: Party, name: String, bill: Sale) {
        Manager.approve(this, Rules.REOPEN_BILL) { approval ->
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.reopen_title, name))
                .setMessage(getString(R.string.reopen_body, bill.invoiceNo ?: bill.label))
                .setPositiveButton(R.string.btn_reopen) { _, _ ->
                    Tables.reopen(this, bill, approval)
                    Toast.makeText(this, getString(R.string.reopened, name), Toast.LENGTH_SHORT).show()
                    Sync.kick(this)
                    render()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    // ── views ───────────────────────────────────────────────────────────

    private fun lineRow(l: Tables.Line, struck: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(40)
            setBackgroundResource(R.drawable.row_bg_plain)
        }
        val label = "${Math.abs(l.qty)} × ${l.name}" + (if (l.note.isNotBlank()) "  ·  ${l.note}" else "") + (if (l.reason.isNotBlank()) "  ·  ${l.reason}" else "")
        row.addView(text(label, 15f, if (struck) R.color.danger else R.color.ink), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(text((if (l.qty < 0) "−" else "") + MainActivity.rs(Math.abs(l.price * l.qty)), 15f, if (struck) R.color.danger else R.color.ink))
        if (struck) for (i in 0 until row.childCount) (row.getChildAt(i) as TextView).paintFlags = Paint.STRIKE_THRU_TEXT_FLAG
        return row
    }

    private fun banner(t: String, colour: Int) = text(t, 14f, colour, bold = true).apply {
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setBackgroundResource(R.drawable.card)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    }

    private fun action(label: String, primary: Boolean, onClick: () -> Unit) {
        actions.addView(Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(getColor(if (primary) R.color.on_accent else R.color.ink))
            setBackgroundResource(if (primary) R.drawable.btn_primary else R.drawable.btn_secondary)
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(0, dp(52), if (primary) 1.4f else 1f).apply { if (actions.childCount > 0) marginStart = dp(8) })
    }

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        minHeight = 0; minimumHeight = 0
        setTextColor(getColor(R.color.ink))
        setBackgroundResource(R.drawable.btn_secondary)
        setPadding(dp(12), 0, dp(12), 0)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginEnd = dp(8) }
    }

    private fun text(t: String, size: Float, colour: Int = R.color.ink, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(getColor(colour))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun deviceName(id: String?) = Cloud.phones(this).find { it.id == id }?.name ?: ""

    private fun time(t: Long) = SimpleDateFormat("HH:mm", Locale.US).format(Date(t))

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_ORDER = "order"
    }
}

/** Dialogs shared by the order screen and Sales. */
object TableUi {
    /** Settles a printed table bill: Cash, UPI, or on a customer's dues. */
    fun settle(a: Activity, sale: Sale, name: String, done: () -> Unit) {
        val modes = listOf("CASH" to R.string.pay_cash, "UPI" to R.string.pay_upi, CREDIT to R.string.pay_credit)
        AlertDialog.Builder(a)
            .setTitle(a.getString(R.string.settle_title, name, MainActivity.rs(sale.total)))
            .setItems(modes.map { a.getString(it.second) }.toTypedArray()) { _, i ->
                val mode = modes[i].first
                if (mode != CREDIT) {
                    Sales.settle(a, sale.uid, mode)
                    Toast.makeText(a, a.getString(R.string.table_settled, name, a.getString(modes[i].second)), Toast.LENGTH_SHORT).show()
                    Sync.kick(a)
                    done()
                    return@setItems
                }
                val who = AutoCompleteTextView(a).apply {
                    setAdapter(ArrayAdapter(a, android.R.layout.simple_dropdown_item_1line, Ledger.names(a)))
                    threshold = 1
                    hint = a.getString(R.string.settle_customer)
                    inputType = InputType.TYPE_TEXT_FLAG_CAP_WORDS
                }
                val pad = (24 * a.resources.displayMetrics.density).toInt()
                val box = LinearLayout(a).apply { setPadding(pad, pad / 3, pad, 0); addView(who, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }
                val d = AlertDialog.Builder(a).setTitle(R.string.settle_customer).setView(box)
                    .setPositiveButton(R.string.btn_settle, null).setNegativeButton(android.R.string.cancel, null).create()
                d.show()
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val n = who.text.toString().trim()
                    if (n.isEmpty()) { who.error = a.getString(R.string.err_credit_name); return@setOnClickListener }
                    d.dismiss()
                    Sales.settle(a, sale.uid, CREDIT, n)
                    Toast.makeText(a, a.getString(R.string.table_settled, name, a.getString(R.string.pay_credit)), Toast.LENGTH_SHORT).show()
                    Sync.kick(a)
                    done()
                }
            }
            .show()
    }
}
