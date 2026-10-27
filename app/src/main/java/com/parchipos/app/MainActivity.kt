package com.parchipos.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The till: tap items, take payment, and both slips come out.
 *
 * The sale is written to disk before anything is sent to the printer, and the
 * cart clears straight away, so the next customer is never waiting on a slow
 * printer — and a printer that is off never costs a sale, only a reprint.
 */
class MainActivity : Activity() {

    /**
     * A snapshot of the menu item at the moment it was rung up, editable from
     * then on: a price agreed at the counter or a note for the kitchen applies
     * to this bill only and never touches the menu.
     */
    private class CartLine(
        /** Item id plus variant, so Half and Full momos are separate lines. */
        val key: String,
        /** Null for a custom item typed in at the counter. */
        val itemId: String?,
        var name: String,
        var price: Double,
        var qty: Int,
        var note: String = "",
    )

    private lateinit var titleView: TextView
    private lateinit var search: EditText
    private lateinit var categoryScroll: HorizontalScrollView
    private lateinit var categoryBar: LinearLayout
    private lateinit var itemGrid: LinearLayout
    private lateinit var emptyMenu: View
    private lateinit var cartCard: View
    private lateinit var cartTitle: TextView
    private lateinit var cartScroll: ScrollView
    private lateinit var cartLines: LinearLayout
    private lateinit var customer: EditText
    private lateinit var payCash: Button
    private lateinit var payUpi: Button
    private lateinit var btnCharge: Button

    private var menu: List<MenuItem> = emptyList()
    /** Insertion-ordered, so the bill lists items in the order they were rung up. */
    private val cart = LinkedHashMap<String, CartLine>()
    private var category: String? = null
    private var payment = "CASH"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PrintLog.init(this)
        setContentView(R.layout.activity_main)

        titleView = findViewById(R.id.title)
        search = findViewById(R.id.search)
        categoryScroll = findViewById(R.id.categoryScroll)
        categoryBar = findViewById(R.id.categoryBar)
        itemGrid = findViewById(R.id.itemGrid)
        emptyMenu = findViewById(R.id.emptyMenu)
        cartCard = findViewById(R.id.cartCard)
        cartTitle = findViewById(R.id.cartTitle)
        cartScroll = findViewById(R.id.cartScroll)
        cartLines = findViewById(R.id.cartLines)
        customer = findViewById(R.id.customer)
        payCash = findViewById(R.id.payCash)
        payUpi = findViewById(R.id.payUpi)
        btnCharge = findViewById(R.id.btnCharge)

