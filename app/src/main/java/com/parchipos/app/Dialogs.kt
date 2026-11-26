package com.parchipos.app

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout

object Dialogs {
    /**
     * Asks for a reason before something that cannot pass unexplained, such as
     * a void. The OK button does nothing until a reason is typed.
     */
    fun askReason(a: Activity, message: String, okLabel: String, onOk: (String) -> Unit) {
        val field = EditText(a).apply {
            hint = a.getString(R.string.hint_reason)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
        }
        val pad = (24 * a.resources.displayMetrics.density).toInt()
        val box = LinearLayout(a).apply {
            setPadding(pad, pad / 3, pad, 0)
            addView(field, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(a)
            .setMessage(message)
            .setView(box)
            .setPositiveButton(okLabel, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        field.requestFocus()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val reason = field.text.toString().trim()
            if (reason.length < 3) {
                field.error = a.getString(R.string.err_reason)
                return@setOnClickListener
            }
            dialog.dismiss()
            onOk(reason)
        }
    }
}
