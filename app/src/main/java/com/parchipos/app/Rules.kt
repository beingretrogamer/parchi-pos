package com.parchipos.app

import android.content.Context

/**
 * The till rules (Parchi Cloud): what anyone at the counter may do, and what
 * needs an owner's or manager's PIN at that moment. The owner sets them in
 * the portal; until then the server's defaults apply (src/protocol.ts,
 * DEFAULT_RULES), copied here.
 *
 * A device of its own, never connected, has no rules: everything is allowed,
 * exactly as before.
 */
object Rules {
    const val OPEN_ITEMS = "openItems"
    const val PRICE_EDIT = "priceEdit"
    const val DISCOUNT = "discount"
    const val BACK_DATE = "backDate"
    const val VOID_BILL = "voidBill"
    const val CHANGE_PAYMENT = "changePayment"
    const val VOID_RECEIPT = "voidReceipt"
    const val SOLD_OUT = "soldOut"
    const val STAFF_SEES_TOTALS = "staffSeesTotals"

    private const val MANAGER = "manager"
    private const val ALLOW = "allow"

    private val DEFAULTS = mapOf(
        OPEN_ITEMS to ALLOW, PRICE_EDIT to MANAGER, DISCOUNT to MANAGER, BACK_DATE to MANAGER,
        VOID_BILL to ALLOW, CHANGE_PAYMENT to ALLOW, VOID_RECEIPT to MANAGER, SOLD_OUT to ALLOW, STAFF_SEES_TOTALS to ALLOW,
    )
    private const val DEFAULT_DISCOUNT_MAX_PCT = 10.0
    private const val DEFAULT_IDLE_MINUTES = 10

    /** Whether [rule] needs a PIN at the moment it is used. */
    fun needsPin(c: Context, rule: String): Boolean {
        if (!Cloud.connected(c)) return false
        val set = Cloud.rules(c)?.optString(rule)?.takeIf { it == ALLOW || it == MANAGER }
        return (set ?: DEFAULTS[rule] ?: ALLOW) == MANAGER
    }

    /** Discounts up to this percent of the bill never need a PIN. */
    fun discountMaxPct(c: Context): Double =
        Cloud.rules(c)?.optDouble("discountMaxPct")?.takeUnless(Double::isNaN)?.coerceIn(0.0, 100.0) ?: DEFAULT_DISCOUNT_MAX_PCT

    /** Whether a discount of [off] rupees on [subtotal] needs a PIN. */
    fun discountNeedsPin(c: Context, off: Double, subtotal: Double): Boolean =
        off > 0 && subtotal > 0 && needsPin(c, DISCOUNT) && off * 100 / subtotal > discountMaxPct(c) + 1e-9

    /** The app asks for a PIN when opened and after sitting unused (connected devices only). */
    fun appLock(c: Context): Boolean = Cloud.connected(c) && Cloud.rules(c)?.optBoolean("appLock", false) == true

    fun appLockIdleMs(c: Context): Long =
        (Cloud.rules(c)?.optInt("appLockIdleMinutes", DEFAULT_IDLE_MINUTES) ?: DEFAULT_IDLE_MINUTES).coerceIn(1, 240) * 60_000L
}
