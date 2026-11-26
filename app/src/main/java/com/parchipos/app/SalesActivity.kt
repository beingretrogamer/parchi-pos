package com.parchipos.app

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * One day's takings, the bills behind them, and the way out to a spreadsheet.
 *
 * Voided bills stay listed (struck through) rather than disappearing, so the
 * count of tokens handed out still adds up at closing.
 */
class SalesActivity : Activity() {

    private lateinit var dayLabel: TextView
    private lateinit var dayTotal: TextView
    private lateinit var daySplit: TextView
    private lateinit var dayItems: TextView
    private lateinit var noBills: TextView
    private lateinit var billList: LinearLayout

    private val day: Calendar = Calendar.getInstance()
    private var sales: List<Sale> = emptyList()
    /** Held across the document picker, which is a round trip through another app. */
    private var pendingCsv: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sales)

        dayLabel = findViewById(R.id.dayLabel)
        dayTotal = findViewById(R.id.dayTotal)
        daySplit = findViewById(R.id.daySplit)
        dayItems = findViewById(R.id.dayItems)
        noBills = findViewById(R.id.noBills)
        billList = findViewById(R.id.billList)

        findViewById<Button>(R.id.btnPrevDay).setOnClickListener { day.add(Calendar.DAY_OF_MONTH, -1); load() }
        findViewById<Button>(R.id.btnNextDay).setOnClickListener {
            if (!isToday()) { day.add(Calendar.DAY_OF_MONTH, 1); load() }
        }
        findViewById<Button>(R.id.btnShare).setOnClickListener { shareSummary() }
        // Back to the till, which bills the day shown here until told otherwise.
        findViewById<Button>(R.id.btnAddMissed).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_BILL_DAY, day.timeInMillis))
            finish()
        }
        findViewById<Button>(R.id.btnExportDay).setOnClickListener {
            export(Sales.toCsv(sales), "sales-${fileDay()}.csv")
        }
        findViewById<Button>(R.id.btnExportAll).setOnClickListener {
            export(Sales.toCsv(Sales.all(this)), "sales-all-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.csv")
        }
        load()
    }

    /** uids of bills IceCreamOS has not got in their current state; empty when sync is off. */
    private var unsynced: Set<String> = emptySet()

    private fun load(kick: Boolean = true) {
        sales = Sales.forDay(this, SimpleDateFormat("yyyyMMdd", Locale.US).format(day.time)).sortedByDescending { it.time }
        // load() follows every edit here, so this is also what sends one; when
        // the sync finishes, other phones' bills and edits are shown too.
        if (Sync.configured(this)) {
            unsynced = Sync.pending(this).map { it.uid }.toSet()
            if (kick) Sync.kick(this) { runOnUiThread { if (!isFinishing) load(kick = false) } }
        }
        dayLabel.text = if (isToday()) getString(R.string.today) else SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(day.time)

        val live = sales.filterNot { it.voided }
        dayTotal.text = MainActivity.rs(live.sumOf { it.total })
        val cash = live.filter { it.payment == "CASH" }.sumOf { it.total }
        val upi = live.filter { it.payment == "UPI" }.sumOf { it.total }
        val credit = live.filter { it.onCredit }.sumOf { it.total }
        val discount = live.sumOf { it.discount }
        val voided = sales.size - live.size
        // Dues collected today are money in the drawer, though not today's sales.
        val dayKey = SimpleDateFormat("yyyyMMdd", Locale.US).format(day.time)
        val collected = Ledger.receipts(this).filter { !it.voided && SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(it.time)) == dayKey }
        // Every phone's bills arrive through IceCreamOS; say how much was this one's.
        val others = live.filterNot { it.mine }
        daySplit.text = getString(R.string.day_split, live.size, MainActivity.rs(cash), MainActivity.rs(upi)) +
            (if (credit > 0) "\n" + getString(R.string.day_credit, MainActivity.rs(credit)) else "") +
            (if (discount > 0) "\n" + getString(R.string.day_discount, MainActivity.rs(discount)) else "") +
            (if (collected.isNotEmpty()) "\n" + getString(R.string.day_collected,
                MainActivity.rs(collected.filter { it.mode == "CASH" }.sumOf { it.amount }),
                MainActivity.rs(collected.filter { it.mode == "UPI" }.sumOf { it.amount })) else "") +
            (if (others.isNotEmpty()) "\n" + getString(R.string.day_split_phones,
                MainActivity.rs(live.filter { it.mine }.sumOf { it.total }), MainActivity.rs(others.sumOf { it.total })) else "") +
            if (voided > 0) "\n" + getString(R.string.day_voided, voided) else ""

        dayItems.text = itemTotals(live).joinToString("\n") { (name, qty) -> "${qty.toString().padStart(4)}  $name" }
        dayItems.visibility = if (live.isEmpty()) View.GONE else View.VISIBLE

        noBills.visibility = if (sales.isEmpty()) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.btnAddMissed).visibility = if (isToday()) View.GONE else View.VISIBLE
        billList.removeAllViews()
        sales.forEach { billList.addView(billRow(it)) }
    }

    /** Item-wise quantities, bestseller first — what to restock tomorrow. */
    private fun itemTotals(list: List<Sale>): List<Pair<String, Int>> =
        list.flatMap { it.lines }.groupBy { it.name }
            .map { (name, lines) -> name to lines.sumOf { it.qty } }
            .sortedByDescending { it.second }

    private fun billRow(s: Sale): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setBackgroundResource(R.drawable.row_bg)
            setOnClickListener { billActions(s) }
        }
        val time = SimpleDateFormat("HH:mm", Locale.US).format(Date(s.time))
        row.addView(text("#${s.label}", 16f, bold = true), LinearLayout.LayoutParams(dp(60), LinearLayout.LayoutParams.WRAP_CONTENT))
        val desc = listOf(time, getString(R.string.n_items, s.itemCount),
            if (s.onCredit) getString(R.string.pay_credit) else s.payment, s.phone, s.customer,
            if (s.discount > 0) getString(R.string.disc_short, MainActivity.rs(s.discount)) else "",
            if (s.voided) getString(R.string.voided_because, s.voidReason) else "",
            if (s.uid in unsynced) getString(R.string.not_synced) else "")
            .filter { it.isNotBlank() }.joinToString("  ·  ")
        row.addView(text(desc, 14f, colour = R.color.ink_soft), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(text(MainActivity.rs(s.total), 16f, bold = true).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        if (s.voided) {
            for (i in 0 until row.childCount) {
                (row.getChildAt(i) as TextView).apply {
                    paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                    setTextColor(getColor(R.color.ink_faint))
                }
            }
        }
        return row
    }

    private fun billActions(s: Sale) {
        val detail = s.lines.joinToString("\n") { "${it.qty} × ${it.name}   ${MainActivity.rs(it.amount)}" } +
            (if (s.discount > 0) "\n" + getString(R.string.discount_line, MainActivity.rs(s.discount), s.discountReason) else "") +
            (if (s.voided) "\n\n" + getString(R.string.voided_because, s.voidReason) else "")
        val labels = arrayOf(
            getString(R.string.reprint_both),
            getString(R.string.reprint_bill),
            getString(R.string.reprint_parchi),
            getString(if (s.voided) R.string.restore_bill else R.string.void_bill),
            getString(R.string.change_date),
            getString(R.string.change_payment_any),
        )
        // A custom title, because a stock one cuts off after two lines and the
        // point is to see what was on the bill before reprinting or voiding it.
        val header = text(getString(R.string.bill_title, s.label, MainActivity.rs(s.total)) +
            (if (s.mine) "" else "  ·  ${s.phone}") + "\n\n" + detail, 15f).apply {
            maxLines = Int.MAX_VALUE
            setPadding(dp(24), dp(20), dp(24), dp(8))
        }
        AlertDialog.Builder(this)
            .setCustomTitle(header)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> reprint(s, bill = true, parchi = true)
                    1 -> reprint(s, bill = true, parchi = false)
                    2 -> reprint(s, bill = false, parchi = true)
                    3 -> confirmVoid(s)
                    4 -> changeDate(s)
                    5 -> changePayment(s)
                }
            }
            .show()
    }

    private fun reprint(s: Sale, bill: Boolean, parchi: Boolean) {
        // The parchi toggle in Settings governs new sales; an explicit
        // "reprint parchi" should print one even with it switched off.
        val shop = Prefs.shop(this)
        var bytes = ByteArray(0)
        if (bill) bytes += Escpos.bill(shop, s)
        if (parchi) bytes += Escpos.parchi(shop, s)
        Printer.send(this, "reprint token ${s.label}", bytes) { err ->
            toast(if (err == null) getString(R.string.reprint_ok, s.label) else getString(R.string.reprint_failed, err))
        }
    }

    /** For bills that landed on the wrong day. The bill follows to its new day's list. */
    private fun changeDate(s: Sale) {
        val c = Calendar.getInstance().apply { timeInMillis = s.time }
        DatePickerDialog(this, { _, y, m, d ->
            val day = Calendar.getInstance().apply { clear(); set(y, m, d) }
            val moved = Sales.moveToDay(this, s.uid, day.timeInMillis) ?: return@DatePickerDialog
            if (moved.id == s.id) return@DatePickerDialog
            this.day.timeInMillis = day.timeInMillis
            load()
            toast(getString(R.string.date_changed, s.label, SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(day.time), moved.label))
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply {
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }

    private fun confirmVoid(s: Sale) {
        if (s.voided) {
            Sales.setVoided(this, s.uid, false)
            load()
            return
        }
        // A void always needs a reason, kept on the bill, shown in Sales and exported.
        Dialogs.askReason(this, getString(R.string.void_confirm, s.label, MainActivity.rs(s.total)), getString(R.string.void_bill)) { reason ->
            Sales.setVoided(this, s.uid, true, reason)
            load()
        }
    }

    /** Cash, UPI or credit. Moving to credit needs a customer name, since that is the account it goes on. */
    private fun changePayment(s: Sale) {
        val modes = listOf("CASH", "UPI", CREDIT).filter { it != s.payment }
        val names = modes.map {
            when (it) { "CASH" -> getString(R.string.pay_cash); "UPI" -> getString(R.string.pay_upi); else -> getString(R.string.pay_credit) }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.change_payment_title, s.label))
            .setItems(names) { _, which ->
                val to = modes[which]
                if (to == CREDIT && s.customer.isBlank()) {
                    toast(getString(R.string.err_credit_needs_name))
                    return@setItems
                }
                Sales.setPayment(this, s.uid, to)
                load()
                toast(getString(R.string.payment_changed, s.label, names[which]))
            }
            .show()
    }

    /** Plain text, so it pastes into WhatsApp for whoever wasn't at the counter. */
    private fun shareSummary() {
        val live = sales.filterNot { it.voided }
        val shopName = Prefs.shop(this).name.ifBlank { getString(R.string.app_name) }
        val text = buildString {
            append("$shopName — ${dayLabel.text}\n")
            append("Total: ${MainActivity.rs(live.sumOf { it.total })} (${live.size} bills)\n")
            append("Cash: ${MainActivity.rs(live.filter { it.payment == "CASH" }.sumOf { it.total })}\n")
            append("UPI: ${MainActivity.rs(live.filter { it.payment == "UPI" }.sumOf { it.total })}\n")
            val credit = live.filter { it.onCredit }.sumOf { it.total }
            if (credit > 0) append("On credit: ${MainActivity.rs(credit)}\n")
            val discount = live.sumOf { it.discount }
            if (discount > 0) append("Discounts: ${MainActivity.rs(discount)}\n")
            if (live.isNotEmpty()) {
                append("\n")
                itemTotals(live).forEach { (name, qty) -> append("$qty × $name\n") }
            }
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, getString(R.string.btn_share_summary)))
    }

    /**
     * Through the system's Save dialog: it lands wherever the shop wants
     * (Downloads, Drive) with no storage permission and no FileProvider.
     */
    private fun export(csv: String, name: String) {
        pendingCsv = csv
        val pick = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_TITLE, name)
        try {
            startActivityForResult(pick, REQ_EXPORT)
        } catch (_: Exception) {
            toast(getString(R.string.export_no_picker))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_EXPORT) return
        val csv = pendingCsv
        pendingCsv = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null || csv == null) return
        try {
            contentResolver.openOutputStream(uri)!!.use { it.write(csv.toByteArray()) }
            toast(getString(R.string.export_ok))
        } catch (e: Exception) {
            toast(getString(R.string.export_failed, e.message ?: ""))
        }
    }

    private fun isToday(): Boolean {
        val now = Calendar.getInstance()
        return now.get(Calendar.YEAR) == day.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == day.get(Calendar.DAY_OF_YEAR)
    }

    private fun fileDay() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(day.time)

    private fun text(s: String, size: Float, bold: Boolean = false, colour: Int = R.color.ink) = TextView(this).apply {
        text = s
        textSize = size
        maxLines = 2
        setTextColor(getColor(colour))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_EXPORT = 7
    }
}
