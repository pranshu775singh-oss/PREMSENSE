package com.ojas.permsense.ledger

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException

import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Keeps the ledger's AES-256 key on disk only in wrapped form.
 *
 * The wrapping key is created inside the Android Keystore (StrongBox when the device has it) and
 * never leaves it. `ledger.key` therefore holds nothing useful on its own: copied off the phone it
 * cannot be opened, and a rooted file browser sees only ciphertext.
 *
 * Uses the platform Keystore directly rather than androidx.security-crypto, which is deprecated.
 *
 * Failure is loud. If the wrapped key exists but cannot be unwrapped (Keystore wiped, key lost
 * after a restore), this throws [LedgerStorageException.KeyInvalidatedException]. It never
 * generates a fresh key over an existing ledger, which would make the old evidence unreadable
 * without the user ever being told.
 */
class AndroidLedgerKeyProvider(
    context: Context,
) {

    private val keyFile = File(context.applicationContext.filesDir, KEY_FILE_NAME)

    /** True when a ledger key was created earlier on this device. */
    fun exists(): Boolean = keyFile.exists() && keyFile.length() > 0L

    fun loadOrCreate(): SecretKey {

        if (exists()) {
            return unwrap(keyFile.readBytes())
        }

        val wrappingKey = createWrappingKey()
        val ledgerKey = LedgerKeyManager.generateAesKey()
        keyFile.writeBytes(wrap(wrappingKey, ledgerKey.encoded))
        return ledgerKey
    }

    /** Deletes the wrapped key and its Keystore wrapping key. The ledger becomes unreadable. */
    fun destroy() {
        keyFile.delete()
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(WRAP_ALIAS)
        }
    }

    private fun unwrap(blob: ByteArray): SecretKey {

        if (blob.size <= IV_LENGTH) {
            throw LedgerStorageException.CorruptedStorageException("Wrapped ledger key file is truncated.")
        }

        val wrappingKey = try {
            val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            store.getKey(WRAP_ALIAS, null) as? SecretKey
        } catch (e: GeneralSecurityException) {
            throw LedgerStorageException.KeyInvalidatedException("Keystore wrapping key cannot be read.", e)
        } ?: throw LedgerStorageException.KeyInvalidatedException(
            "Keystore wrapping key is missing; the ledger key cannot be recovered."
        )

        return try {
            val iv = blob.copyOfRange(0, IV_LENGTH)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(TAG_BITS, iv))
            val raw = cipher.doFinal(blob, IV_LENGTH, blob.size - IV_LENGTH)
            SecretKeySpec(raw, "AES")
        } catch (e: GeneralSecurityException) {
            throw LedgerStorageException.KeyInvalidatedException("Ledger key failed to unwrap.", e)
        }
    }

    private fun wrap(wrappingKey: SecretKey, raw: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
        return cipher.iv + cipher.doFinal(raw)
    }

    private fun createWrappingKey(): SecretKey {

        fun spec(strongBox: Boolean): KeyGenParameterSpec =
            KeyGenParameterSpec.Builder(
                WRAP_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setIsStrongBoxBacked(strongBox)
                .build()

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)

        return try {
            generator.init(spec(strongBox = true))
            generator.generateKey()
        } catch (_: StrongBoxUnavailableException) {
            generator.init(spec(strongBox = false))
            generator.generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val WRAP_ALIAS = "permsense_ledger_wrap_v1"
        const val KEY_FILE_NAME = "ledger.key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}
