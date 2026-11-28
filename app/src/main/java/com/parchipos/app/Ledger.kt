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
    /** PINs that let a void or restore through (till rule voidReceipt). */
    val approvals: List<ApprovalNote> = emptyList(),
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

data class Account(
    val name: String, val billed: Double, val received: Double, val lastTime: Long, val bills: Int,
    /** "c:<customer id>" (a customer record, merges followed) or "n:<name>" (a name with no record). */
    val key: String = "n:" + Ledger.key(name),
    /** The customer record payments go on: the one any duplicates were merged into. */
    val customerId: String? = null,
) {
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

    fun setReceiptVoided(c: Context, uid: String, voided: Boolean, reason: String, approval: Manager.Approval? = null) {
        Store.tx(c) {
            val r = Store.receipt(c, uid) ?: return@tx
            // After the state it changes, whatever this phone's clock says (see Sales.editTime).
            val now = maxOf(Cloud.now(c), r.updatedAt + 1, (r.fieldTimes.values.maxOrNull() ?: 0) + 1)
            Store.saveReceipt(c, r.copy(
                voided = voided, voidReason = if (voided) reason else "", updatedAt = now,
                fieldTimes = r.fieldTimes.ifEmpty { Receipts.allGroups(r.updatedAt) } + ("void" to now),
                approvals = if (approval != null) r.approvals + ApprovalNote.from(approval.toJson(r.uid)) else r.approvals,
            ))
        }
    }

    /**
     * Customer records (Parchi Cloud): each id → the customer it counts as, following
     * merges made in the portal (a duplicate points at the real one), and their names.
     */
    private class Customers(val root: Map<String, String>, val name: Map<String, String>)

    private fun customers(c: Context): Customers {
        val recs = Store.records(c, "customer").associateBy { it.id }
        fun root(id: String): String {
            var cur = id
            repeat(10) {
                val next = recs[cur]?.data?.optStringOrNull("mergedInto") ?: return cur
                if (!recs.containsKey(next) || next == id) return cur
                cur = next
            }
            return cur
        }
        return Customers(recs.keys.associateWith(::root), recs.mapValues { it.value.data.optString("name").trim() })
    }

    /** Which account a bill or payment counts on: its customer (merges followed), or its name when it has no record. */
    private fun accountKey(cs: Customers, customerId: String?, name: String): String =
        customerId?.let { cs.root[it] }?.let { "c:$it" } ?: "n:" + key(name)

    /** Everyone who has ever bought on credit, biggest balance first; settled accounts last. */
    fun accounts(c: Context): List<Account> {
        val cs = customers(c)
        val sales = Sales.onCredit(c).filter { !it.voided && it.customer.isNotBlank() }
        val receipts = receipts(c).filterNot { it.voided }
        val names = LinkedHashMap<String, String>()
        // The name as last typed is the one shown, unless the account is a customer record: then its name.
        (sales.map { Triple(accountKey(cs, it.customerId, it.customer), it.customer, it.time) } + receipts.map { Triple(accountKey(cs, it.customerId, it.customer), it.customer, it.time) })
            .sortedBy { it.third }.forEach { (k, n, _) -> names[k] = n.trim() }
        return names.map { (k, typed) ->
            val mine = sales.filter { accountKey(cs, it.customerId, it.customer) == k }
            val paid = receipts.filter { accountKey(cs, it.customerId, it.customer) == k }
            val cid = k.removePrefix("c:").takeIf { k.startsWith("c:") }
            Account(
                name = cid?.let { cs.name[it] }?.ifBlank { null } ?: typed,
                billed = mine.sumOf { it.total },
                received = paid.sumOf { it.credited },
                lastTime = maxOf(mine.maxOfOrNull { it.time } ?: 0L, paid.maxOfOrNull { it.time } ?: 0L),
                bills = mine.size,
                key = k,
                customerId = cid,
            )
        }.sortedWith(compareByDescending<Account> { it.balance > 0.005 }.thenByDescending { it.balance }.thenByDescending { it.lastTime })
    }

    fun names(c: Context): List<String> = accounts(c).map { it.name }

    /**
     * The account a name means: the one shown under that name, else (connected) the customer the
     * till would make of it, merges followed, so a duplicate's name finds the real account.
     */
    fun account(c: Context, name: String): Account? {
        val all = accounts(c)
        all.find { key(it.name) == key(name) }?.let { return it }
        val me = Cloud.me(c) ?: return null
        val root = customers(c).root[com.parchipos.app.Customers.idFor(me.outletId, name)] ?: return null
        return all.find { it.key == "c:$root" }
    }

    fun balance(c: Context, name: String): Double = account(c, name)?.balance ?: 0.0

    fun balanceFor(c: Context, key: String): Double = accounts(c).find { it.key == key }?.balance ?: 0.0

    /** A customer's statement, voided bills and payments included (with no effect on the balance). */
    fun statement(c: Context, name: String): List<LedgerEntry> = statementFor(c, account(c, name)?.key ?: "n:" + key(name))

    fun statementFor(c: Context, accountKey: String): List<LedgerEntry> {
        val cs = customers(c)
        val sales = Sales.onCredit(c).filter { accountKey(cs, it.customerId, it.customer) == accountKey }
        val receipts = receipts(c).filter { accountKey(cs, it.customerId, it.customer) == accountKey }
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