        findViewById<Button>(R.id.btnSales).setOnClickListener { open(SalesActivity::class.java) }
        findViewById<Button>(R.id.btnMenu).setOnClickListener { open(MenuActivity::class.java) }
        findViewById<Button>(R.id.btnAddItems).setOnClickListener { open(MenuActivity::class.java) }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { open(SettingsActivity::class.java) }
        payCash.setOnClickListener { payment = "CASH"; renderPayment() }
        payUpi.setOnClickListener { payment = "UPI"; renderPayment() }
        findViewById<View>(R.id.btnClear).setOnClickListener { clearCart() }
        findViewById<Button>(R.id.btnCustom).setOnClickListener { editLine(null, query()) }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = renderGrid()
        })
        titleView.typeface = Typeface.create("cursive", Typeface.BOLD)
        titleView.textSize = 26f
        titleView.setTextColor(getColor(R.color.accent))
        btnCharge.setOnClickListener { charge(printSlips = true) }
        findViewById<Button>(R.id.btnSave).setOnClickListener { charge(printSlips = false) }
        renderPayment()
    }

    /** Menu and shop name may have changed on another screen. */
    override fun onResume() {
        super.onResume()
        val shopName = Prefs.shop(this).name
        titleView.text = shopName.ifBlank { Wordmark.NAME }

        // The cart is left alone: its lines are snapshots, possibly edited,
        // and a menu change mid-order should not reprice what was agreed.
        menu = Menu.all(this)
        if (category != null && menu.none { it.category == category }) category = null

        renderCategories()
        renderGrid()
        renderCart()
    }

    /** Declared in the manifest so a rotation keeps the cart; only the grid reflows. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        renderGrid()
        renderCart()
    }

    private fun open(cls: Class<*>) = startActivity(Intent(this, cls))

    // ── Rendering ─────────────────────────────────────────────────────

    private fun renderCategories() {
        val cats = menu.map { it.category }.filter { it.isNotBlank() }.distinct()
        categoryScroll.visibility = if (cats.isEmpty()) View.GONE else View.VISIBLE
        categoryBar.removeAllViews()
        (listOf<String?>(null) + cats).forEach { cat ->
            val selected = cat == category
            val chip = TextView(this).apply {
                text = cat ?: getString(R.string.all)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(16), 0, dp(16), 0)
                setTextColor(if (selected) getColor(R.color.on_accent) else getColor(R.color.ink))
                setBackgroundResource(if (selected) R.drawable.chip_selected else R.drawable.chip)
                setOnClickListener { category = cat; renderCategories(); renderGrid() }
            }
            categoryBar.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                marginEnd = dp(8)
            })
        }
    }

    private fun renderGrid() {
        itemGrid.removeAllViews()
        emptyMenu.visibility = if (menu.isEmpty()) View.VISIBLE else View.GONE
        // A search looks across the whole menu, whatever category is picked.
        val q = query()
        val shown = if (q.isNotEmpty()) menu.filter { it.name.contains(q, ignoreCase = true) }
            else menu.filter { category == null || it.category == category }

        // Roughly 110dp a tile: three across on a phone, more on a tablet.
        val usableDp = resources.configuration.screenWidthDp - 2 * resources.getDimension(R.dimen.side_padding) / resources.displayMetrics.density
        val cols = (usableDp / 110).toInt().coerceIn(2, 8)

        // The custom tile rides at the end of every view, so it is always one tap away.
        val tiles: List<MenuItem?> = shown + listOf<MenuItem?>(null)
        tiles.chunked(cols).forEach { rowItems ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until cols) {
                // MATCH_PARENT in a wrap-content row: every tile takes the
                // height of the tallest, so long names grow the row instead
                // of being cut off.
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (i > 0) marginStart = dp(8)
                }
                val view = when {
                    i >= rowItems.size -> View(this)
                    rowItems[i] == null -> customTile(q)
                    else -> tile(rowItems[i]!!)
                }
                row.addView(view, lp)
            }
            itemGrid.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
        }
    }

    private fun tile(item: MenuItem): View {
        val qty = cart.values.filter { it.itemId == item.id }.sumOf { it.qty }
        val prices = item.variants.map { it.price }.ifEmpty { listOf(item.price) }
        val priceText = if (prices.min() == prices.max()) rs(prices.min()) else "${rs(prices.min())} – ${rs(prices.max())}"

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(84)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        body.addView(TextView(this).apply {
            text = item.name
            textSize = 14f
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(getColor(R.color.ink))
            // Room for the badge, so a count never sits on the name.
            if (qty > 0) setPadding(0, 0, dp(18), 0)
        })
        body.addView(TextView(this).apply {
            text = priceText
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.accent))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })

        return FrameLayout(this).apply {
            setBackgroundResource(if (qty > 0) R.drawable.tile_selected else R.drawable.tile)
            setOnClickListener { pick(item) }
            addView(body, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            if (qty > 0) addView(TextView(this@MainActivity).apply {
                text = qty.toString()
                textSize = 12f
                gravity = Gravity.CENTER
                minWidth = dp(22)
                setPadding(dp(6), 0, dp(6), 0)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(getColor(R.color.on_accent))
                setBackgroundResource(R.drawable.badge)
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(22), Gravity.TOP or Gravity.END).apply {
                setMargins(0, dp(6), dp(6), 0)
            })
        }
    }

    /** Always at the end of the grid; during a search it offers to ring up what was typed. */
    private fun customTile(q: String): View = TextView(this).apply {
        text = if (q.isEmpty()) getString(R.string.custom_item) else getString(R.string.custom_item_named, q)
        textSize = 14f
        gravity = Gravity.CENTER
        minHeight = dp(84)
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setTextColor(getColor(R.color.accent))
        setBackgroundResource(R.drawable.tile_custom)
        setOnClickListener { editLine(null, q) }
    }

    /** Single-price items go straight in; the rest ask which size or flavour. */
    private fun pick(item: MenuItem) {
        if (item.variants.isEmpty()) {
            addLine(item.id, item.id, item.name, item.price)
            return
        }
        val labels = item.variants.map { "${it.label}   ${rs(it.price)}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(labels) { _, i ->
                val v = item.variants[i]
                addLine(item.id + "|" + v.label, item.id, "${item.name} (${v.label})", v.price)
            }
            .show()
    }

    private fun renderCart() {
        cartLines.removeAllViews()
        cartCard.visibility = if (cart.isEmpty()) View.GONE else View.VISIBLE
        if (cart.isEmpty()) return

        for (line in cart.values) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            // Tapping the name opens the line for editing; the second line
            // shows the rate and any note, so an edit is visible at a glance.
            val label = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.row_bg_plain)
                setOnClickListener { editLine(line) }
            }
            label.addView(TextView(this).apply {
                text = line.name
                textSize = 15f
                singleLine()
                setTextColor(getColor(R.color.ink))
            })
            label.addView(TextView(this).apply {
                text = listOf("@ " + rs(line.price), line.note).filter { it.isNotBlank() }.joinToString("  ·  ")
                textSize = 12f
                singleLine()
                setTextColor(getColor(if (line.note.isBlank()) R.color.ink_faint else R.color.accent))
            })
            row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(stepper("−") { step(line, -1) }, LinearLayout.LayoutParams(dp(40), dp(40)))
            row.addView(TextView(this).apply {
                text = line.qty.toString()
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.ink))
            }, LinearLayout.LayoutParams(dp(36), LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(stepper("+") { step(line, 1) }, LinearLayout.LayoutParams(dp(40), dp(40)))
            row.addView(TextView(this).apply {
                text = rs(line.price * line.qty)
                textSize = 15f
                gravity = Gravity.END
                setTextColor(getColor(R.color.ink))
            }, LinearLayout.LayoutParams(dp(76), LinearLayout.LayoutParams.WRAP_CONTENT))
            cartLines.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ROW_DP)))
        }

        // Grow with the cart up to a few rows, then scroll — past that the
        // menu above would be squeezed to nothing on a phone.
        val maxRows = if (resources.configuration.screenHeightDp < 700) 3.5f else 5.5f
        cartScroll.layoutParams = cartScroll.layoutParams.apply {
            height = dp((minOf(cart.size.toFloat(), maxRows) * ROW_DP).toInt())
        }

        val total = cart.values.sumOf { it.price * it.qty }
        val count = cart.values.sumOf { it.qty }
        cartTitle.text = getString(R.string.cart_title, count)
        btnCharge.text = getString(R.string.btn_charge, count, rs(total))
    }

    private fun stepper(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 18f
        setPadding(0, 0, 0, 0)
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        isAllCaps = false
        setTextColor(getColor(R.color.ink))
        setBackgroundResource(R.drawable.btn_secondary)
        setOnClickListener { onClick() }
    }

    private fun renderPayment() {
        listOf(payCash to "CASH", payUpi to "UPI").forEach { (b, mode) ->
            val on = payment == mode
            b.setBackgroundResource(if (on) R.drawable.btn_primary else R.drawable.btn_secondary)
            b.setTextColor(if (on) getColor(R.color.on_accent) else getColor(R.color.ink))
        }
    }

    // ── Actions ───────────────────────────────────────────────────────

    private fun addLine(key: String, itemId: String?, name: String, price: Double) {
        cart.getOrPut(key) { CartLine(key, itemId, name, price, 0) }.qty += 1
        renderGrid()
        renderCart()
    }

    private fun step(line: CartLine, delta: Int) {
        line.qty += delta
        if (line.qty <= 0) cart.remove(line.key)
        renderGrid()
        renderCart()
    }

    /**
     * Edit a line of the bill being rung up — name, rate, quantity, a note for
     * the kitchen — or, with null, add something that is not on the menu.
     */
    private fun editLine(line: CartLine?, prefill: String = "") {
        val form = layoutInflater.inflate(R.layout.dialog_line, null)
        val name = form.findViewById<EditText>(R.id.lineName)
        val price = form.findViewById<EditText>(R.id.linePrice)
        val qty = form.findViewById<EditText>(R.id.lineQty)
        val note = form.findViewById<EditText>(R.id.lineNote)
        name.setText(line?.name ?: prefill)
        price.setText(line?.let { Sales.money(it.price).removeSuffix(".00") } ?: "")
        qty.setText((line?.qty ?: 1).toString())
        note.setText(line?.note ?: "")

        val builder = AlertDialog.Builder(this)
            .setTitle(if (line == null) R.string.custom_item_title else R.string.edit_line)
            .setView(form)
            .setPositiveButton(R.string.btn_save, null)
            .setNegativeButton(android.R.string.cancel, null)
        if (line != null) builder.setNeutralButton(R.string.btn_remove) { _, _ -> step(line, -line.qty) }
        val dialog = builder.create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()

        // Wired after show() so a bad value keeps the dialog open with what was typed.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val n = name.text.toString().trim()
            val p = price.text.toString().trim().toDoubleOrNull()
            val q = qty.text.toString().trim().toIntOrNull()
            when {
                n.isEmpty() -> name.error = getString(R.string.err_name)
                p == null || p < 0 -> price.error = getString(R.string.err_price)
                q == null || q < 0 -> qty.error = getString(R.string.err_qty)
                else -> {
                    val target = line ?: CartLine("custom-" + Menu.newId(), null, n, p, 0).also { cart[it.key] = it }
                    target.name = n
                    target.price = p
                    target.qty = q
                    target.note = note.text.toString().trim()
                    if (q == 0) cart.remove(target.key)
                    dialog.dismiss()
                    renderGrid()
                    renderCart()
                }
            }
        }
        // A custom item named from the search box only needs its price.
        (if (line == null && prefill.isEmpty()) name else price).requestFocus()
    }

    private fun clearCart() {
        cart.clear()
        customer.setText("")
        search.setText("")
        payment = "CASH"
        renderPayment()
        renderGrid()
        renderCart()
    }

    /** `printSlips = false` records the sale and skips both slips; Sales can print it later. */
    private fun charge(printSlips: Boolean) {
        if (cart.isEmpty()) return
        val now = System.currentTimeMillis()
        val token = Prefs.nextToken(this)
        val sale = Sale(
            id = SimpleDateFormat("yyMMdd", Locale.US).format(Date(now)) + "-" + token.toString().padStart(3, '0'),
            token = token,
            time = now,
            lines = cart.values.map { SaleLine(it.name, it.price, it.qty, it.note) },
            payment = payment,
            customer = customer.text.toString().trim(),
        )
        try {
            Sales.record(this, sale)
        } catch (e: Exception) {
            // Nothing printed and the cart is untouched, so the sale can simply be retried.
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.save_failed, e.message ?: ""))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        clearCart()
        if (!printSlips) {
            Toast.makeText(this, getString(R.string.toast_sale_saved, token), Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, getString(R.string.toast_printing, token), Toast.LENGTH_SHORT).show()
        print(sale)
    }

    private fun print(sale: Sale) {
        Printer.send(this, "token ${sale.token}", Printer.saleBytes(this, sale)) { err ->
            if (err == null || isFinishing) return@send
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.print_failed_title, sale.token))
                .setMessage(getString(R.string.print_failed_body, err))
                .setPositiveButton(R.string.btn_retry) { _, _ -> print(sale) }
                .setNegativeButton(R.string.btn_later, null)
                .show()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun query(): String = search.text.toString().trim()

    private fun TextView.singleLine() {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val ROW_DP = 48

        /** Whole rupees without the ".00" — the screen is read at a glance. */
        fun rs(n: Double): String =
            if (n == Math.floor(n)) "₹" + n.toLong() else "₹" + String.format(Locale.US, "%.2f", n)
    }
}
