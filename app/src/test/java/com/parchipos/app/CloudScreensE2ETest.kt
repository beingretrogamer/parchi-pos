package com.parchipos.app

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.net.HttpURLConnection
import java.net.URL

/**
 * The Parchi Cloud screens, driven like a person would, against a real
 * server (see CloudE2ETest for how to run). Skipped without PARCHI_E2E_URL.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudScreensE2ETest {

    private val server = System.getenv("PARCHI_E2E_URL")
    private val stamp = System.currentTimeMillis()

    @Before fun setUp() {
        assumeTrue(server != null)
        Store.closeForTests()
        val c = RuntimeEnvironment.getApplication()
        c.filesDir.listFiles()?.forEach { it.delete() }
        c.deleteDatabase("parchi.db")
    }

    @After fun tearDown() = Store.closeForTests()

    private var cookie = ""
    private fun call(method: String, path: String, body: JSONObject): JSONObject {
        val conn = (URL(server + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("content-type", "application/json")
            if (cookie.isNotEmpty()) setRequestProperty("cookie", cookie)
            doOutput = true
            outputStream.use { it.write(body.toString().toByteArray()) }
        }
        check(conn.responseCode in 200..299) { "$path → ${conn.responseCode}" }
        conn.getHeaderField("set-cookie")?.let { cookie = it.substringBefore(';') }
        return JSONObject(conn.inputStream.bufferedReader().readText())
    }

    /** Lets background threads finish and the main thread run what they posted. */
    private fun settle(until: () -> Boolean) {
        repeat(150) {
            shadowOf(Looper.getMainLooper()).idle()
            if (until()) return
            Thread.sleep(100)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun connectFromTheScreenAndUploadTheMenuWithAPin() {
        call("POST", "/api/auth/login", JSONObject().put("login", System.getenv("PARCHI_E2E_ADMIN")).put("password", System.getenv("PARCHI_E2E_PASSWORD")))
        val acc = call("POST", "/api/admin/accounts", JSONObject().put("name", "Screens $stamp").put("maxDevices", 2)
            .put("outlet", JSONObject().put("name", "Main")).put("owner", JSONObject().put("login", "screens-$stamp").put("name", "Owner").put("password", "owner-pass-123")))
        cookie = ""
        call("POST", "/api/auth/login", JSONObject().put("login", "screens-$stamp").put("password", "owner-pass-123"))
        call("POST", "/api/me/pin", JSONObject().put("password", "owner-pass-123").put("pin", "1357"))
        val code = call("POST", "/api/accounts/${acc.getJSONObject("account").getString("id")}/codes", JSONObject().put("outletId", acc.getString("outletId"))).getString("code")

        val app = RuntimeEnvironment.getApplication()
        Store.put(app, "cloud.server", server)
        val cloud = Robolectric.buildActivity(CloudActivity::class.java).setup().get()
        assertEquals(View.VISIBLE, cloud.findViewById<View>(R.id.connectCard).visibility)
        assertTrue(cloud.findViewById<TextView>(R.id.uploadNote).text.isNotEmpty())

        cloud.findViewById<EditText>(R.id.code).setText(code)
        cloud.findViewById<EditText>(R.id.deviceName).setText("Counter")
        cloud.findViewById<Button>(R.id.btnConnect).performClick()
        settle { Cloud.connected(app) && Store.get(app, "cloud.windowDone") == "1" && cloud.findViewById<TextView>(R.id.stateDetail).text.contains("Screens $stamp") && cloud.findViewById<View>(R.id.choiceCard).visibility == View.VISIBLE }

        assertEquals(View.GONE, cloud.findViewById<View>(R.id.connectCard).visibility)
        assertEquals(View.VISIBLE, cloud.findViewById<View>(R.id.statusCard).visibility)
        assertTrue(cloud.findViewById<TextView>(R.id.stateDetail).text.contains("Screens $stamp"))
        assertEquals("the starter menu is offered to the empty account", View.VISIBLE, cloud.findViewById<View>(R.id.choiceCard).visibility)

        // Upload menu → PIN dialog → the owner's PIN.
        val buttons = cloud.findViewById<android.widget.LinearLayout>(R.id.choiceList)
        (0 until buttons.childCount).map { buttons.getChildAt(it) }.filterIsInstance<Button>().first { it.text == cloud.getString(R.string.btn_upload_menu) }.performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        val pad = shadowOf(dialog).view as android.widget.LinearLayout
        val pinField = (0 until pad.childCount).map { pad.getChildAt(it) }.filterIsInstance<EditText>().single()
        val status = (0 until pad.childCount).map { pad.getChildAt(it) }.filterIsInstance<TextView>().last { it !is EditText }
        pinField.setText("0000")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        settle { status.text.startsWith("Wrong PIN") }
        assertEquals(cloud.getString(R.string.pin_wrong_left, Manager.MAX_TRIES - 1), status.text.toString())
        pinField.setText("1357")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        settle { Store.get(app, "cloud.menuChoice") == null && Store.outboxCount(app) == 0 && cloud.findViewById<View>(R.id.choiceCard).visibility == View.GONE }
        assertEquals(0, Store.outboxCount(app))
        assertEquals(Cloud.State.LIVE, Cloud.status(app).state)

        // The till shows the cloud dot and a padlock; the menu is read-only until unlocked; Settings hides IceCreamOS.
        Manager.lock()
        val till = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertEquals(View.VISIBLE, till.findViewById<View>(R.id.cloudDot).visibility)
        assertEquals(View.VISIBLE, till.findViewById<View>(R.id.managerChip).visibility)
        assertEquals(till.getString(R.string.manager_chip_locked), till.findViewById<TextView>(R.id.managerChip).text.toString())
        val menu = Robolectric.buildActivity(MenuActivity::class.java).setup().get()
        assertEquals(View.GONE, menu.findViewById<View>(R.id.btnAdd).visibility)
        val staff = CloudSetup.staff(app).single()
        Manager.begin(staff, Pin.key("1357", staff.salt))
        val unlocked = Robolectric.buildActivity(MenuActivity::class.java).setup().get()
        assertEquals("unlocked, the editor is back", View.VISIBLE, unlocked.findViewById<View>(R.id.btnAdd).visibility)
        assertEquals("but never the built-in board, which would replace the account's menu", View.GONE, unlocked.findViewById<View>(R.id.btnReset).visibility)
        val chip = Robolectric.buildActivity(MainActivity::class.java).setup().get().findViewById<TextView>(R.id.managerChip).text.toString()
        assertTrue(chip, chip.startsWith("🔓 Owner"))
        Manager.lock()
        val settings = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        assertEquals(View.GONE, settings.findViewById<View>(R.id.iceCreamCard).visibility)
        assertTrue(settings.findViewById<TextView>(R.id.cloudEntry).text.contains("Screens $stamp"))

        // The app lock: once the owner switches it on, a screen coming up shows the lock first.
        Store.saveRecord(app, Store.Rec("rules", "rules", JSONObject().put("appLock", true), seq = Long.MAX_VALUE / 2))
        AppLock.reset()
        val locked = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertEquals(LockActivity::class.java.name, shadowOf(locked).nextStartedActivity?.component?.className)
    }
}
