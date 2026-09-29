package com.coolcupman.dnasq

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM key that never leaves secure hardware (StrongBox when the phone has it, otherwise the
 * TEE). Every single encrypt/decrypt needs a fresh strong-biometric or device-credential
 * authentication, the key only works while the device is unlocked, and it is destroyed if new
 * fingerprints / faces are enrolled.
 */
object KeystoreVault {
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS_PREFIX = "dnasq.template.aes256gcm."
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val GCM_TAG_BITS = 128

    private val keyStore: KeyStore get() = KeyStore.getInstance(PROVIDER).apply { load(null) }

    /**
     * Fresh hardware key for every enrollment, under a new alias so that an abandoned enrollment
     * never destroys the key of the template currently in use.
     */
    fun newEncryptCipher(): Pair<String, Cipher> {
        val alias = ALIAS_PREFIX + System.currentTimeMillis()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, createKey(alias)) }
        return alias to cipher
    }

    fun decryptCipher(alias: String, iv: ByteArray): Cipher {
        val key = keyStore.getKey(alias, null) as? SecretKey
            ?: throw IllegalStateException("No DNA key present; enroll first")
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
    }

    /** Deletes every DNAsq key except [keep]. */
    fun deleteKeysExcept(keep: String?) {
        val ks = keyStore
        ks.aliases().toList()
            .filter { it.startsWith(ALIAS_PREFIX) && it != keep }
            .forEach(ks::deleteEntry)
    }

    /** Human readable description of where the key lives, or null if there is no key. */
    fun securityLevel(alias: String): String? {
        val key = keyStore.getKey(alias, null) as? SecretKey ?: return null
        val info = SecretKeyFactory.getInstance(key.algorithm, PROVIDER)
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            @Suppress("DEPRECATION")
            return if (info.isInsideSecureHardware) "Secure hardware" else "Software (no secure hardware!)"
        }
        return when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox secure element"
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "Trusted Execution Environment"
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> "Software (no secure hardware!)"
            else -> "Unknown"
        }
    }

    private fun createKey(alias: String): SecretKey = try {
        generate(alias, strongBox = true)
    } catch (e: StrongBoxUnavailableException) {
        generate(alias, strongBox = false)
    }

    private fun generate(alias: String, strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(
                0, // 0 = authenticate for every single use of the key
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
            .setInvalidatedByBiometricEnrollment(true)
            .setUnlockedDeviceRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            .apply { init(spec) }
            .generateKey()
    }
}
