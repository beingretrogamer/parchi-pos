package com.parchipos.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Credit (udhaar) accounts: who owes how much, each customer's statement, and
 * taking payment against it.
 *
 * Opened without [EXTRA_CUSTOMER] it lists every account. With one it is that
 * customer's statement.
 */
class LedgerActivity : Activity() {

    private lateinit var title: TextView
    private lateinit var total: TextView
    private lateinit var sub: TextView
    private lateinit var section: TextView
    private lateinit var empty: TextView
    private lateinit var list: LinearLayout

    /** Null on the list of accounts. */
    private var customer: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ledger)
        title = findViewById(R.id.ledgerTitle)
        total = findViewById(R.id.ledgerTotal)
        sub = findViewById(R.id.ledgerSub)
        section = findViewById(R.id.ledgerSection)
        empty = findViewById(R.id.ledgerEmpty)
        list = findViewById(R.id.ledgerList)
        customer = intent.getStringExtra(EXTRA_CUSTOMER)
        findViewById<View>(R.id.ledgerActions).visibility = if (customer == null) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.btnReceive).setOnClickListener { receive() }
        findViewById<Button>(R.id.btnShareStatement).setOnClickListener { shareStatement() }
    }

    /** A bill put on credit at the till, or voided in Sales, changes what is owed. */
    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        list.removeAllViews()
        val name = customer
        if (name == null) loadAccounts() else loadStatement(name)
    }

    private fun loadAccounts() {
        title.setText(R.string.title_ledger)
        section.setText(R.string.section_accounts)
        val accounts = Ledger.accounts(this)
        val owing = accounts.filter { it.balance > 0.005 }
        total.text = MainActivity.rs(owing.sumOf { it.balance })
        sub.text = getString(R.string.ledger_owing, owing.size)
        empty.setText(R.string.no_accounts)
        empty.visibility = if (accounts.isEmpty()) View.VISIBLE else View.GONE
        accounts.forEach { a ->
            val settled = a.balance <= 0.005
            val detail = listOf(
                getString(R.string.n_credit_bills, a.bills),
                if (a.lastTime > 0) SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(a.lastTime)) else "",
                if (a.balance < -0.005) getString(R.string.advance, MainActivity.rs(-a.balance)) else "",
            ).filter { it.isNotBlank() }.joinToString("  ·  ")
            list.addView(row(a.name, detail, if (settled) getString(R.string.settled) else MainActivity.rs(a.balance), faint = settled) {
                startActivity(Intent(this, LedgerActivity::class.java).putExtra(EXTRA_CUSTOMER, a.name))
            })
        }
    }

    private fun loadStatement(name: String) {
        title.text = name
        section.setText(R.string.section_statement)
        val entries = Ledger.statement(this, name)
        val bal = entries.lastOrNull()?.balance ?: 0.0
        total.text = if (bal < -0.005) getString(R.string.advance, MainActivity.rs(-bal)) else MainActivity.rs(maxOf(0.0, bal))
        val billed = entries.sumOf { it.debit }
        val received = entries.sumOf { it.credit }
        sub.text = getString(R.string.ledger_sub, MainActivity.rs(billed), MainActivity.rs(received))
        empty.setText(R.string.no_entries)
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        val fmt = SimpleDateFormat("d MMM yy, HH:mm", Locale.getDefault())
        // Newest first, which is what is looked for; the balance column still runs oldest to newest.
        entries.asReversed().forEach { e ->
            val voided = e.sale?.voided == true || e.receipt?.voided == true
            val amount = if (e.debit > 0) "+" + MainActivity.rs(e.debit)
                else if (e.credit > 0) "−" + MainActivity.rs(e.credit)
                else MainActivity.rs(e.sale?.total ?: e.receipt?.credited ?: 0.0)
            val reason = e.sale?.voidReason?.takeIf { it.isNotBlank() } ?: e.receipt?.voidReason?.takeIf { it.isNotBlank() }
            val detail = listOfNotNull(fmt.format(Date(e.time)), e.receipt?.note?.takeIf { it.isNotBlank() }, reason,
                getString(R.string.balance_after, MainActivity.rs(e.balance))).joinToString("  ·  ")
            list.addView(row(e.label, detail, amount, faint = voided, strike = voided) { entryActions(e) })
        }
    }

    private fun entryActions(e: LedgerEntry) {
        val s = e.sale
        if (s != null) {
            val detail = s.lines.joinToString("\n") { "${it.qty} × ${it.name}   ${MainActivity.rs(it.amount)}" } +
                (if (s.discount > 0) "\n" + getString(R.string.discount_line, MainActivity.rs(s.discount), s.discountReason) else "") +
                "\n\n" + getString(R.string.bill_title, s.label, MainActivity.rs(s.total)) +
                (if (s.voided) "\n" + getString(R.string.voided_because, s.voidReason) else "")
            AlertDialog.Builder(this)
                .setTitle(SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(s.time)))
                .setMessage(detail)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val r = e.receipt ?: return
        val items = arrayOf(
            getString(R.string.print_receipt),
            getString(if (r.voided) R.string.restore_receipt else R.string.void_receipt),
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.receipt_title, MainActivity.rs(r.amount), r.mode))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> printReceipt(r)
                    1 -> if (r.voided) {
                        Ledger.setReceiptVoided(this, r.uid, false, "")
                        load()
                    } else {
                        Dialogs.askReason(this, getString(R.string.void_receipt_confirm, MainActivity.rs(r.credited)), getString(R.string.void_receipt)) { reason ->
                            Ledger.setReceiptVoided(this, r.uid, true, reason)
                            load()
                        }
                    }
                }
            }
            .show()
    }

    /** Settle all or part of the balance. The amount starts at the whole balance. */
    private fun receive() {
        val name = customer ?: return
        val owed = Ledger.balance(this, name)
        val form = layoutInflater.inflate(R.layout.dialog_receipt, null)
        val amount = form.findViewById<EditText>(R.id.rcptAmount)
        val discount = form.findViewById<EditText>(R.id.rcptDiscount)
        val mode = form.findViewById<RadioGroup>(R.id.rcptMode)
        val note = form.findViewById<EditText>(R.id.rcptNote)
        if (owed > 0.005) amount.setText(Sales.money(owed).removeSuffix(".00"))
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.receive_title, name, MainActivity.rs(maxOf(0.0, owed))))
            .setView(form)
            .setPositiveButton(R.string.btn_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        amount.requestFocus()
        amount.selectAll()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val a = amount.text.toString().trim().ifEmpty { "0" }.toDoubleOrNull()
            val d = discount.text.toString().trim().ifEmpty { "0" }.toDoubleOrNull()
            when {
                a == null || a < 0 -> amount.error = getString(R.string.err_price)
                d == null || d < 0 -> discount.error = getString(R.string.err_price)
                a + d <= 0 -> amount.error = getString(R.string.err_amount)
                else -> {
                    val at = Cloud.now(this)
                    val r = Receipt(
                        time = at,
                        updatedAt = at,
                        deviceId = Cloud.me(this)?.deviceId,
                        customerId = Customers.ensure(this, name),
                        customer = name,
                        amount = a,
                        discount = d,
                        mode = if (mode.checkedRadioButtonId == R.id.rcptUpi) "UPI" else "CASH",
                        note = note.text.toString().trim(),
                    )
                    try {
                        Ledger.record(this, r)
                    } catch (ex: Exception) {
                        toast(getString(R.string.save_failed_plain, ex.message ?: ""))
                        return@setOnClickListener
                    }
                    dialog.dismiss()
                    load()
                    val left = Ledger.balance(this, name)
                    AlertDialog.Builder(this)
                        .setMessage(getString(R.string.received_ok, MainActivity.rs(a), name,
                            if (left > 0.005) MainActivity.rs(left) else getString(R.string.settled)))
                        .setPositiveButton(R.string.print_receipt) { _, _ -> printReceipt(r) }
                        .setNegativeButton(R.string.btn_done, null)
                        .show()
                }
            }
        }
    }

    private fun printReceipt(r: Receipt) {
        val shop = Prefs.shop(this)
        val after = Ledger.statement(this, r.customer).find { it.receipt?.uid == r.uid }?.balance ?: Ledger.balance(this, r.customer)
        Printer.send(this, Prefs.billPrinter(this), "receipt ${r.customer}", Escpos.receipt(shop, r, after)) { err ->
            toast(if (err == null) getString(R.string.receipt_printed) else getString(R.string.reprint_failed, err))
        }
    }

    /** Plain text for WhatsApp: what was bought on credit, what was paid, what is left. */
    private fun shareStatement() {
        val name = customer ?: return
        val shopName = Prefs.shop(this).name.ifBlank { getString(R.string.app_name) }
        val fmt = SimpleDateFormat("d MMM", Locale.getDefault())
        val entries = Ledger.statement(this, name).filter { it.debit > 0 || it.credit > 0 }
        val text = buildString {
            append("$shopName — account of $name\n\n")
            entries.forEach { e ->
                val amt = if (e.debit > 0) "+${MainActivity.rs(e.debit)}" else "−${MainActivity.rs(e.credit)}"
                append("${fmt.format(Date(e.time))}  ${e.label}  $amt\n")
            }
            val bal = entries.lastOrNull()?.balance ?: 0.0
            append("\nBalance due: ${MainActivity.rs(maxOf(0.0, bal))}\n")
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, getString(R.string.btn_share_statement)))
    }

    private fun row(left: String, detail: String, right: String, faint: Boolean = false, strike: Boolean = false, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(0, dp(6), 0, dp(6))
            setBackgroundResource(R.drawable.row_bg)
            setOnClickListener { onClick() }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val colour = getColor(if (faint) R.color.ink_faint else R.color.ink)
        val a = TextView(this).apply {
            text = left; textSize = 16f; setTextColor(colour); setTypeface(typeface, Typeface.BOLD)
        }
        val b = TextView(this).apply {
            text = detail; textSize = 13f; setTextColor(getColor(if (faint) R.color.ink_faint else R.color.ink_soft))
        }
        val c = TextView(this).apply {
            text = right; textSize = 16f; gravity = Gravity.END; setTextColor(colour); setTypeface(typeface, Typeface.BOLD)
        }
        if (strike) listOf(a, c).forEach { it.paintFlags = it.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG }
        texts.addView(a)
        if (detail.isNotBlank()) texts.addView(b)
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(c, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(8)
        })
        return row
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_CUSTOMER = "customer"
    }
}
