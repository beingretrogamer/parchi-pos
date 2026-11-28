package com.parchipos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Till rules, manager PINs, approvals on bills, sold out and the app lock, on a connected device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManagerTest {

    private lateinit var c: Context
    private val me = Cloud.Me("https://cloud.test", "dev_me", "pk_acc_x_key", "acc_x", "Shop", "out_1", "Main", "A", "Counter", 1)
    private val ownerSalt = "00112233445566778899aabbccddeeff"
    private val managerSalt = "ffeeddccbbaa99887766554433221100"
    private val staffSalt = "0f1e2d3c4b5a69788796a5b4c3d2e1f0"

    @Before fun setUp() {
        Store.closeForTests()
        c = RuntimeEnvironment.getApplication()
        c.deleteDatabase("parchi.db")
        Manager.lock()
        AppLock.reset()
    }

    @After fun tearDown() {
        Manager.lock()
        Store.closeForTests()
    }

    private fun connect() = Cloud.saveMe(c, me)

    private fun staff(id: String, name: String, role: String, pin: String, salt: String, outlets: List<String> = emptyList()) {
        Store.saveRecord(c, Store.Rec("staff", id, JSONObject().put("name", name).put("role", role).put("active", true)
            .put("outletIds", JSONArray(outlets)).put("pinSalt", salt).put("pinVerifier", Pin.verifier(Pin.key(pin, salt))), seq = 1))
    }

    private fun rules(vararg pairs: Pair<String, Any>) {
        val d = JSONObject()
        pairs.forEach { (k, v) -> d.put(k, v) }
        Store.saveRecord(c, Store.Rec("rules", "rules", d, seq = 2))
    }

    private fun outlet(extra: JSONObject.() -> Unit = {}) {
        Store.saveRecord(c, Store.Rec("outlet", me.outletId, JSONObject().put("name", "Main").apply(extra), outletId = me.outletId, seq = 3))
    }

    @Test fun aDeviceOfItsOwnHasNoRules() {
        rules(Rules.PRICE_EDIT to "manager", "appLock" to true)
        assertFalse(Rules.needsPin(c, Rules.PRICE_EDIT))
        assertFalse(Rules.discountNeedsPin(c, 90.0, 100.0))
        assertFalse(Rules.appLock(c))
        assertFalse(AppLock.locked(c))
    }

    @Test fun connectedUsesTheServersDefaultsUntilTheOwnerSetsRules() {
        connect()
        assertTrue(Rules.needsPin(c, Rules.PRICE_EDIT))
        assertTrue(Rules.needsPin(c, Rules.VOID_RECEIPT))
        assertFalse(Rules.needsPin(c, Rules.OPEN_ITEMS))
        assertFalse(Rules.needsPin(c, Rules.VOID_BILL))
        // 10% of the bill is free; a paisa over needs a PIN.
        assertFalse(Rules.discountNeedsPin(c, 10.0, 100.0))
        assertTrue(Rules.discountNeedsPin(c, 10.01, 100.0))

        rules(Rules.PRICE_EDIT to "allow", Rules.VOID_BILL to "manager", "discountMaxPct" to 25)
        assertFalse(Rules.needsPin(c, Rules.PRICE_EDIT))
        assertTrue(Rules.needsPin(c, Rules.VOID_BILL))
        assertFalse(Rules.discountNeedsPin(c, 25.0, 100.0))
        assertTrue(Rules.discountNeedsPin(c, 26.0, 100.0))
    }

    @Test fun aPinIsCheckedOfflineAndFiveWrongOnesLockThePad() {
        connect()
        staff("usr_owner", "Mudit", "owner", "2468", ownerSalt)
        staff("usr_ravi", "Ravi", "manager", "1357", managerSalt)
        staff("usr_far", "Far Away", "manager", "9999", staffSalt, outlets = listOf("out_other"))

        val ok = Manager.check(c, "1357", Manager.MANAGERS) as Manager.Check.Ok
        assertEquals("usr_ravi", ok.staff.userId)
        assertEquals(Pin.key("1357", managerSalt), ok.key)
        // A manager's PIN isn't an owner's; a manager of another outlet's isn't anyone's here.
        assertTrue(Manager.check(c, "1357", Manager.OWNERS) is Manager.Check.Wrong)
        assertTrue(Manager.check(c, "9999", Manager.MANAGERS) is Manager.Check.Wrong)
        assertEquals(2, (Manager.check(c, "0000", Manager.MANAGERS) as Manager.Check.Wrong).triesLeft)
        assertEquals(1, (Manager.check(c, "1111", Manager.MANAGERS) as Manager.Check.Wrong).triesLeft)
        assertTrue(Manager.check(c, "2222", Manager.MANAGERS) is Manager.Check.LockedOut)
        // Locked out: even the right PIN waits.
        assertTrue(Manager.check(c, "2468", Manager.MANAGERS) is Manager.Check.LockedOut)
        assertTrue(Manager.lockedOutMs(c) in 1..Manager.LOCKOUT_MS)
        Store.put(c, "pin.lockedUntil", (System.currentTimeMillis() - 1).toString())
        assertTrue(Manager.check(c, "2468", Manager.OWNERS) is Manager.Check.Ok)
        // A right PIN starts the count again.
        assertEquals(Manager.MAX_TRIES - 1, (Manager.check(c, "0000", Manager.MANAGERS) as Manager.Check.Wrong).triesLeft)
    }

    @Test fun anUnlockSignsMenuEditsAndEndsWhenTheStaffRecordChanges() {
        connect()
        staff("usr_ravi", "Ravi", "manager", "1357", managerSalt)
        val key = Pin.key("1357", managerSalt)

        // Nobody unlocked: the edit is saved unsigned (the screen asks for a PIN first).
        Menu.upsert(c, MenuItem("itm_1", "Tea", 10.0, "Hot"))
        assertNull(Store.record(c, "menuItem", "itm_1")!!.proof)

        Manager.begin(CloudSetup.staff(c).single(), key)
        Menu.upsert(c, MenuItem("itm_1", "Tea", 12.0, "Chai & Coffee"))
        val rec = Store.record(c, "menuItem", "itm_1")!!
        assertEquals("usr_ravi", rec.byUser)
        assertEquals(Pin.proof(key, "menuItem", "itm_1", rec.updatedAt), rec.proof)
        val cat = Store.record(c, "category", rec.data.getString("categoryId"))!!
        assertEquals(Pin.proof(key, "category", cat.id, cat.updatedAt), cat.proof)

        // A new PIN set in the portal arrives as a new verifier: the unlock ends.
        staff("usr_ravi", "Ravi", "manager", "8642", managerSalt)
        assertNull(Manager.current(c))
    }

    @Test fun approvalsGoOnTheBillWithAProofNamingIt() {
        connect()
        staff("usr_ravi", "Ravi", "manager", "1357", managerSalt)
        val ravi = CloudSetup.staff(c).single()
        val key = Pin.key("1357", managerSalt)
        val t = 1_800_000_000_000L
        Sales.record(c, Sale(id = "261127-A001", token = 1, time = t, letter = "A", lines = listOf(SaleLine("Chai", 20.0, 1)),
            payment = "CASH", customer = "Ramesh", uid = "bill_1", updatedAt = t, deviceId = me.deviceId))

        Sales.setVoided(c, "bill_1", true, "Customer left", Manager.Approval(Rules.VOID_BILL, ravi, key, t + 5))
        val s = Store.bill(c, "bill_1")!!
        assertTrue(s.voided)
        val a = s.approvals.single()
        assertEquals(Rules.VOID_BILL, a.action)
        assertEquals("usr_ravi", a.userId)
        assertEquals("Ravi", a.name)
        assertEquals(Pin.proof(key, "approval:voidBill", "bill_1", t + 5), a.proof)
        // The void and the approval are both stamped, so they travel together.
        assertEquals(s.fieldTimes["void"], s.fieldTimes["content"])
        val data = Store.raw(c, "bill", "bill_1")!!.data
        assertEquals("voidBill", data.getJSONArray("approvals").getJSONObject(0).getString("action"))

        // Moving to credit makes the customer record, and the bill points to it.
        Sales.setPayment(c, "bill_1", CREDIT)
        assertEquals(Customers.idFor(me.outletId, "Ramesh"), Store.bill(c, "bill_1")!!.customerId)
    }

    @Test fun soldOutIsPerOutletAndSignedOnlyWhenApproved() {
        connect()
        staff("usr_ravi", "Ravi", "manager", "1357", managerSalt)
        Menu.upsert(c, MenuItem("itm_1", "Tea", 10.0, "Hot"))

        Menu.setSoldOut(c, "itm_1", true, null)
        val rec = Store.record(c, "itemStatus", "out_1:itm_1")!!
        assertEquals("out_1", rec.outletId)
        assertTrue(rec.dirty)
        assertNull(rec.proof)
        assertTrue(Menu.all(c).first { it.id == "itm_1" }.soldOut)
        assertEquals(1, Menu.all(c).count { it.soldOut })

        val key = Pin.key("1357", managerSalt)
        Menu.setSoldOut(c, "itm_1", false, Manager.Approval(Rules.SOLD_OUT, CloudSetup.staff(c).single(), key, 1))
        val back = Store.record(c, "itemStatus", "out_1:itm_1")!!
        assertEquals(Pin.proof(key, "itemStatus", "out_1:itm_1", back.updatedAt), back.proof)
        assertFalse(Menu.all(c).first { it.id == "itm_1" }.soldOut)
    }

    @Test fun theAppLockOpensWithTheStaffPinOrAManagersAndComesBackWhenIdle() {
        connect()
        staff("usr_ravi", "Ravi", "manager", "1357", managerSalt)
        outlet { put("lockPinSalt", staffSalt).put("lockPinVerifier", Pin.verifier(Pin.key("2580", staffSalt))) }
        assertFalse(AppLock.locked(c)) // the rule is off
        rules("appLock" to true, "appLockIdleMinutes" to 1)
        assertTrue(AppLock.locked(c)) // just opened

        assertTrue(Manager.hasStaffPin(c))
        val staffPin = Manager.check(c, "2580", Manager.MANAGERS, withStaffPin = true) as Manager.Check.Ok
        assertEquals("staff", staffPin.staff.role)
        // The staff PIN opens the lock but never unlocks manager screens.
        assertTrue(Manager.check(c, "2580", Manager.MANAGERS) is Manager.Check.Wrong)
        assertTrue(Manager.check(c, "1357", Manager.MANAGERS, withStaffPin = true) is Manager.Check.Ok)

        AppLock.opened()
        assertFalse(AppLock.locked(c))
        AppLock.touch()
        assertFalse(AppLock.locked(c))
        rules("appLock" to false)
        assertFalse(AppLock.locked(c))
    }

    @Test fun approvalProofsMatchTheServersScheme() {
        // The same HMAC the server checks for record edits, over "approval:<action>|<bill uid>|<at>".
        val key = "680d7e50" + "0".repeat(56)
        val a = Manager.Approval(Rules.PRICE_EDIT, CloudSetup.Staff("usr_1", "Ravi", "manager", "", ""), key, 42)
        val j = a.toJson("bill_9")
        assertEquals(Pin.proof(key, "approval:priceEdit", "bill_9", 42), j.getString("proof"))
        assertNotNull(ApprovalNote.from(j).proof)
    }
}
