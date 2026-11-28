package com.parchipos.app

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.app.AlertDialog

/**
 * Every table of the outlet, by area, live: free, running (what has gone to
 * the kitchen, for how long), or a bill waiting for its payment. A free table
 * opens the till for its first round; a busy one opens its order.
 *
 * Back, or Counter, returns the device to the counter till for good; Tables
 * on the till brings it back here.
 */
class TablesActivity : ParchiActivity() {

    private lateinit var list: LinearLayout
    private lateinit var summary: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val refresh: () -> Unit = { if (!isFinishing) render() }
    /** Minutes on the tiles move on; a sync every so often brings other devices' rounds. */
    private val tick = object : Runnable {
        override fun run() {
            SyncEngine.kick(this@TablesActivity)
            render()
            ui.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = resources.getDimensionPixelSize(R.dimen.side_padding)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, dp(12), pad, dp(8))
        }
        header.addView(TextView(this).apply {
            text = getString(R.string.title_tables)
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.ink))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(topButton(getString(R.string.btn_sales)) { startActivity(Intent(this, SalesActivity::class.java)) })
        header.addView(topButton(getString(R.string.btn_counter)) { toCounter() })
        root.addView(header)
        summary = TextView(this).apply {
            textSize = 13f
            setTextColor(getColor(R.color.ink_soft))
            setPadding(pad, 0, pad, dp(6))
        }
        root.addView(summary)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, dp(24))
        }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        SyncEngine.listen(refresh)
        ui.removeCallbacks(tick)
        tick.run()
    }

    override fun onPause() {
        super.onPause()
        SyncEngine.unlisten(refresh)
        ui.removeCallbacks(tick)
    }

    @Deprecated("Back goes to the counter till")
    override fun onBackPressed() = toCounter()

    private fun toCounter() {
        MainActivity.counterMode(this)
        finish()
    }

    private fun render() {
        list.removeAllViews()
        val states = Tables.states(this)
        if (states.isEmpty()) {
            list.addView(TextView(this).apply {
                text = getString(R.string.tables_none)
                textSize = 15f
                setTextColor(getColor(R.color.ink_soft))
                setPadding(0, dp(24), 0, 0)
            })
            summary.text = ""
            return
        }
        val running = states.count { it.running.isNotEmpty() }
        summary.text = getString(R.string.tables_summary, running, MainActivity.rs(states.sumOf { it.sent }), states.sumOf { it.toSettle.size })

        // Roughly 150dp a tile: two across on a phone, more on a tablet or an open Fold.
        val usableDp = resources.configuration.screenWidthDp - 2 * resources.getDimension(R.dimen.side_padding) / resources.displayMetrics.density
        val cols = (usableDp / 150).toInt().coerceIn(2, 8)
        val areas = states.map { it.table.area }.distinct().sortedBy { if (it.isEmpty()) 1 else 0 }
        for (area in areas) {
            val inArea = states.filter { it.table.area == area }
            if (areas.size > 1 || area.isNotEmpty()) list.addView(TextView(this).apply {
                text = area.ifEmpty { "—" }.uppercase()
                textSize = 12f
                letterSpacing = 0.08f
                setTextColor(getColor(R.color.ink_soft))
                setPadding(0, dp(14), 0, dp(2))
            })
            inArea.chunked(cols).forEach { rowStates ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for (i in 0 until cols) {
                    val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) marginStart = dp(8) }
                    row.addView(if (i < rowStates.size) tile(rowStates[i]) else View(this), lp)
                }
                list.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            }
        }
    }

    private fun tile(s: Tables.State): View {
        val busy = s.running.isNotEmpty()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(92)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(if (busy) R.drawable.tile_selected else R.drawable.tile)
            if (!s.table.active && s.free) alpha = 0.45f
        }
        body.addView(TextView(this).apply {
            text = s.table.name + (s.table.seats?.let { "  ·  $it" } ?: "")
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.ink))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        val lines = ArrayList<Pair<String, Int>>()
        if (busy) {
            lines += getString(R.string.tile_running, MainActivity.rs(s.sent), s.since?.let { duration(Cloud.now(this) - it) } ?: "") to R.color.accent
            if (s.running.size > 1) lines += getString(R.string.tile_parties, s.running.size) to R.color.ink_soft
            if (s.covers > 0) lines += getString(R.string.order_guests, s.covers) to R.color.ink_soft
        }
        for (b in s.toSettle) lines += getString(R.string.tile_to_settle, MainActivity.rs(b.total)) to R.color.warn
        if (s.notOnBill > 0) lines += getString(R.string.tile_not_on_bill, s.notOnBill) to R.color.danger
        if (lines.isEmpty()) lines += getString(if (s.table.active) R.string.tile_free else R.string.tile_off) to R.color.ink_faint
        for ((t, colour) in lines) body.addView(TextView(this).apply {
            text = t
            textSize = 13f
            setTextColor(getColor(colour))
            if (colour == R.color.accent || colour == R.color.warn) setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
        body.setOnClickListener { tapped(s) }
        return body
    }

    /** A free table seats a party; anything else opens what is going on at it. */
    private fun tapped(s: Tables.State) {
        val order = s.running.firstOrNull()
            ?: s.toSettle.firstOrNull()?.orderId?.let { Tables.order(this, it) }
            ?: if (s.notOnBill > 0) Tables.orders(this).filter { it.tableId == s.table.id && it.status == Tables.BILLED }.maxByOrNull { it.openedAt } else null
        if (order != null && s.running.size <= 1) {
            startActivity(Intent(this, OrderActivity::class.java).putExtra(OrderActivity.EXTRA_ORDER, order.id))
            return
        }
        if (s.running.size > 1) {
            // Two parties at one table: pick which.
            val names = s.running.map { o -> getString(R.string.order_opened, time(o.openedAt), o.deviceId?.let(::deviceName) ?: "") }.toTypedArray()
            AlertDialog.Builder(this).setTitle(s.table.name).setItems(names) { _, i ->
                startActivity(Intent(this, OrderActivity::class.java).putExtra(OrderActivity.EXTRA_ORDER, s.running[i].id))
            }.show()
            return
        }
        if (!s.table.active) return
        askCovers(s.table) { covers -> startActivity(MainActivity.forTable(this, s.table, null, covers)) }
    }

    /** Optional: a party's size, for the bill and for reports. Skip leaves it uncounted. */
    private fun askCovers(t: Tables.Table, then: (Int?) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.covers_hint)
            setText(t.seats?.toString() ?: "")
            selectAll()
        }
        val box = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }
        val d = AlertDialog.Builder(this)
            .setTitle(getString(R.string.covers_title, t.name))
            .setView(box)
            .setPositiveButton(R.string.btn_ok) { _, _ -> then(input.text.toString().trim().toIntOrNull()?.takeIf { it in 1..999 }) }
            .setNegativeButton(R.string.btn_skip) { _, _ -> then(null) }
            .create()
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        input.requestFocus()
    }

    private fun deviceName(id: String) = Cloud.phones(this).find { it.id == id }?.name ?: ""

    private fun topButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        minHeight = 0; minimumHeight = 0
        setTextColor(getColor(R.color.ink))
        setBackgroundResource(R.drawable.btn_secondary)
        setPadding(dp(12), 0, dp(12), 0)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginStart = dp(6) }
    }

    private fun duration(ms: Long): String {
        val m = (ms / 60_000).coerceAtLeast(0).toInt()
        return if (m < 60) getString(R.string.minutes_short, m) else getString(R.string.hours_short, m / 60, m % 60)
    }

    private fun time(t: Long) = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(t))

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
