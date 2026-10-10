package org.kaze.camkaze

import org.kaze.camkaze.crypto.KeyWrapper
import org.kaze.camkaze.crypto.Vault
import java.io.File
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Stand-in for the Android Keystore in plain JVM tests. */
class SoftwareWrapper : KeyWrapper {
    private var key: ByteArray? = null
    override fun wrap(plain: ByteArray): ByteArray {
        val k = key ?: ByteArray(32).also { SecureRandom().nextBytes(it); key = it }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, iv))
        return iv + c.doFinal(plain)
    }
    override fun unwrap(blob: ByteArray): ByteArray {
        val k = key ?: throw GeneralSecurityException("missing")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return c.doFinal(blob, 12, blob.size - 12)
    }
    override fun destroy() { key = null }
}

const val PW = "correct horse battery"

fun newVault(clock: () -> Long = { System.currentTimeMillis() }, wrapper: SoftwareWrapper = SoftwareWrapper()): Vault {
    val cfg = File.createTempFile("vault", ".cfg").also { it.delete() }
    return Vault(cfg, wrapper, iterations = 1000, clock = clock)
}
