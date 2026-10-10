package org.kaze.camkaze.crypto

import java.io.File
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class VaultException(message: String, cause: Throwable? = null) : Exception(message, cause)
class VaultLockedException : Exception("Vault is locked")

/** Device-bound key wrapper (Android Keystore on the phone, a software key in unit tests). */
interface KeyWrapper {
    fun wrap(plain: ByteArray): ByteArray
    /** Throws GeneralSecurityException if the device key is missing or cannot decrypt. */
    fun unwrap(blob: ByteArray): ByteArray
    fun destroy()
}

sealed class UnlockResult {
    data object Success : UnlockResult()
    data class Wrong(val failedAttempts: Int, val attemptsBeforeWipe: Int?) : UnlockResult()
    data class Throttled(val waitMs: Long) : UnlockResult()
    data object Wiped : UnlockResult()
    data object DeviceKeyLost : UnlockResult()
}

/**
 * Key hierarchy (v2)
 *
 *   VK      random 256-bit vault key (never derived from the password)
 *   pwKey   = PBKDF2-HMAC-SHA256(password, salt, 600k)
 *   inner   = AES-GCM(pwKey, VK)
 *   outer   = KeyWrapper.wrap(inner)      <- Android Keystore AES key, not exportable
 *   config  stores only salt + outer.     Cracking a stolen copy of the files needs BOTH the
 *           password AND the original phone's Keystore (guessing off-device is useless).
 *
 *   fileKey = HMAC-SHA256(VK, fileSalt || "kaze/file")   one fresh key per file
 *
 * Failed unlock attempts are counted BEFORE the check (killing the app does not help),
 * persisted, and throttled; optionally the vault self-destructs after MAX_ATTEMPTS.
 */
