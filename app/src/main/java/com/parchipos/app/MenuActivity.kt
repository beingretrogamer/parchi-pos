package com.parchipos.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Add, edit, reorder and remove what the till sells. */
class MenuActivity : ParchiActivity() {

    private lateinit var itemList: LinearLayout
    private lateinit var noItems: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu)
        itemList = findViewById(R.id.itemList)
        noItems = findViewById(R.id.noItems)
        findViewById<Button>(R.id.btnAdd).setOnClickListener { asManager { edit(null) } }
        findViewById<Button>(R.id.btnPaste).setOnClickListener { asManager { paste() } }
        findViewById<Button>(R.id.btnReset).setOnClickListener { confirmReset() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private val connected: Boolean get() = Cloud.connected(this)

    /**
     * Connected, the menu is the account's: anyone sees it, owners and
     * managers edit it after unlocking, and each edit goes up signed with
     * their PIN. A device of its own edits freely, as before.
     */
    private val readOnly: Boolean get() = connected && Manager.current(this) == null

    /** Runs [action] as the unlocked owner or manager, asking for a PIN if the unlock has run out. */
    private fun asManager(action: () -> Unit) {
        if (connected) Manager.unlock(this) { action(); render() } else action()
    }

    private fun render() {
        val readOnly = readOnly // once per render: it reads the staff list
        val items = Menu.all(this)
        noItems.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        itemList.removeAllViews()
        for (id in listOf(R.id.btnAdd, R.id.btnPaste)) findViewById<View>(id).visibility = if (readOnly) View.GONE else View.VISIBLE
        // The built-in board would replace the whole account's menu: only for a device of its own.
        findViewById<View>(R.id.btnReset).visibility = if (connected) View.GONE else View.VISIBLE
        if (connected) itemList.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(12))
            addView(TextView(this@MenuActivity).apply {
                text = if (readOnly) getString(R.string.menu_from_cloud_locked) else getString(R.string.menu_unlocked, Manager.unlockedName())
                textSize = 13f
                setTextColor(getColor(R.color.accent))
            })
            if (readOnly && Manager.available(this@MenuActivity)) addView(Button(this@MenuActivity).apply {
                text = getString(R.string.btn_unlock_to_edit)
                isAllCaps = false
                setTextColor(getColor(R.color.ink))
                setBackgroundResource(R.drawable.btn_secondary)
                setOnClickListener { Manager.unlock(this@MenuActivity) { render() } }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)).apply { topMargin = dp(8) })
        })
        items.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setBackgroundResource(R.drawable.row_bg)
                if (!readOnly) setOnClickListener { asManager { edit(item) } }
            }
            val label = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            label.addView(TextView(this).apply {
                text = item.name
                textSize = 16f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(getColor(R.color.ink))
            })
            label.addView(TextView(this).apply {
                text = listOf(if (item.soldOut) getString(R.string.sold_out) else "", Menu.formatPrice(item), item.category).filter { it.isNotBlank() }.joinToString("  ·  ")
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                textSize = 13f
                setTextColor(getColor(R.color.ink_soft))
            })
            row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            // Sold out for this outlet: anyone, or a PIN, as the till rules say.
            if (connected) row.addView(TextView(this).apply {
                text = getString(R.string.btn_sold_out_toggle)
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(10), 0, dp(10), 0)
                setTextColor(getColor(if (item.soldOut) R.color.on_accent else R.color.ink_soft))
                setBackgroundResource(if (item.soldOut) R.drawable.chip_selected else R.drawable.chip)
                setOnClickListener {
                    Manager.approve(this@MenuActivity, Rules.SOLD_OUT) { approval ->
                        Menu.setSoldOut(this@MenuActivity, item.id, !item.soldOut, approval)
                        SyncEngine.kick(this@MenuActivity)
                        render()
                    }
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(32)).apply { marginEnd = dp(4) })
            // Items move within their category, which is how the till lists them.
            if (!readOnly) {
                row.addView(arrow("↑", enabled = Menu.canMove(items, item, -1)) { asManager { Menu.move(this, item.id, -1) }; render() })
                row.addView(arrow("↓", enabled = Menu.canMove(items, item, 1)) { asManager { Menu.move(this, item.id, 1) }; render() })
            }
            itemList.addView(row)
        }
    }

    private fun arrow(label: String, enabled: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(getColor(R.color.ink_soft))
        alpha = if (enabled) 1f else 0.25f
        isEnabled = enabled
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
    }

    private fun edit(existing: MenuItem?) {
        val form = layoutInflater.inflate(R.layout.dialog_item, null)
        val name = form.findViewById<EditText>(R.id.itemName)
        val price = form.findViewById<EditText>(R.id.itemPrice)
        val category = form.findViewById<AutoCompleteTextView>(R.id.itemCategory)
        category.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, Menu.categories(this)))
        if (existing != null) {
            name.setText(existing.name)
            price.setText(Menu.formatPrice(existing))
            category.setText(existing.category)
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.btn_add_item else R.string.edit_item)
            .setView(form)
            .setPositiveButton(R.string.btn_save, null)
            .setNegativeButton(android.R.string.cancel, null)
        if (existing != null) {
            builder.setNeutralButton(R.string.btn_delete) { _, _ -> confirmDelete(existing) }
        }
        val dialog = builder.create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()

        // Wired after show() so a bad price keeps the dialog open instead of
        // throwing away what was typed.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val n = name.text.toString().trim()
            val p = Menu.parsePrice(price.text.toString())
            when {
                n.isEmpty() -> name.error = getString(R.string.err_name)
                p == null -> price.error = getString(R.string.err_price_sizes)
                else -> {
                    dialog.dismiss()
                    asManager { Menu.upsert(this, MenuItem(existing?.id ?: Menu.newId(), n, p.first, category.text.toString().trim(), p.second)) }
                    render()
                }
            }
        }
        name.requestFocus()
    }

    private fun confirmDelete(item: MenuItem) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_confirm, item.name))
            .setPositiveButton(R.string.btn_delete) { _, _ -> asManager { Menu.delete(this, item.id) }; render() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Bulk add from text, one item per line — see Menu's text format. Typing
     * forty items one dialog at a time on a phone is what makes people give up
     * on setting a till up.
     */
    private fun paste() {
        val form = layoutInflater.inflate(R.layout.dialog_paste, null)
        val input = form.findViewById<EditText>(R.id.pasteText)
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_paste_list)
            .setView(form)
            .setPositiveButton(R.string.btn_add) { _, _ ->
                val parsed = Menu.parseLines(input.text.toString())
                asManager { Menu.addAll(this, parsed.items) }
                render()
                val added = parsed.items.size
                val msg = if (parsed.skipped == 0) getString(R.string.paste_added, added)
                    else getString(R.string.paste_added_skipped, added, parsed.skipped)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setMessage(R.string.reset_confirm)
            .setPositiveButton(R.string.btn_reset_menu) { _, _ -> Menu.resetToDefault(this); render() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
