package com.parchipos.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Printers, what the bill says at the top and bottom, cloud sync, and the print log. */
class SettingsActivity : ParchiActivity() {

    private lateinit var billKind: RadioGroup
    private lateinit var printerIp: EditText
    private lateinit var printerPort: EditText
    private lateinit var paper: RadioGroup
    private lateinit var printParchi: CheckBox
    private lateinit var kitchenBlock: View
    private lateinit var kitchenKind: RadioGroup
    private lateinit var kitchenIp: EditText
    private lateinit var kitchenPort: EditText
    private lateinit var kitchenPaper: RadioGroup
    private lateinit var smallPrint: CheckBox
    private lateinit var printBrand: CheckBox
    private lateinit var shopName: EditText
    private lateinit var address: EditText
    private lateinit var phone: EditText
    private lateinit var gstin: EditText
    private lateinit var footer: EditText
    private lateinit var logView: TextView
    private lateinit var syncKey: EditText
    private lateinit var serverUrl: EditText
    private lateinit var syncStatus: TextView

    /** The Bluetooth printer picked for each slot; only the address and name are kept. */
    private var billBt = PrinterTarget(bluetooth = true)
    private var kitchenBt = PrinterTarget(bluetooth = true)
    /** Which slot the picker is for, held across the permission and Bluetooth-on prompts. */
    private var pickingForKitchen = false

    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PrintLog.init(this)
        setContentView(R.layout.activity_settings)

        billKind = findViewById(R.id.billKind)
        printerIp = findViewById(R.id.printerIp)
        printerPort = findViewById(R.id.printerPort)
        paper = findViewById(R.id.paper)
        printParchi = findViewById(R.id.printParchi)
        kitchenBlock = findViewById(R.id.kitchenBlock)
        kitchenKind = findViewById(R.id.kitchenKind)
        kitchenIp = findViewById(R.id.kitchenIp)
        kitchenPort = findViewById(R.id.kitchenPort)
        kitchenPaper = findViewById(R.id.kitchenPaper)
        smallPrint = findViewById(R.id.smallPrint)
        printBrand = findViewById(R.id.printBrand)
        shopName = findViewById(R.id.shopName)
        address = findViewById(R.id.address)
        phone = findViewById(R.id.phone)
        gstin = findViewById(R.id.gstin)
        footer = findViewById(R.id.footer)
        logView = findViewById(R.id.logView)
        syncKey = findViewById(R.id.syncKey)
        serverUrl = findViewById(R.id.serverUrl)
        syncStatus = findViewById(R.id.syncStatus)
        syncKey.setText(Prefs.syncKey(this))
        serverUrl.setText(Prefs.serverUrl(this))
        findViewById<Button>(R.id.btnSyncNow).setOnClickListener { syncNow() }
        findViewById<Button>(R.id.btnOpenCloud).setOnClickListener {
            save(quiet = true)
            startActivity(android.content.Intent(this, CloudActivity::class.java))
        }

        fillPrinters()
        // The exact bitmap the bill prints, so there is no surprise at the printer.
        findViewById<ImageView>(R.id.brandPreview).setImageBitmap(Wordmark.bitmap(576))
        fillShop()

        billKind.setOnCheckedChangeListener { _, id ->
            renderPrinters()
            if (id == R.id.billBt && !billBt.ready) pickBluetooth(forKitchen = false)
        }
        kitchenKind.setOnCheckedChangeListener { _, id ->
            renderPrinters()
            if (id == R.id.kitchenBt && !kitchenBt.ready) pickBluetooth(forKitchen = true)
        }
        printParchi.setOnCheckedChangeListener { _, _ -> renderPrinters() }
        findViewById<Button>(R.id.billBtChoose).setOnClickListener { pickBluetooth(forKitchen = false) }
        findViewById<Button>(R.id.kitchenBtChoose).setOnClickListener { pickBluetooth(forKitchen = true) }