class Vault(
    private val configFile: File,
    private val wrapper: KeyWrapper,
    private val iterations: Int = KDF_ITERATIONS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        const val KDF_ITERATIONS = 600_000
        const val MIN_PASSWORD_LEN = 12
        const val MAX_ATTEMPTS = 30
        private const val FREE_ATTEMPTS = 4
        private const val MAX_DELAY_MS = 3_600_000L

        private val MAGIC = byteArrayOf(0x4B, 0x41, 0x5A, 0x45) // "KAZE"
        private const val VERSION: Byte = 2
        private const val SALT_LEN = 16
        private const val NONCE_LEN = 12
        private const val TAG_LEN = 16
        const val HEADER_LEN = 4 + 1 + SALT_LEN + NONCE_LEN // 33
        private val AAD_VK = "kaze/vk/v2".toByteArray()
        private val FILE_LABEL = "kaze/file".toByteArray()

        /** 0 for the first 4 failures, then 30 s doubling per failure, capped at 1 hour. */
        fun delayForFailures(n: Int): Long =
            if (n <= FREE_ATTEMPTS) 0L
            else minOf(30_000L shl (n - FREE_ATTEMPTS - 1).coerceAtMost(20), MAX_DELAY_MS)
    }

    private val random = SecureRandom()
    private val mutex = Any()
    private val attemptsFile = File(configFile.path + ".attempts")
    @Volatile private var key: ByteArray? = null
    @Volatile private var lockPending = false
    private var holds = 0

    var onLock: (() -> Unit)? = null
    var onWipe: (() -> Unit)? = null

    val isConfigured: Boolean get() = configFile.isFile
    val isUnlocked: Boolean get() = key != null && !lockPending
    val failedAttempts: Int get() = readAttempts().first

    var wipeEnabled: Boolean
        get() = isConfigured && readCfg()["wipe"] == "1"
        set(value) {
            check(isUnlocked) { "Unlock first" }
            val m = readCfg().toMutableMap()
            m["wipe"] = if (value) "1" else "0"
            writeCfg(m)
        }

    fun throttleRemainingMs(): Long {
        val (count, last) = readAttempts()
        return maxOf(0L, last + delayForFailures(count) - clock())
    }

    // ------------------------------------------------------------------ lifecycle
    fun create(password: CharArray, wipeAfterMaxAttempts: Boolean) {
        require(password.size >= MIN_PASSWORD_LEN) { "Password must be at least $MIN_PASSWORD_LEN characters" }
        check(!isConfigured) { "Vault already exists" }
        wrapper.destroy() // always start with a brand-new device key
        val salt = rand(SALT_LEN)
        val vk = rand(32)
        val pwKey = derive(password, salt, iterations)
        try {
            val outer = wrapper.wrap(seal(pwKey, vk, AAD_VK))
            val b64 = Base64.getEncoder()
            writeCfg(linkedMapOf(
                "v" to "2", "iter" to iterations.toString(),
                "salt" to b64.encodeToString(salt), "wrapped" to b64.encodeToString(outer),
                "wipe" to if (wipeAfterMaxAttempts) "1" else "0",
            ))
            writeAttempts(0, 0L)
            setKey(vk)
        } finally {
            pwKey.fill(0)
        }
    }

    fun unlock(password: CharArray): UnlockResult {
        val cfg = readCfg()
        val wipe = cfg["wipe"] == "1"
        val (before, last) = readAttempts()
        if (wipe && before >= MAX_ATTEMPTS) { wipeNow(); return UnlockResult.Wiped }
        val wait = last + delayForFailures(before) - clock()
        if (wait > 0) return UnlockResult.Throttled(wait)

        val count = before + 1
        writeAttempts(count, clock())                    // counted BEFORE verifying
        val b64 = Base64.getDecoder()
        val pwKey = derive(password, b64.decode(cfg.getValue("salt")), cfg.getValue("iter").toInt())
        try {
            val inner = try {
                wrapper.unwrap(b64.decode(cfg.getValue("wrapped")))
            } catch (e: GeneralSecurityException) {
                writeAttempts(before, last)              // not the user's fault
                return UnlockResult.DeviceKeyLost
            }
            val vk = try { open(pwKey, inner, AAD_VK) } catch (e: GeneralSecurityException) { null }
            if (vk != null) {
                writeAttempts(0, 0L)
                setKey(vk)
                return UnlockResult.Success
            }
            if (wipe && count >= MAX_ATTEMPTS) { wipeNow(); return UnlockResult.Wiped }
            return UnlockResult.Wrong(count, if (wipe) MAX_ATTEMPTS - count else null)
        } finally {
            pwKey.fill(0)
        }
    }

    /** Zero the key now - or as soon as the last [hold] is released. */
    fun lock() {
        synchronized(mutex) {
            if (holds > 0) {
                lockPending = true
            } else {
                zeroKey()
            }
        }
        onLock?.invoke()
    }

    /** Keep the key alive while a long job (e.g. encrypting a video) is running. */
    fun hold() {
        synchronized(mutex) { holds++ }
    }

    fun release() {
        synchronized(mutex) {
            holds = maxOf(0, holds - 1)
            if (holds == 0 && lockPending) {
                zeroKey()
            }
        }
    }

    /** Erase everything: secure files (via [onWipe]), then the config, counters and device key. */
    fun wipeNow() {
        try { onWipe?.invoke() } finally { destroyAll() }
    }

    // ------------------------------------------------------------------ crypto
    fun fileKey(salt: ByteArray): ByteArray = hmac(requireKey(), salt + FILE_LABEL)

    /** .kaze v2: "KAZE" | 2 | fileSalt(16) | nonce(12) | ciphertext | tag(16); header is AAD. */
    fun encrypt(plain: ByteArray): ByteArray {
        val salt = rand(SALT_LEN)
        val nonce = rand(NONCE_LEN)
        val fk = fileKey(salt)
        try {
            val header = MAGIC + VERSION + salt + nonce
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(fk, "AES"), GCMParameterSpec(TAG_LEN * 8, nonce))
            cipher.updateAAD(header)
            return header + cipher.doFinal(plain)
        } finally {
            fk.fill(0)
        }
    }

    fun decrypt(blob: ByteArray): ByteArray {
        if (blob.size < HEADER_LEN + TAG_LEN || !blob.copyOfRange(0, 4).contentEquals(MAGIC)) {
            throw VaultException("Not a .kaze file")
        }
        if (blob[4] != VERSION) throw VaultException("Unsupported .kaze version ${blob[4]}")
        val fk = fileKey(blob.copyOfRange(5, 5 + SALT_LEN))
        try {
            val nonce = blob.copyOfRange(5 + SALT_LEN, HEADER_LEN)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(fk, "AES"), GCMParameterSpec(TAG_LEN * 8, nonce))
            cipher.updateAAD(blob, 0, HEADER_LEN)
            return cipher.doFinal(blob, HEADER_LEN, blob.size - HEADER_LEN)
        } catch (e: GeneralSecurityException) {
            throw VaultException("Corrupted file or wrong key", e)
        } finally {
            fk.fill(0)
        }
    }

    // ------------------------------------------------------------------ internals
    private fun requireKey(): ByteArray = key ?: throw VaultLockedException()

    private fun setKey(k: ByteArray) {
        synchronized(mutex) {
            lockPending = false
            key = k
        }
    }

    private fun zeroKey() {
        key?.fill(0)
        key = null
        lockPending = false
    }

    private fun destroyAll() {
        synchronized(mutex) { zeroKey() }
        try { wrapper.destroy() } catch (_: Exception) {}
        configFile.delete()
        attemptsFile.delete()
        File(configFile.path + ".tmp").delete()
    }

    private fun rand(n: Int) = ByteArray(n).also(random::nextBytes)

    private fun derive(password: CharArray, salt: ByteArray, iter: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iter, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun hmac(k: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(k, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun seal(k: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        val nonce = rand(NONCE_LEN)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(TAG_LEN * 8, nonce))
        c.updateAAD(aad)
        return nonce + c.doFinal(plain)
    }

    private fun open(k: ByteArray, blob: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(TAG_LEN * 8, blob.copyOfRange(0, NONCE_LEN)))
        c.updateAAD(aad)
        return c.doFinal(blob, NONCE_LEN, blob.size - NONCE_LEN)
    }

    private fun parse(lines: List<String>) =
        lines.filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun readCfg(): Map<String, String> = parse(configFile.readLines())

    private fun writeCfg(m: Map<String, String>) {
        val tmp = File(configFile.path + ".tmp")
        tmp.writeText(m.entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" })
        if (!tmp.renameTo(configFile)) throw VaultException("Could not write vault config")
    }

    private fun readAttempts(): Pair<Int, Long> = try {
        val m = parse(attemptsFile.readLines())
        (m["count"]?.toInt() ?: 0) to (m["last"]?.toLong() ?: 0L)
    } catch (e: Exception) {
        0 to 0L
    }

    private fun writeAttempts(count: Int, last: Long) {
        attemptsFile.writeText("count=$count\nlast=$last\n")
    }
}

inline fun <T> Vault.holding(block: () -> T): T {
    hold()
    try { return block() } finally { release() }
}
