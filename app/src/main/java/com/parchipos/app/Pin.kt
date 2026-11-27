package com.parchipos.app

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Manager and owner PINs, exactly as Parchi Cloud derives them (src/auth.ts):
 *
 * - k = PBKDF2-HMAC-SHA256(pin, salt, 100,000 rounds, 32 bytes), as hex.
 * - The phone holds only the verifier, SHA-256 of that hex text, so it can
 *   check a typed PIN offline but cannot sign anything without the PIN.
 * - An edit that needs a manager carries proof = HMAC-SHA256(k, "kind|id|updatedAt").
 *
 * PBKDF2 is written out here because Android 7 has no PBKDF2WithHmacSHA256;
 * HMAC-SHA256 itself is everywhere.
 */
object Pin {
    const val ROUNDS = 100_000

    fun valid(pin: String): Boolean = Regex("^\\d{4,6}$").matches(pin)

    /** k as lower-case hex. Takes a moment on purpose: call it off the main thread. */
    fun key(pin: String, saltHex: String, rounds: Int = ROUNDS): String = hex(pbkdf2(pin.toByteArray(), unhex(saltHex), rounds))

    fun verifier(keyHex: String): String = hex(MessageDigest.getInstance("SHA-256").digest(keyHex.toByteArray()))

    /** Whether a typed PIN matches a staff record's salt and verifier. */
    fun matches(pin: String, saltHex: String, verifierHex: String): String? {
        val k = key(pin, saltHex)
        return if (MessageDigest.isEqual(verifier(k).toByteArray(), verifierHex.lowercase().toByteArray())) k else null
    }

    fun proof(keyHex: String, kind: String, id: String, updatedAt: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(unhex(keyHex), "HmacSHA256"))
        return hex(mac.doFinal("$kind|$id|$updatedAt".toByteArray()))
    }

    /** RFC 8018 PBKDF2 with HMAC-SHA256, one 32-byte block. */
    internal fun pbkdf2(password: ByteArray, salt: ByteArray, rounds: Int): ByteArray {
        require(password.isNotEmpty()) { "empty PIN" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1))
        var u = mac.doFinal()
        val out = u.copyOf()
        repeat(rounds - 1) {
            u = mac.doFinal(u)
            for (i in out.indices) out[i] = (out[i].toInt() xor u[i].toInt()).toByte()
        }
        return out
    }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
