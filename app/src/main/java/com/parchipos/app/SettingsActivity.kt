package com.parchipos.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/** Printer address, what the bill says at the top and bottom, and the print log. */
class SettingsActivity : Activity() {

    private lateinit var printerIp: EditText
    private lateinit var printerPort: EditText
    private lateinit var paper: RadioGroup
    private lateinit var printParchi: CheckBox
    private lateinit var smallPrint: CheckBox
    private lateinit var shopName: EditText
    private lateinit var address: EditText
    private lateinit var phone: EditText
    private lateinit var gstin: EditText
    private lateinit var footer: EditText
    private lateinit var logView: TextView

    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PrintLog.init(this)
        setContentView(R.layout.activity_settings)

        printerIp = findViewById(R.id.printerIp)
        printerPort = findViewById(R.id.printerPort)
        paper = findViewById(R.id.paper)
        printParchi = findViewById(R.id.printParchi)
        smallPrint = findViewById(R.id.smallPrint)
        shopName = findViewById(R.id.shopName)
        address = findViewById(R.id.address)
        phone = findViewById(R.id.phone)
        gstin = findViewById(R.id.gstin)
        footer = findViewById(R.id.footer)
        logView = findViewById(R.id.logView)

        val shop = Prefs.shop(this)
        printerIp.setText(Prefs.printerIp(this))
        printerPort.setText(Prefs.printerPort(this).toString())
        paper.check(if (shop.width == 32) R.id.paper58 else R.id.paper80)
        printParchi.isChecked = shop.printParchi
        smallPrint.isChecked = shop.small
        shopName.setText(shop.name)
        address.setText(shop.address)
        phone.setText(shop.phone)
        gstin.setText(shop.gstin)
        footer.setText(shop.footer)

        findViewById<Button>(R.id.btnSave).setOnClickListener { if (save()) finish() }
        findViewById<Button>(R.id.btnTest).setOnClickListener { testPrint() }
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

    override fun onResume() {
        super.onResume()
        logView.text = PrintLog.text().ifEmpty { getString(R.string.no_activity) }
    }

    /** Leaving with Back keeps what was typed — losing it would be the surprise. */
    override fun onPause() {
        super.onPause()
        save(quiet = true)
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
    )

    private fun readPrinter(): Pair<String, Int> = Pair(
        printerIp.text.toString().trim().ifEmpty { Prefs.DEF_PRINTER_IP },
        printerPort.text.toString().trim().toIntOrNull() ?: Prefs.DEF_PRINTER_PORT,
    )

    private fun save(quiet: Boolean = false): Boolean {
        val (ip, port) = readPrinter()
        if (!looksLikeHost(ip)) { if (!quiet) toast(getString(R.string.bad_ip)); return false }
        if (port !in 1..65535) { if (!quiet) toast(getString(R.string.bad_port)); return false }
        Prefs.save(this, ip, port, readShop())
        if (!quiet) toast(getString(R.string.toast_saved))
        return true
    }

    /** Permissive on purpose — hostnames are legitimate, gibberish is not. */
    private fun looksLikeHost(s: String): Boolean =
        s.isNotBlank() && s.length <= 253 && Regex("^[A-Za-z0-9.\\-]+$").matches(s)

    /** Saves first, so the test uses exactly what is on screen. */
    private fun testPrint() {
        if (!save(quiet = false)) return
        val (ip, port) = readPrinter()
        Printer.send(this, "test print", Escpos.test(readShop())) { err ->
            toast(if (err == null) getString(R.string.test_ok, ip, port) else getString(R.string.test_fail, ip, port, err))
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

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
