package com.parchipos.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Money a credit customer paid against what they owe, or an amount let go when settling. */
data class Receipt(
    val uid: String = UUID.randomUUID().toString(),
    val time: Long = System.currentTimeMillis(),
    val customer: String,
    /** Rupees actually received. */
    val amount: Double,
    /** Rupees let go when settling up ("round it off"); reduces the balance like money received. */
    val discount: Double = 0.0,
    /** CASH or UPI. */
    val mode: String = "CASH",
    val note: String = "",
    val voided: Boolean = false,
    val voidReason: String = "",
    /** Bumped by a void or restore, which syncs like a bill edit. */
    val updatedAt: Long = time,
    /** Edit time of each field group (void, content). */
    val fieldTimes: Map<String, Long> = emptyMap(),
    /** Parchi Cloud id of the phone that took it. */
    val deviceId: String? = null,
    /** The customer record it was paid against (Parchi Cloud); null for name-only. */
    val customerId: String? = null,
) {
    val credited: Double get() = amount + discount
    val day: String get() = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(time))
}

/** One row of a customer's account, oldest first, with the balance after it. */
data class LedgerEntry(
    val time: Long,
    /** "Bill A12" or "Received · UPI". */
    val label: String,
    /** What it added to the balance (a credit bill). */
    val debit: Double,
    /** What it took off (a payment received, a discount on settling). */
    val credit: Double,
    val balance: Double,
    val sale: Sale? = null,
    val receipt: Receipt? = null,
)

data class Account(val name: String, val billed: Double, val received: Double, val lastTime: Long, val bills: Int) {
    val balance: Double get() = billed - received
}

/**
 * Credit (udhaar) accounts.
 *
 * What a customer owes is worked out every time from their bills marked
 * [CREDIT] and the payments recorded here, never stored, so voiding a bill or
 * a payment corrects the balance by itself. Customers are matched by name,
 * ignoring case and extra spaces, which is why the till suggests names that
 * already have an account.
 */
object Ledger {

    fun key(name: String): String = name.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    fun record(c: Context, r: Receipt) {
        Store.saveReceipt(c, r.copy(fieldTimes = r.fieldTimes.ifEmpty { Receipts.allGroups(r.updatedAt) }))
    }

    fun receipts(c: Context): List<Receipt> = Store.receipts(c)

    fun setReceiptVoided(c: Context, uid: String, voided: Boolean, reason: String) {
        Store.tx(c) {
            val r = Store.receipt(c, uid) ?: return@tx
            // After the state it changes, whatever this phone's clock says (see Sales.editTime).
            val now = maxOf(System.currentTimeMillis(), r.updatedAt + 1, (r.fieldTimes.values.maxOrNull() ?: 0) + 1)
            Store.saveReceipt(c, r.copy(
                voided = voided, voidReason = if (voided) reason else "", updatedAt = now,
                fieldTimes = r.fieldTimes.ifEmpty { Receipts.allGroups(r.updatedAt) } + ("void" to now),
            ))
        }
    }

    /** Everyone who has ever bought on credit, biggest balance first; settled accounts last. */
    fun accounts(c: Context): List<Account> {
        val sales = Sales.onCredit(c).filter { !it.voided && it.customer.isNotBlank() }
        val receipts = receipts(c).filterNot { it.voided }
        val names = LinkedHashMap<String, String>()
        // The name as last typed is the one shown.
        (sales.map { it.customer to it.time } + receipts.map { it.customer to it.time })
            .sortedBy { it.second }.forEach { (n, _) -> names[key(n)] = n.trim() }
        return names.map { (k, name) ->
            val mine = sales.filter { key(it.customer) == k }
            val paid = receipts.filter { key(it.customer) == k }
            Account(
                name = name,
                billed = mine.sumOf { it.total },
                received = paid.sumOf { it.credited },
                lastTime = maxOf(mine.maxOfOrNull { it.time } ?: 0L, paid.maxOfOrNull { it.time } ?: 0L),
                bills = mine.size,
            )
        }.sortedWith(compareByDescending<Account> { it.balance > 0.005 }.thenByDescending { it.balance }.thenByDescending { it.lastTime })
    }

    fun names(c: Context): List<String> = accounts(c).map { it.name }

    fun balance(c: Context, name: String): Double =
        accounts(c).find { key(it.name) == key(name) }?.balance ?: 0.0

    /** A customer's statement, voided bills and payments included (with no effect on the balance). */
    fun statement(c: Context, name: String): List<LedgerEntry> {
        val k = key(name)
        val sales = Sales.onCredit(c).filter { key(it.customer) == k }
        val receipts = receipts(c).filter { key(it.customer) == k }
        val events = sales.map { it.time to (it as Any) } + receipts.map { it.time to (it as Any) }
        var bal = 0.0
        return events.sortedBy { it.first }.map { (t, e) ->
            when (e) {
                is Sale -> {
                    val d = if (e.voided) 0.0 else e.total
                    bal += d
                    LedgerEntry(t, "Bill ${e.label}" + if (e.voided) " (void)" else "", d, 0.0, bal, sale = e)
                }
                else -> {
                    val r = e as Receipt
                    val cr = if (r.voided) 0.0 else r.credited
                    bal -= cr
                    val what = "Received · ${r.mode}" +
                        (if (r.discount > 0) " · disc ${Sales.money(r.discount)}" else "") +
                        (if (r.voided) " (void)" else "")
                    LedgerEntry(t, what, 0.0, cr, bal, receipt = r)
                }
            }
        }
    }
}
