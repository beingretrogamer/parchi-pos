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
class MenuActivity : Activity() {

    private lateinit var itemList: LinearLayout
    private lateinit var noItems: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu)
        itemList = findViewById(R.id.itemList)
        noItems = findViewById(R.id.noItems)
        findViewById<Button>(R.id.btnAdd).setOnClickListener { edit(null) }
        findViewById<Button>(R.id.btnPaste).setOnClickListener { paste() }
        findViewById<Button>(R.id.btnReset).setOnClickListener { confirmReset() }
        render()
    }

    private fun render() {
        val items = Menu.all(this)
        noItems.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        itemList.removeAllViews()
        items.forEachIndexed { i, item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setBackgroundResource(R.drawable.row_bg)
                setOnClickListener { edit(item) }
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
                text = listOf(Menu.formatPrice(item), item.category).filter { it.isNotBlank() }.joinToString("  ·  ")
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                textSize = 13f
                setTextColor(getColor(R.color.ink_soft))
            })
            row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(arrow("↑", enabled = i > 0) { Menu.move(this, item.id, -1); render() })
            row.addView(arrow("↓", enabled = i < items.size - 1) { Menu.move(this, item.id, 1); render() })
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
                    Menu.upsert(this, MenuItem(existing?.id ?: Menu.newId(), n, p.first, category.text.toString().trim(), p.second))
                    dialog.dismiss()
                    render()
                }
            }
        }
        name.requestFocus()
    }

    private fun confirmDelete(item: MenuItem) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_confirm, item.name))
            .setPositiveButton(R.string.btn_delete) { _, _ -> Menu.delete(this, item.id); render() }
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
                Menu.addAll(this, parsed.items)
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
