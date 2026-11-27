package com.parchipos.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

data class SaleLine(
    val name: String,
    /** Rupees each, as agreed at the counter. Stored as paise. */
    val price: Double,
    val qty: Int,
    /** "less spicy", "Mint mojito" — printed under the item on both slips. */
    val note: String = "",
    /** The menu item it was rung up from; null for a custom item. Reports follow renames by it. */
    val itemId: String? = null,
    /** The size or flavour picked, "Half"; null for a single-price item. */
    val variant: String? = null,
    /** Typed in at the counter rather than picked from the menu. */
    val custom: Boolean = false,
    /** GST percent in force when it was rung up; later rate changes never rewrite old bills. */
    val taxRate: Double? = null,
) {
    val amount: Double get() = price * qty
}

/** The payment mode of a bill put on the customer's account (udhaar). */
const val CREDIT = "CREDIT"

data class Sale(
    val id: String,
    val token: Int,
    val time: Long,
    val lines: List<SaleLine>,
    val payment: String,
    val customer: String,
    val voided: Boolean = false,
    /**
     * Never changes, unlike [id], which follows the bill to a new day. It is
     * how IceCreamOS tells this sale apart from every other phone's.
     */
    val uid: String = UUID.randomUUID().toString(),
    /** Bumped by every edit (void, payment, date); the cloud keeps the newest state. */
    val updatedAt: Long = System.currentTimeMillis(),
    /**
     * Token letter of the phone that rang it up (A, B, …), from IceCreamOS.
     * Empty before this phone first synced. Phones bill at the same time and
     * each counts tokens from 1, so the letter is what tells "A5" from "B5".
     */
    val letter: String = "",
    /** Empty for this phone's own bills; otherwise the name of the phone that rang it up. */
    val phone: String = "",
    /** Rupees off the whole bill, already worked out from a percent if one was given. */
    val discount: Double = 0.0,
    val discountReason: String = "",
    /** Why it was voided; required when voiding, cleared on restore. */
    val voidReason: String = "",
    /** Parchi Cloud id of the phone that rang it up; null before the cloud. */
    val deviceId: String? = null,
    /** That phone's name when it rang it up ("Counter"). */
    val origin: String = "",
    /** The customer record a credit bill goes on (Parchi Cloud); null for name-only bills. */
    val customerId: String? = null,
    /** "A/26-27/000123", from the first cloud connection on; null before. */
    val invoiceNo: String? = null,
    /** Edit time of each field group (void, payment, date, content), so edits on two phones both survive. */
    val fieldTimes: Map<String, Long> = emptyMap(),
) {
    /** What the slip and the screen call it: "A12", or "12" without a letter. */
    val label: String get() = letter + token
    val mine: Boolean get() = phone.isEmpty()
    /** Before the discount. */
    val subtotal: Double get() = lines.sumOf { it.amount }
    /** What the customer pays. */
    val total: Double get() = maxOf(0.0, subtotal - discount)
    /** On the customer's account rather than paid at the counter; see [Ledger]. */
    val onCredit: Boolean get() = payment == CREDIT
    val itemCount: Int get() = lines.sumOf { it.qty }
    /** The day it belongs to, as yyyyMMdd — what the Sales screen groups by. */
    val day: String get() = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(time))
}

/**
 * Every bill, in parchi.db (see [Store]).
 *
 * The functions are the ones the screens always called. Each edit stamps the
 * time on the field group it touched (void, payment, date), so an edit made
 * here and a different one made on another phone both survive a sync.
 */
object Sales {

    fun record(c: Context, sale: Sale) {
        Store.saveBill(c, sale.copy(fieldTimes = sale.fieldTimes.ifEmpty { Bills.allGroups(sale.updatedAt) }))
    }

    fun all(c: Context): List<Sale> = Store.bills(c)

    fun forDay(c: Context, day: String): List<Sale> = Store.bills(c, "day = ?", arrayOf(day))

    /** Credit bills, voided ones included, for the dues screens. */
    fun onCredit(c: Context): List<Sale> = Store.bills(c, "payment = ?", arrayOf(CREDIT))

    // Edits find a bill by uid: bill ids from before token letters repeat
    // across phones, and any phone may edit any phone's bill.

    fun setVoided(c: Context, uid: String, voided: Boolean, reason: String = "") = edit(c, uid, "void") {
        it.copy(voided = voided, voidReason = if (voided) reason else "")
    }

    /** For a bill rung up as Cash that was paid by UPI, or the other way round. */
    fun setPayment(c: Context, uid: String, payment: String) = edit(c, uid, "payment") { it.copy(payment = payment) }

    private fun edit(c: Context, uid: String, group: String, change: (Sale) -> Sale) {
        Store.tx(c) {
            val s = Store.bill(c, uid) ?: return@tx
            val now = editTime(c, s)
            Store.saveBill(c, change(s).copy(updatedAt = now, fieldTimes = stamp(s, group, now)))
        }
    }

