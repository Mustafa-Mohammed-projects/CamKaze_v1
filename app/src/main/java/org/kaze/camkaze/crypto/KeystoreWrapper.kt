package org.kaze.camkaze.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM key that lives inside Android Keystore (StrongBox if the phone has it, else the
 * TEE). It cannot be exported, so a copy of the app's files is useless on any other device.
 */
class KeystoreWrapper(private val alias: String = "kaze_vault_wrap_v2") : KeyWrapper {

    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(create: Boolean): SecretKey? {
        (store().getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) return null
        return generate(strongBox = true) ?: generate(strongBox = false)
    }

    private fun generate(strongBox: Boolean): SecretKey? = try {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply { init(spec) }.generateKey()
    } catch (e: Exception) {
        if (strongBox) null else throw e
    }

    override fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true)!!)
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun unwrap(blob: ByteArray): ByteArray {
        val k = key(create = false) ?: throw GeneralSecurityException("Device key missing")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return cipher.doFinal(blob, 12, blob.size - 12)
    }

    override fun destroy() {
        val ks = store()
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }
}
