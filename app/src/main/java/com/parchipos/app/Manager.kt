package com.parchipos.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Owners' and managers' PINs on a connected device, checked offline.
 *
 * - **Unlock** opens the edit screens (menu, bill header) for 5 minutes from
 *   the last tap, until Lock, or until the app goes to the background.
 * - **Approve** lets one restricted action through at the till, as the till
 *   rules say. The approval goes on the bill. An unlocked manager approves
 *   without being asked again.
 * - Five wrong PINs in a row lock the pad for a minute, across restarts.
 *
 * Edits that the server only takes from a manager (menu, bill header, sold
 * out when the rule says so) are signed with the PIN's key, which is kept in
 * memory only, never written down.
 */
object Manager {
    const val UNLOCK_MS = 5 * 60_000L
    const val MAX_TRIES = 5
    const val LOCKOUT_MS = 60_000L
    val MANAGERS = setOf("owner", "manager")
    val OWNERS = setOf("owner")

    class Session(val staff: CloudSetup.Staff, val key: String, @Volatile var until: Long)

    /**
     * One restricted action, approved. The key stays in memory until the
     * bill exists, so the proof can name it: proof = HMAC(key, "approval:<action>|<bill uid>|<at>").
     */
    class Approval(val action: String, val staff: CloudSetup.Staff, val key: String, val at: Long) {
        fun toJson(billUid: String): JSONObject = JSONObject()
            .put("action", action).put("userId", staff.userId).put("name", staff.name).put("at", at)
            .put("proof", Pin.proof(key, "approval:$action", billUid, at))
    }

    @Volatile private var session: Session? = null

    /** Whoever is unlocked, if their time hasn't run out and their PIN still works here. */
    fun current(c: Context): Session? {
        val s = session ?: return null
        val stillHere = CloudSetup.staff(c).any { it.userId == s.staff.userId && it.verifier == s.staff.verifier }
        if (System.currentTimeMillis() > s.until || !Cloud.connected(c) || !stillHere) {
            session = null
            return null
        }
        return s
    }

    /** Milliseconds left on the unlock, without checking the staff list (for a ticking label). */
    fun remainingMs(): Long = session?.let { maxOf(0L, it.until - System.currentTimeMillis()) } ?: 0L

    fun unlockedName(): String? = session?.takeIf { it.until > System.currentTimeMillis() }?.staff?.name

    /** Any tap keeps an unlock going. */
    fun touch() {
        session?.let { if (it.until > System.currentTimeMillis()) it.until = System.currentTimeMillis() + UNLOCK_MS }
    }

    fun lock() {
        session = null
    }

    /** Connected, with at least one owner or manager PIN that works on this device. */
    fun available(c: Context): Boolean = Cloud.connected(c) && CloudSetup.staff(c).isNotEmpty()

    /**
     * A restricted action at the till. [onOk] runs at once with null when the
     * till rules allow it, with the unlocked manager's approval when someone
     * is unlocked, and otherwise after a correct PIN.
     */
    fun approve(a: Activity, rule: String, onOk: (Approval?) -> Unit) {
        if (!Rules.needsPin(a, rule)) { onOk(null); return }
        current(a)?.let { touch(); onOk(Approval(rule, it.staff, it.key, Cloud.now(a))); return }
        askPin(a, a.getString(R.string.title_needs_manager), a.getString(R.string.needs_manager_body, actionName(a, rule)), MANAGERS) { staff, key ->
            onOk(Approval(rule, staff, key, Cloud.now(a)))
        }
    }

    /** Unlocks the edit screens for [roles]: at once if someone with that role already is. */
    fun unlock(a: Activity, roles: Set<String> = MANAGERS, onOk: (Session) -> Unit) {
        current(a)?.takeIf { it.staff.role in roles }?.let { touch(); onOk(it); return }
        val title = a.getString(if (roles == OWNERS) R.string.title_owner_pin else R.string.title_pin)
        askPin(a, title, null, roles) { staff, key -> onOk(begin(staff, key)) }
    }

    /** Starts an unlock for [staff], whose PIN gave [key]. */
    internal fun begin(staff: CloudSetup.Staff, key: String): Session =
        Session(staff, key, System.currentTimeMillis() + UNLOCK_MS).also { session = it }

    /**
     * A record edit as the server wants it from a device: signed by whoever
     * is unlocked. Unchanged when nobody is, and on a device of its own.
     */
    fun signed(c: Context, rec: Store.Rec): Store.Rec {
        if (!rec.dirty || !Cloud.connected(c)) return rec
        val s = current(c) ?: return rec
        return CloudSetup.sign(c, rec, s.staff, s.key)
    }

    /** Signed by the person who approved it, if anyone did. */
    fun signedBy(c: Context, rec: Store.Rec, approval: Approval?): Store.Rec =
        if (approval == null || !Cloud.connected(c)) rec else CloudSetup.sign(c, rec, approval.staff, approval.key)

    // ── checking a PIN ──────────────────────────────────────────────────

    sealed class Check {
        class Ok(val staff: CloudSetup.Staff, val key: String) : Check()
        class Wrong(val triesLeft: Int) : Check()
        class LockedOut(val ms: Long) : Check()
    }

    /** How long the pad stays locked after too many wrong PINs; 0 when it isn't. */
    fun lockedOutMs(c: Context): Long =
        maxOf(0L, (Store.get(c, "pin.lockedUntil")?.toLongOrNull() ?: 0L) - System.currentTimeMillis())