    /**
     * When an edit happens: now, but always after the state it was made on.
     * Another phone's clock may be ahead of this one's, and an edit stamped
     * earlier than the bill it changed would lose every merge.
     */
    internal fun editTime(c: Context, s: Sale): Long = maxOf(Cloud.now(c), s.updatedAt + 1, (s.fieldTimes.values.maxOrNull() ?: 0) + 1)

    /** The bill's group times with [group] moved to [now]. */
    private fun stamp(s: Sale, group: String, now: Long): Map<String, Long> =
        s.fieldTimes.ifEmpty { Bills.allGroups(s.updatedAt) } + (group to now)

    /** "yyMMdd-A007": date, the phone's letter, the token. Unique across phones. */
    fun billId(time: Long, letter: String, token: Int): String =
        SimpleDateFormat("yyMMdd", Locale.US).format(Date(time)) + "-" + letter + token.toString().padStart(3, '0')

    /** Highest token one phone (this one when [phone] is empty) used on [day]; tokens only need to be unique per phone. */
    fun maxToken(c: Context, day: String, phone: String, letter: String): Int = Store.maxToken(c, day, phone, letter)

    /**
     * Merges bills from the IceCreamOS feed: other phones' bills, and this
     * phone's own if another phone edited them. Whichever state is newer wins,
     * so an edit made here and not yet sent is kept. Returns the uids whose
     * local state now equals the server's, which need no sending.
     */
    fun applyRemote(c: Context, remote: List<Sale>): Set<String> {
        if (remote.isEmpty()) return emptySet()
        return Store.tx(c) {
            val settled = HashSet<String>()
            for (r in remote) {
                val l = Store.bill(c, r.uid)
                when {
                    l == null || l.updatedAt < r.updatedAt -> {
                        // The feed knows nothing of cloud ids; keep any this phone has.
                        val merged = r.copy(
                            origin = r.origin.ifBlank { l?.origin?.ifBlank { null } ?: r.phone },
                            deviceId = l?.deviceId,
                            customerId = l?.customerId,
                            invoiceNo = l?.invoiceNo,
                            fieldTimes = Bills.allGroups(r.updatedAt),
                        )
                        Store.saveBill(c, merged, dirty = false)
                        settled.add(r.uid)
                    }
                    l.updatedAt == r.updatedAt -> settled.add(r.uid)
                }
            }
            settled
        }
    }

    /**
     * Moves a bill to another day, keeping its clock time. It takes the next
     * token on that day, and a new id to match, since the id is date + token
     * and must not collide with a bill already there.
     */
    fun moveToDay(c: Context, uid: String, day: Long): Sale? = Store.tx(c) {
        val old = Store.bill(c, uid) ?: return@tx null
        val time = Calendar.getInstance().apply {
            val clock = Calendar.getInstance().apply { timeInMillis = old.time }
            timeInMillis = day
            set(Calendar.HOUR_OF_DAY, clock.get(Calendar.HOUR_OF_DAY))
            set(Calendar.MINUTE, clock.get(Calendar.MINUTE))
            set(Calendar.SECOND, clock.get(Calendar.SECOND))
        }.timeInMillis
        val key = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(time))
        if (key == old.day) return@tx old
        // Next token of the same phone on that day, keeping its letter.
        val token = maxToken(c, key, old.phone, old.letter) + 1
        val now = editTime(c, old)
        // Into another financial year: the invoice number restarts in that year's series.
        val invoice = old.invoiceNo?.let { no -> if (Protocol.parseInvoice(no)?.fy == Protocol.fyOf(key)) no else Cloud.nextInvoice(c, key) }
        val moved = old.copy(
            id = billId(time, old.letter, token),
            token = token,
            time = time,
            updatedAt = now,
            fieldTimes = stamp(old, "date", now),
            invoiceNo = invoice,
        )
        Store.saveBill(c, moved)
        moved
    }

    /** One row per item line, which is what a spreadsheet wants to pivot on. */
    fun toCsv(sales: List<Sale>): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val time = SimpleDateFormat("HH:mm", Locale.US)
        val sb = StringBuilder("date,time,token,bill_id,phone,customer,payment,status,void_reason,item,note,qty,rate,amount,bill_discount,discount_reason,bill_total\n")
        for (s in sales) {
            val d = Date(s.time)
            for (l in s.lines) {
                sb.append(listOf(
                    date.format(d), time.format(d), s.label, s.id, s.phone.ifEmpty { "this phone" }, s.customer, s.payment,
                    if (s.voided) "VOID" else "OK", s.voidReason,
                    l.name, l.note, l.qty.toString(), money(l.price), money(l.amount),
                    money(s.discount), s.discountReason, money(s.total),
                ).joinToString(",") { csvCell(it) }).append('\n')
            }
        }
        return sb.toString()
    }

    fun money(n: Double): String = String.format(Locale.US, "%.2f", n)

    private fun csvCell(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