        findViewById<Button>(R.id.btnSave).setOnClickListener { if (save()) finish() }
        findViewById<Button>(R.id.btnTest).setOnClickListener { testPrint(kitchen = false) }
        findViewById<Button>(R.id.btnTestKitchen).setOnClickListener { testPrint(kitchen = true) }
        findViewById<Button>(R.id.btnCopyLog).setOnClickListener {
            try {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("print log", PrintLog.text()))
                toast(getString(R.string.toast_log_copied))
            } catch (_: Exception) { /* nothing useful to say */ }
        }
        findViewById<Button>(R.id.btnCheckUpdate).setOnClickListener { checkForUpdate() }
        findViewById<TextView>(R.id.versionText).text = getString(R.string.version_line, appVersion())
    }

    /**
     * A row of chips under the title, one per section card on show (Printers,
     * Bill, Parchi Cloud…); each scrolls to its card, so the long page has a contents line.
     */
    private fun renderJumps() {
        val scroll = (findViewById<ViewGroup>(android.R.id.content)).getChildAt(0) as? ScrollView ?: return
        val page = scroll.getChildAt(0) as? LinearLayout ?: return
        (page.findViewWithTag<View>("jumps"))?.let { page.removeView(it) }
        val sections = listOf(R.string.section_printer to R.string.jump_printers, R.string.section_bill to R.string.jump_bill,
            R.string.title_cloud to R.string.jump_cloud, R.string.label_sync to R.string.jump_sync, R.string.label_activity to R.string.jump_log)
        val cards = sections.mapNotNull { (heading, chip) ->
            val label = getString(heading)
            (0 until page.childCount).map { page.getChildAt(it) }
                .firstOrNull { card -> card.visibility == View.VISIBLE && card is ViewGroup && firstText(card) == label }
                ?.let { it to getString(chip) }
        }
        if (cards.size < 2) return
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(12)) }
        cards.forEach { (card, label) ->
            bar.addView(TextView(this).apply {
                text = label
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
                setTextColor(getColor(R.color.ink))
                setBackgroundResource(R.drawable.chip)
                setOnClickListener { scroll.smoothScrollTo(0, card.top - dp(8)) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginEnd = dp(8) })
        }
        page.addView(HorizontalScrollView(this).apply {
            tag = "jumps"
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(24))
            addView(bar)
        }, 1)
    }

    private fun firstText(v: View): String? = when (v) {
        is TextView -> v.text?.toString()
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { firstText(v.getChildAt(it)) }
        else -> null
    }

    override fun onResume() {
        super.onResume()
        logView.text = PrintLog.text().ifEmpty { getString(R.string.no_activity) }
        renderCloud()
        renderSync()
        renderJumps()
    }

    /** The outlet's header is on screen and an owner or manager has unlocked it for editing. */
    private var headerUnlocked = false

    /**
     * Connected to Parchi Cloud: the bill header is the outlet's, edited in
     * the portal or here after an owner or manager unlocks; and the
     * IceCreamOS section goes away.
     */
    private fun renderCloud() {
        val me = Cloud.me(this)
        val entry = findViewById<TextView>(R.id.cloudEntry)
        entry.text = if (me == null) getString(R.string.cloud_entry_off) else getString(R.string.cloud_entry_on, me.accountName, me.outletName) + "\n" + when (Cloud.status(this).state) {
            Cloud.State.LIVE -> getString(R.string.state_live)
            Cloud.State.WAITING -> getString(R.string.state_waiting, Cloud.status(this).outbox)
            Cloud.State.OFFLINE -> getString(R.string.state_offline, Cloud.status(this).outbox)
            Cloud.State.UPGRADE -> getString(R.string.state_upgrade)
            Cloud.State.REMOVED -> getString(R.string.state_removed)
            else -> getString(R.string.state_error)
        }
        findViewById<View>(R.id.iceCreamCard).visibility = if (me == null) View.VISIBLE else View.GONE
        val cloudHeader = me != null && Cloud.outlet(this) != null && Store.get(this, "cloud.headerChoice") != "ask"
        val note = findViewById<TextView>(R.id.headerNote)
        note.visibility = if (cloudHeader) View.VISIBLE else View.GONE
        val unlocked = cloudHeader && Manager.current(this) != null
        val wasUnlocked = headerUnlocked
        headerUnlocked = unlocked
        if (cloudHeader) {
            note.text = if (unlocked) getString(R.string.header_unlocked, Manager.unlockedName(), me!!.outletName)
                else getString(R.string.header_locked, me!!.outletName) + "  " + getString(R.string.btn_unlock_to_edit) + " ›"
            note.setOnClickListener(if (unlocked) null else View.OnClickListener { Manager.unlock(this) { renderCloud() } })
        }
        val shown = if (cloudHeader) Prefs.shop(this) else Prefs.localShop(this)
        for ((field, value) in listOf(shopName to shown.name, address to shown.address, phone to shown.phone, gstin to shown.gstin, footer to shown.footer)) {
            field.isEnabled = !cloudHeader || unlocked
            // What is being typed stays; a fresh unlock shows the outlet's current header to edit.
            if (cloudHeader && (!unlocked || !wasUnlocked) || !field.hasFocus()) field.setText(value)
        }
    }

    /**
     * The outlet's header, as typed here by whoever unlocked, signed with
     * their PIN for every device of the outlet. The logo switch stays as the
     * portal set it. True if something changed.
     */
    private fun saveCloudHeader(quiet: Boolean): Boolean {
        if (!headerUnlocked) return false
        val me = Cloud.me(this) ?: return false
        val session = Manager.current(this)
        if (session == null) {
            if (!quiet) toast(getString(R.string.btn_unlock_to_edit))
            return false
        }
        val typed = readShop()
        val changed = Store.tx(this) {
            val rec = Store.record(this, "outlet", me.outletId) ?: return@tx false
            val header = JSONObject(rec.data.optJSONObject("header")?.toString() ?: "{}")
            val before = header.toString()
            header.put("shopName", typed.name.trim()).put("address", typed.address.trim()).put("phone", typed.phone.trim())
                .put("gstin", typed.gstin.trim().uppercase()).put("footer", typed.footer.trim())
            if (!header.has("logo")) header.put("logo", Prefs.localShop(this).brand)
            if (header.toString() == before) return@tx false
            Store.saveRecord(this, CloudSetup.sign(this, rec.copy(data = JSONObject(rec.data.toString()).put("header", header), dirty = true), session.staff, session.key))
            true
        }
        if (changed) {
            SyncEngine.kick(this)
            if (!quiet) toast(getString(R.string.header_saved, me.outletName))
        }
        return changed
    }

    /** Leaving with Back keeps what was typed — losing it would be the surprise. */
    override fun onPause() {
        super.onPause()
        save(quiet = true)
    }

    // ── Printers ──────────────────────────────────────────────────────

    private fun fillPrinters() {
        val bill = Prefs.billPrinter(this)
        val kitchen = Prefs.kitchenPrinter(this)
        billKind.check(if (bill.bluetooth) R.id.billBt else R.id.billNet)
        printerIp.setText(bill.host)
        printerPort.setText(bill.port.toString())
        billBt = PrinterTarget(bluetooth = true, btAddress = bill.btAddress, btName = bill.btName)

        kitchenKind.check(when {
            kitchen.sameAsBill -> R.id.kitchenSame
            kitchen.target.bluetooth -> R.id.kitchenBt
            else -> R.id.kitchenNet
        })
        kitchenIp.setText(kitchen.target.host)
        kitchenPort.setText(kitchen.target.port.toString())
        kitchenBt = PrinterTarget(bluetooth = true, btAddress = kitchen.target.btAddress, btName = kitchen.target.btName)
        kitchenPaper.check(if (kitchen.width == 32) R.id.kitchenPaper58 else R.id.kitchenPaper80)
        renderPrinters()
    }

    /** Shows the fields for whichever kind of printer is picked in each slot. */
    private fun renderPrinters() {
        val billOnBt = billKind.checkedRadioButtonId == R.id.billBt
        findViewById<View>(R.id.billNetBlock).visibility = if (billOnBt) View.GONE else View.VISIBLE
        findViewById<View>(R.id.billBtBlock).visibility = if (billOnBt) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.billBtLabel).text = btLabel(billBt)

        kitchenBlock.visibility = if (printParchi.isChecked) View.VISIBLE else View.GONE
        val k = kitchenKind.checkedRadioButtonId
        findViewById<View>(R.id.kitchenNetBlock).visibility = if (k == R.id.kitchenNet) View.VISIBLE else View.GONE
        findViewById<View>(R.id.kitchenBtBlock).visibility = if (k == R.id.kitchenBt) View.VISIBLE else View.GONE
        findViewById<View>(R.id.kitchenPaperBlock).visibility = if (k == R.id.kitchenSame) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btnTestKitchen).visibility = if (k == R.id.kitchenSame) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.kitchenBtLabel).text = btLabel(kitchenBt)
    }

    private fun btLabel(t: PrinterTarget): String =
        if (!t.ready) getString(R.string.bt_none_chosen) else getString(R.string.bt_chosen, t.label, t.btAddress)

    /**
     * Lists paired devices to pick from. Asks for the Nearby devices
     * permission (Android 12+) and for Bluetooth to be on first, coming back
     * here once granted.
     */
    @SuppressLint("MissingPermission") // ACTION_REQUEST_ENABLE runs only once the permission is held
    private fun pickBluetooth(forKitchen: Boolean) {
        pickingForKitchen = forKitchen
        if (BtPrinter.adapter(this) == null) { toast(getString(R.string.bt_no_bluetooth)); return }
        if (!BtPrinter.hasPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT_PERMISSION)
            return
        }
        if (!BtPrinter.isOn(this)) {
            try {
                startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_BT_ON)
            } catch (_: Exception) {
                toast(getString(R.string.bt_turn_on))
            }
            return
        }
        val devices = try { BtPrinter.paired(this) } catch (e: BtPrinter.BtError) { toast(e.message ?: ""); return }
        if (devices.isEmpty()) {
            AlertDialog.Builder(this)
                .setMessage(R.string.bt_none_paired)
                .setPositiveButton(R.string.bt_open_settings) { _, _ -> openBluetoothSettings() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val labels = devices.map { d ->
            d.name + (if (d.looksLikePrinter) "  ·  " + getString(R.string.bt_likely_printer) else "") + "\n" + d.address
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.bt_title_choose)
            .setItems(labels) { _, i ->
                val picked = PrinterTarget(bluetooth = true, btAddress = devices[i].address, btName = devices[i].name)
                if (pickingForKitchen) kitchenBt = picked else billBt = picked
                renderPrinters()
                save(quiet = true)
            }
            .setNeutralButton(R.string.bt_pair_new) { _, _ -> openBluetoothSettings() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openBluetoothSettings() {
        try {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (_: Exception) {
            toast(getString(R.string.bt_turn_on))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_BT_PERMISSION) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            pickBluetooth(pickingForKitchen)
            return
        }
        // Denied for good: only Android's app settings can grant it now.
        if (!shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT)) {
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.bt_permission_needed) + "\n\n" + getString(R.string.bt_permission_settings))
                .setPositiveButton(R.string.btn_app_settings) { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                    } catch (_: Exception) {}
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            toast(getString(R.string.bt_permission_needed))
        }
    }

    @Deprecated("Activity result API; this app has no AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_BT_ON && resultCode == RESULT_OK) pickBluetooth(pickingForKitchen)
    }

    private fun readBill(): PrinterTarget = if (billKind.checkedRadioButtonId == R.id.billBt) billBt.copy(
        host = printerIp.text.toString().trim().ifEmpty { Prefs.DEF_PRINTER_IP },
        port = printerPort.text.toString().trim().toIntOrNull() ?: Prefs.DEF_PRINTER_PORT,
    ) else PrinterTarget(
        bluetooth = false,
        host = printerIp.text.toString().trim().ifEmpty { Prefs.DEF_PRINTER_IP },
        port = printerPort.text.toString().trim().toIntOrNull() ?: Prefs.DEF_PRINTER_PORT,
        btAddress = billBt.btAddress,
        btName = billBt.btName,
    )

    private fun readKitchen(): KitchenPrinter {
        val k = kitchenKind.checkedRadioButtonId
        return KitchenPrinter(
            sameAsBill = k == R.id.kitchenSame,
            target = PrinterTarget(
                bluetooth = k == R.id.kitchenBt,
                host = kitchenIp.text.toString().trim(),
                port = kitchenPort.text.toString().trim().toIntOrNull() ?: Prefs.DEF_PRINTER_PORT,
                btAddress = kitchenBt.btAddress,
                btName = kitchenBt.btName,
            ),
            width = if (kitchenPaper.checkedRadioButtonId == R.id.kitchenPaper58) 32 else Prefs.DEF_WIDTH,
        )
    }

    private fun fillShop() {
        val shop = Prefs.localShop(this)
        paper.check(if (shop.width == 32) R.id.paper58 else R.id.paper80)
        printParchi.isChecked = shop.printParchi
        smallPrint.isChecked = shop.small
        printBrand.isChecked = shop.brand
        shopName.setText(shop.name)
        address.setText(shop.address)
        phone.setText(shop.phone)
        gstin.setText(shop.gstin)
        footer.setText(shop.footer)
        renderPrinters()
    }

    private fun readShop() = Prefs.Shop(
        name = shopName.text.toString().trim(),
        address = address.text.toString().trim(),
        phone = phone.text.toString().trim(),
        gstin = gstin.text.toString().trim().uppercase(),
        footer = footer.text.toString().trim(),
        width = if (paper.checkedRadioButtonId == R.id.paper58) 32 else Prefs.DEF_WIDTH,
        printParchi = printParchi.isChecked,
        small = smallPrint.isChecked,
        brand = printBrand.isChecked,
    )

    /** Why a printer setting can't be saved as it is, or null. */
    private fun problem(t: PrinterTarget): String? = when {
        t.bluetooth -> if (t.ready) null else getString(R.string.bt_choose_first)
        !looksLikeHost(t.host) -> getString(R.string.bad_ip)
        t.port !in 1..65535 -> getString(R.string.bad_port)
        else -> null
    }

    private fun save(quiet: Boolean = false): Boolean {
        val bill = readBill()
        val kitchen = readKitchen()
        // Connected, the header on screen is the outlet's: this phone's own stays as it was.
        val shop = if (Cloud.connected(this)) Prefs.localShop(this).let { l -> readShop().copy(name = l.name, address = l.address, phone = l.phone, gstin = l.gstin, footer = l.footer) } else readShop()
        val bad = problem(bill) ?: if (shop.printParchi && !kitchen.sameAsBill) problem(kitchen.target) else null
        if (bad != null) { if (!quiet) toast(bad); return false }
        Prefs.save(this, bill, kitchen, shop)
        val headerSaved = saveCloudHeader(quiet)
        if (Prefs.saveSync(this, serverUrl.text.toString(), syncKey.text.toString())) Sync.forget(this)
        if (!quiet && !headerSaved) toast(getString(R.string.toast_saved))
        return true
    }

    /** Permissive on purpose — hostnames are legitimate, gibberish is not. */
    private fun looksLikeHost(s: String): Boolean =
        s.isNotBlank() && s.length <= 253 && Regex("^[A-Za-z0-9.\\-]+$").matches(s)

    /** Saves first, so the test uses exactly what is on screen. */
    private fun testPrint(kitchen: Boolean) {
        if (!save(quiet = false)) return
        val shop = readShop()
        val k = readKitchen()
        val target = if (kitchen && !k.sameAsBill) k.target else readBill()
        val width = if (kitchen && !k.sameAsBill) k.width else shop.width
        Printer.send(this, target, "test print", Escpos.test(shop.copy(width = width))) { err ->
            toast(if (err == null) getString(R.string.test_ok, target.label) else getString(R.string.test_fail, target.label, err))
            logView.text = PrintLog.text()
        }
    }

    private fun checkForUpdate() {
        toast(getString(R.string.checking))
        thread {
            val current = appVersion()
            val r = UpdateCheck.fetch(current)
            ui.post {
                when {
                    r == null -> toast(getString(R.string.check_failed))
                    r.newer -> AlertDialog.Builder(this)
                        .setMessage(getString(R.string.update_available, r.latest, current))
                        .setPositiveButton(R.string.btn_get_update) { _, _ ->
                            // Hand the download to the browser. Installing from inside the app
                            // would need REQUEST_INSTALL_PACKAGES, which Google restricts.
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateCheck.LATEST_PAGE)))
                            } catch (_: Exception) {
                                toast(UpdateCheck.LATEST_PAGE)
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    else -> toast(getString(R.string.check_uptodate, current))
                }
            }
        }
    }

    private fun syncNow() {
        if (!save(quiet = true)) return
        if (!Sync.configured(this)) { renderSync(); return }
        syncStatus.text = getString(R.string.sync_started)
        Sync.kick(this) { ui.post { if (!isFinishing) renderSync() } }
    }

    private fun renderSync() {
        if (!Sync.configured(this)) { syncStatus.text = getString(R.string.sync_off); return }
        thread {
            val total = Sales.all(this).size
            val waiting = Sync.pending(this).size
            val st = Prefs.syncStatus(this)
            val lines = mutableListOf(
                if (waiting == 0) resources.getQuantityString(R.plurals.sync_all_sent, total, total) else getString(R.string.sync_waiting, waiting, total),
            )
            if (st.at > 0) lines += getString(R.string.sync_last, SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(st.at)))
            if (st.error.isNotEmpty()) lines += getString(R.string.sync_error, st.error)
            Prefs.letter(this).takeIf { it.isNotEmpty() }?.let { lines += getString(R.string.sync_letter, it) }
            serverCounts(st.counts).takeIf { it.isNotEmpty() }?.let { lines += getString(R.string.sync_server, st.device, it) }
            ui.post { if (!isFinishing) syncStatus.text = lines.joinToString("\n") }
        }
    }

    /** {"POSTED":120,"UNMAPPED":3} → "120 posted, 3 need item links in IceCreamOS". */
    private fun serverCounts(json: String): String = try {
        val o = JSONObject(json)
        val words = mapOf(
            "POSTED" to "posted", "VOIDED" to "voided", "UNMAPPED" to "need item links",
            "BLOCKED" to "can't post", "PENDING" to "queued", "POSTING" to "posting",
            "HELD" to "on credit (kept in Dues, not booked yet)",
        )
        words.keys.filter { o.optInt(it) > 0 }.joinToString(", ") { "${o.getInt(it)} ${words[it]}" }
    } catch (_: Exception) { "" }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_BT_PERMISSION = 31
        private const val REQ_BT_ON = 32
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
