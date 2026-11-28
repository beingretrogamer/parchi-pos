package com.parchipos.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * The optional app lock (a till rule the owner switches on in the portal).
 *
 * A connected device asks for a PIN when Parchi is opened and after it has
 * sat unused for the set minutes. The outlet's staff PIN opens it, as does
 * any owner's or manager's own PIN. Checked offline; wrong PINs lock out like
 * the manager pad. A device of its own never locks.
 */
object AppLock {
    /** Last tap anywhere in the app; 0 on a fresh start, so opening the app asks. */
    @Volatile private var lastUse = 0L

    fun locked(c: Context): Boolean {
        if (!Rules.appLock(c)) return false
        return lastUse == 0L || System.currentTimeMillis() - lastUse > Rules.appLockIdleMs(c)
    }

    fun touch() {
        lastUse = System.currentTimeMillis()
    }

    internal fun opened() = touch()

    /** For tests: as if the app had just been started. */
    internal fun reset() {
        lastUse = 0L
    }
}

/**
 * Every Parchi screen: taps keep the manager unlock and the app lock's idle
 * clock going, and a screen coming up while the app is locked shows the lock
 * first.
 */
abstract class ParchiActivity : Activity() {
    private val lockCheck = Handler(Looper.getMainLooper())
    private val checkLock = object : Runnable {
        override fun run() {
            if (AppLock.locked(this@ParchiActivity)) showLock()
            else lockCheck.postDelayed(this, 15_000)
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        Manager.touch()
        AppLock.touch()
    }

    override fun onResume() {
        super.onResume()
        lockCheck.removeCallbacks(checkLock)
        checkLock.run()
    }

    override fun onPause() {
        super.onPause()
        lockCheck.removeCallbacks(checkLock)
    }

    private fun showLock() {
        startActivity(Intent(this, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}

/** The lock screen. Back leaves the app; it can't be dismissed without a PIN. */
class LockActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var unlock: Button
    private var checking = false
    /** The owner may switch the lock off in the portal: the next sync opens the app. */
    private val cloudChanged: () -> Unit = { if (!isFinishing && !AppLock.locked(this)) finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(28)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, dp(72), pad, pad)
        }
        col.addView(TextView(this).apply {
            text = Prefs.shop(this@LockActivity).name.ifBlank { Wordmark.NAME }
            typeface = Typeface.create("cursive", Typeface.BOLD)
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.accent))
        })
        col.addView(TextView(this).apply {
            text = getString(R.string.lock_title)
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.ink_soft))
            setPadding(0, dp(6), 0, dp(32))
        })
        input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.hint_lock_pin)
            textSize = 26f
            gravity = Gravity.CENTER
            letterSpacing = 0.3f
            imeOptions = EditorInfo.IME_ACTION_DONE
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
            setOnEditorActionListener { _, id, e ->
                if (id == EditorInfo.IME_ACTION_DONE || e?.keyCode == KeyEvent.KEYCODE_ENTER) { submit(); true } else false
            }
        }
        col.addView(input, LinearLayout.LayoutParams(dp(260), LinearLayout.LayoutParams.WRAP_CONTENT))
        status = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.danger))
            setPadding(0, dp(10), 0, dp(10))
        }
        col.addView(status)
        unlock = Button(this).apply {
            text = getString(R.string.btn_unlock)
            isAllCaps = false
            textSize = 16f
            setTextColor(getColor(R.color.on_accent))
            setBackgroundResource(R.drawable.btn_primary)
            setOnClickListener { submit() }
        }
        col.addView(unlock, LinearLayout.LayoutParams(dp(260), dp(52)))
        col.addView(TextView(this).apply {
            text = getString(if (Manager.hasStaffPin(this@LockActivity)) R.string.lock_hint else R.string.lock_hint_no_staff_pin)
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.ink_faint))
            setPadding(0, dp(28), 0, 0)
        }, LinearLayout.LayoutParams(dp(300), LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.bg))
            isFillViewport = true
            addView(col)
        })
    }

    override fun onResume() {
        super.onResume()
        if (!AppLock.locked(this)) { finish(); return }
        SyncEngine.listen(cloudChanged)
        tick.run()
        input.requestFocus()
    }

    override fun onPause() {
        super.onPause()
        SyncEngine.unlisten(cloudChanged)
        ui.removeCallbacks(tick)
    }

    /** Shows the lockout's countdown after too many wrong PINs. */
    private val tick = object : Runnable {
        override fun run() {
            val ms = Manager.lockedOutMs(this@LockActivity)
            if (ms > 0) {
                status.text = getString(R.string.pin_locked_out, (ms + 999) / 1000)
                input.isEnabled = false
                unlock.isEnabled = false
                ui.postDelayed(this, 1000)
            } else if (!unlock.isEnabled && !checking) {
                status.text = ""
                input.isEnabled = true
                unlock.isEnabled = true
            }
        }
    }

    private fun submit() {
        val pin = input.text.toString()
        if (!Pin.valid(pin)) { status.text = getString(R.string.pin_digits); return }
        input.isEnabled = false
        unlock.isEnabled = false
        status.setTextColor(getColor(R.color.ink_soft))
        status.text = getString(R.string.pin_checking)
        checking = true
        thread(name = "parchi-lock") {
            val result = Manager.check(this, pin, Manager.MANAGERS, withStaffPin = true)
            ui.post {
                checking = false
                if (isFinishing) return@post
                status.setTextColor(getColor(R.color.danger))
                input.setText("")
                when (result) {
                    is Manager.Check.Ok -> {
                        AppLock.opened()
                        finish()
                        @Suppress("DEPRECATION")
                        overridePendingTransition(0, android.R.anim.fade_out)
                    }
                    is Manager.Check.Wrong -> {
                        status.text = getString(R.string.pin_wrong_left, result.triesLeft)
                        input.isEnabled = true
                        unlock.isEnabled = true
                        input.requestFocus()
                    }
                    is Manager.Check.LockedOut -> {
                        status.text = ""
                        tick.run()
                    }
                }
            }
        }
    }

    /** Back leaves Parchi rather than getting past the lock. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
