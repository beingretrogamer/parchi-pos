package com.parchipos.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Settings → Parchi Cloud: connect this phone with a code, see how sync is
 * going, and make the first connection's choices (this phone's menu and bill
 * header) with a manager's PIN.
 */
class CloudActivity : ParchiActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private val refresh: () -> Unit = { if (!isFinishing) render() }
    private var server = Cloud.DEFAULT_SERVER

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cloud)
        server = Store.get(this, "cloud.server") ?: Cloud.DEFAULT_SERVER

        val code = findViewById<EditText>(R.id.code)
        val name = findViewById<EditText>(R.id.deviceName)
        findViewById<Button>(R.id.btnConnect).setOnClickListener { connect(code, name) }
        findViewById<TextView>(R.id.serverLine).setOnLongClickListener { editServer(); true }
        findViewById<Button>(R.id.btnSyncNow).setOnClickListener {
            findViewById<TextView>(R.id.stateDetail).text = getString(R.string.connecting)
            SyncEngine.kick(this)
        }
        findViewById<Button>(R.id.btnDisconnect).setOnClickListener { confirmDisconnect() }
        findViewById<Button>(R.id.btnRetryRejected).setOnClickListener {
            val me = Cloud.me(this) ?: return@setOnClickListener
            val n = Store.retryRejected(this, me.deviceId)
            toast(getString(R.string.retry_rejected, n))
            findViewById<TextView>(R.id.stateDetail).text = getString(R.string.connecting)
            SyncEngine.kick(this)
        }
    }

    override fun onResume() {
        super.onResume()
        SyncEngine.listen(refresh)
        render()
        SyncEngine.kick(this)
    }

    override fun onPause() {
        super.onPause()
        SyncEngine.unlisten(refresh)
    }

    private fun render() {
        val me = Cloud.me(this)
        findViewById<View>(R.id.connectCard).visibility = if (me == null) View.VISIBLE else View.GONE
        for (id in listOf(R.id.statusCard, R.id.phonesCard, R.id.btnDisconnect)) findViewById<View>(id).visibility = if (me == null) View.GONE else View.VISIBLE
        if (me == null) {
            findViewById<View>(R.id.choiceCard).visibility = View.GONE
            findViewById<View>(R.id.problemsCard).visibility = View.GONE
            val up = CloudSetup.pendingUpload(this)
            findViewById<TextView>(R.id.uploadNote).text = (if (up.bills + up.payments == 0) getString(R.string.connect_nothing)
                else getString(R.string.connect_upload, up.bills, up.payments, dayText(up.firstDay), dayText(up.lastDay))) +
                if (Prefs.syncKey(this).isNotEmpty()) "\n\n" + getString(R.string.connect_stops_icecreamos) else ""
            findViewById<TextView>(R.id.serverLine).text = getString(R.string.server_line, server.removePrefix("https://"))
            return
        }

        val st = Cloud.status(this)
        val stateLine = findViewById<TextView>(R.id.stateLine)
        stateLine.text = when (st.state) {
            Cloud.State.LIVE -> getString(R.string.state_live)
            Cloud.State.WAITING -> getString(R.string.state_waiting, st.outbox)
            Cloud.State.OFFLINE -> getString(R.string.state_offline, st.outbox)
            Cloud.State.ERROR -> getString(R.string.state_error)
            Cloud.State.UPGRADE -> getString(R.string.state_upgrade)
            Cloud.State.REMOVED -> getString(R.string.state_removed)
            Cloud.State.STANDALONE -> ""
        }
        stateLine.setTextColor(getColor(when (st.state) {
            Cloud.State.LIVE -> R.color.ok
            Cloud.State.WAITING, Cloud.State.OFFLINE, Cloud.State.UPGRADE -> R.color.warn
            else -> R.color.danger
        }))
        val lines = mutableListOf(
            getString(R.string.detail_account, me.accountName, me.outletName),
            getString(R.string.detail_phone, me.letter, me.name),
            if (st.lastOk > 0) getString(R.string.detail_last, SimpleDateFormat("d MMM, HH:mm:ss", Locale.getDefault()).format(Date(st.lastOk))) else getString(R.string.detail_never),
        )
        if (st.error.isNotEmpty()) lines += st.error
        if (st.state == Cloud.State.UPGRADE) lines += getString(R.string.detail_upgrade, st.minimum)
        else if (st.latest.isNotEmpty() && Protocol.compareVersions(st.latest, SyncEngine.appVersion(this)) > 0) lines += getString(R.string.detail_latest, st.latest)
        if (Cloud.clockIsOff(this)) lines += getString(R.string.detail_clock, offsetText(Cloud.clockOffset(this)))
        findViewById<TextView>(R.id.stateDetail).text = lines.joinToString("\n")

        renderChoices()

        // Each device with its app version and when it last synced, so an old version stands out.
        val phones = Cloud.phones(this).ifEmpty { listOf(Cloud.Phone(me.deviceId, me.name, me.letter, true, SyncEngine.appVersion(this))) }
        findViewById<TextView>(R.id.phonesList).text = phones.joinToString("\n") { p ->
            val notes = mutableListOf<String>()
            if (p.id == me.deviceId) notes += getString(R.string.phone_you)
            if (!p.active) notes += getString(R.string.phone_removed)
            val version = if (p.id == me.deviceId) SyncEngine.appVersion(this) else p.appVersion
            version?.let { v ->
                notes += getString(R.string.phone_version, v) +
                    if (st.latest.isNotEmpty() && Protocol.compareVersions(st.latest, v) > 0) ", " + getString(R.string.phone_update, st.latest) else ""
            }
            if (p.id != me.deviceId) p.lastSeenAt?.let { notes += getString(R.string.phone_seen, ago(it)) }
            getString(R.string.phone_row, p.letter, p.name) + if (notes.isEmpty()) "" else "  (" + notes.joinToString(", ") + ")"
        }

        val problems = Store.rejected(this)
        findViewById<View>(R.id.problemsCard).visibility = if (problems.isEmpty()) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.problemsList).text = problems.joinToString("\n")
    }

    /** The first connection's two questions, until answered. */
    private fun renderChoices() {
        val list = findViewById<LinearLayout>(R.id.choiceList)
        list.removeAllViews()
        val menu = Store.get(this, "cloud.menuChoice") == "ask"
        val header = Store.get(this, "cloud.headerChoice") == "ask"
        findViewById<View>(R.id.choiceCard).visibility = if (menu || header) View.VISIBLE else View.GONE
        if (menu) addChoice(list, getString(R.string.choice_menu), getString(R.string.btn_upload_menu), { askPin { s, k ->
            CloudSetup.uploadMenu(this, s, k)
            toast(getString(R.string.menu_uploaded, s.name))
        } }, null)
        if (header) addChoice(list, getString(R.string.choice_header), getString(R.string.btn_upload_header), { askPin { s, k ->
            CloudSetup.uploadHeader(this, s, k)
            toast(getString(R.string.header_uploaded, s.name))
        } }, { CloudSetup.skipHeader(this); render() })
    }

    private fun addChoice(list: LinearLayout, text: String, action: String, onYes: () -> Unit, onNo: (() -> Unit)?) {
        list.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(getColor(R.color.ink))
            setPadding(0, if (list.childCount > 0) dp(16) else 0, 0, dp(8))
        })
        list.addView(Button(this).apply {
            this.text = action
            isAllCaps = false
            setTextColor(getColor(R.color.on_accent))
            setBackgroundResource(R.drawable.btn_primary)
            setOnClickListener { onYes() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)))
        if (onNo != null) list.addView(Button(this).apply {
            this.text = getString(R.string.btn_not_now)
            isAllCaps = false
            setTextColor(getColor(R.color.ink))
            setBackgroundResource(R.drawable.btn_secondary)
            setOnClickListener { onNo() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(8) })
    }

    /** An owner's or manager's PIN, checked offline (with the pad's lockout), then [then]. */
    private fun askPin(then: (CloudSetup.Staff, String) -> Unit) {
        Manager.askPin(this, getString(R.string.title_pin), null, Manager.MANAGERS) { staff, key ->
            then(staff, key)
            render()
            SyncEngine.kick(this)
        }
    }

    private fun connect(code: EditText, name: EditText) {
        val c = code.text.toString().trim()
        val n = name.text.toString().trim()
        if (c.replace("-", "").length != 8) { code.error = getString(R.string.err_code); return }
        if (n.isEmpty()) { name.error = getString(R.string.err_phone_name); return }
        val btn = findViewById<Button>(R.id.btnConnect)
        btn.isEnabled = false
        btn.text = getString(R.string.connecting)
        thread {
            val result = try {
                Result.success(CloudSetup.connect(this, server, c, n))
            } catch (e: Exception) {
                Result.failure(e)
            }
            ui.post {
                if (isFinishing) return@post
                btn.isEnabled = true
                btn.text = getString(R.string.btn_connect)
                result.onSuccess { me ->
                    toast(getString(R.string.connected_toast, me.accountName, me.letter))
                    render()
                    SyncEngine.kick(this)
                    Live.start(this)
                }.onFailure { e ->
                    AlertDialog.Builder(this).setMessage(e.message ?: e.javaClass.simpleName).setPositiveButton(android.R.string.ok, null).show()
                }
            }
        }
    }

    /** For testing against staging or a laptop: hidden behind a long-press. */
    private fun editServer() {
        val input = EditText(this).apply {
            setText(server)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.title_server)
            .setView(input)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                server = input.text.toString().trim().trimEnd('/').ifEmpty { Cloud.DEFAULT_SERVER }
                Store.put(this, "cloud.server", if (server == Cloud.DEFAULT_SERVER) null else server)
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Disconnecting stops this device sharing the shop's bills, so it takes
     * the owner's PIN (a manager's when no owner has one). A removed device,
     * or one where nobody has a PIN yet, only asks to confirm.
     */
    private fun confirmDisconnect() {
        val staff = CloudSetup.staff(this)
        val roles = if (staff.any { it.role == "owner" }) Manager.OWNERS else Manager.MANAGERS
        if (Cloud.status(this).state == Cloud.State.REMOVED || staff.isEmpty()) disconnectNow()
        else Manager.askPin(this, getString(if (roles == Manager.OWNERS) R.string.title_owner_pin else R.string.title_pin), getString(R.string.disconnect_needs_owner), roles) { _, _ -> disconnectNow() }
    }

    private fun disconnectNow() {
        val me = Cloud.me(this) ?: return
        val waiting = Store.outboxCount(this)
        val l = CloudSetup.leaving(this)
        val msg = getString(R.string.disconnect_confirm, me.accountName, l.bills, l.payments, l.otherBills, l.otherPayments) +
            if (waiting > 0) "\n\n" + resources.getQuantityString(R.plurals.disconnect_waiting, waiting, waiting) else ""
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton(R.string.btn_disconnect) { _, _ ->
                CloudSetup.disconnect(this)
                Manager.lock()
                toast(getString(R.string.disconnected))
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun dayText(day: String?): String {
        if (day == null) return "—"
        return try { SimpleDateFormat("d MMM", Locale.getDefault()).format(SimpleDateFormat("yyyyMMdd", Locale.US).parse(day)!!) } catch (_: Exception) { day }
    }

    private fun offsetText(ms: Long): String {
        val m = Math.abs(ms) / 60_000
        return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun ago(t: Long): String {
        val m = ((Cloud.now(this) - t) / 60_000).coerceAtLeast(0)
        return when {
            m < 2 -> getString(R.string.seen_now)
            m < 60 -> getString(R.string.seen_minutes, m.toInt())
            m < 48 * 60 -> getString(R.string.seen_hours, (m / 60).toInt())
            else -> getString(R.string.seen_days, (m / 1440).toInt())
        }
    }
}