    /**
     * Checks a PIN against the owners and managers in [roles] and, with
     * [withStaffPin], the outlet's staff PIN for the app lock. Counts wrong
     * tries. Slow on purpose (PBKDF2 per person): call it off the main thread.
     */
    fun check(c: Context, pin: String, roles: Set<String>, withStaffPin: Boolean = false): Check {
        lockedOutMs(c).takeIf { it > 0 }?.let { return Check.LockedOut(it) }
        val hit = (if (withStaffPin) staffPin(c, pin) else null) ?: CloudSetup.checkPin(c, pin, roles)
        if (hit != null) {
            Store.put(c, "pin.failures", null)
            return Check.Ok(hit.first, hit.second)
        }
        val failures = (Store.get(c, "pin.failures")?.toIntOrNull() ?: 0) + 1
        if (failures >= MAX_TRIES) {
            Store.put(c, "pin.failures", null)
            Store.put(c, "pin.lockedUntil", (System.currentTimeMillis() + LOCKOUT_MS).toString())
            return Check.LockedOut(LOCKOUT_MS)
        }
        Store.put(c, "pin.failures", failures.toString())
        return Check.Wrong(MAX_TRIES - failures)
    }

    /** The outlet's staff PIN, set by the owner in the portal; it opens the app lock and nothing else. */
    fun hasStaffPin(c: Context): Boolean = Cloud.outlet(c)?.optStringOrNull("lockPinVerifier") != null

    private fun staffPin(c: Context, pin: String): Pair<CloudSetup.Staff, String>? {
        if (!Pin.valid(pin)) return null
        val o = Cloud.outlet(c) ?: return null
        val salt = o.optStringOrNull("lockPinSalt") ?: return null
        val verifier = o.optStringOrNull("lockPinVerifier") ?: return null
        val key = Pin.matches(pin, salt, verifier) ?: return null
        return CloudSetup.Staff("", c.getString(R.string.staff_pin_name), "staff", salt, verifier) to key
    }

    // ── the PIN pad ─────────────────────────────────────────────────────

    /**
     * Asks for the PIN of someone in [roles] and runs [then] with who it is
     * and their key. Says so when nobody here has a PIN.
     */
    fun askPin(a: Activity, title: String, message: String?, roles: Set<String>, then: (CloudSetup.Staff, String) -> Unit) {
        if (CloudSetup.staff(a).none { it.role in roles }) {
            AlertDialog.Builder(a)
                .setTitle(title)
                .setMessage(if (roles == OWNERS) R.string.pin_none_owner else R.string.pin_none)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val ui = Handler(Looper.getMainLooper())
        val pad = (20 * a.resources.displayMetrics.density).toInt()
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        if (message != null) box.addView(TextView(a).apply {
            text = message
            textSize = 15f
            setTextColor(a.getColor(R.color.ink_soft))
            setPadding(0, 0, 0, pad / 2)
        })
        val input = EditText(a).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = a.getString(R.string.hint_pin)
            textSize = 24f
            gravity = Gravity.CENTER
            letterSpacing = 0.3f
            imeOptions = EditorInfo.IME_ACTION_DONE
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
        }
        box.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val status = TextView(a).apply {
            textSize = 13f
            setTextColor(a.getColor(R.color.danger))
            setPadding(0, pad / 3, 0, 0)
        }
        box.addView(status)

        val dialog = AlertDialog.Builder(a)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(R.string.btn_unlock, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)

        // While locked out: say how long, and keep the button off until then.
        val countdown = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                val ms = lockedOutMs(a)
                if (ms > 0) {
                    status.text = a.getString(R.string.pin_locked_out, (ms + 999) / 1000)
                    input.isEnabled = false
                    ok.isEnabled = false
                    ui.postDelayed(this, 1000)
                } else if (!input.isEnabled) {
                    status.text = ""
                    input.isEnabled = true
                    ok.isEnabled = true
                    input.requestFocus()
                }
            }
        }
        countdown.run()

        fun submit() {
            val pin = input.text.toString()
            if (!Pin.valid(pin)) { status.text = a.getString(R.string.pin_digits); return }
            input.isEnabled = false
            ok.isEnabled = false
            status.setTextColor(a.getColor(R.color.ink_soft))
            status.text = a.getString(R.string.pin_checking)
            thread(name = "parchi-pin") {
                val result = check(a, pin, roles)
                ui.post {
                    if (a.isFinishing || !dialog.isShowing) return@post
                    status.setTextColor(a.getColor(R.color.danger))
                    when (result) {
                        is Check.Ok -> {
                            dialog.dismiss()
                            then(result.staff, result.key)
                        }
                        is Check.Wrong -> {
                            input.setText("")
                            input.isEnabled = true
                            ok.isEnabled = true
                            input.requestFocus()
                            status.text = a.getString(R.string.pin_wrong_left, result.triesLeft)
                        }
                        is Check.LockedOut -> {
                            input.setText("")
                            countdown.run()
                        }
                    }
                }
            }
        }
        ok.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, id, e ->
            if (id == EditorInfo.IME_ACTION_DONE || e?.keyCode == KeyEvent.KEYCODE_ENTER) { submit(); true } else false
        }
        if (input.isEnabled) input.requestFocus()
    }

    /** "Changing a price", for "… needs an owner's or manager's PIN". */
    fun actionName(c: Context, rule: String): String = c.getString(when (rule) {
        Rules.OPEN_ITEMS -> R.string.action_open_items
        Rules.PRICE_EDIT -> R.string.action_price_edit
        Rules.DISCOUNT -> R.string.action_discount
        Rules.BACK_DATE -> R.string.action_back_date
        Rules.VOID_BILL -> R.string.action_void_bill
        Rules.CHANGE_PAYMENT -> R.string.action_change_payment
        Rules.VOID_RECEIPT -> R.string.action_void_receipt
        Rules.SOLD_OUT -> R.string.action_sold_out
        Rules.STAFF_SEES_TOTALS -> R.string.action_totals
        else -> R.string.action_other
    })
}
