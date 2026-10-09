package com.ojas.permsense.ledger

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Arrays
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * A cryptographically signed checkpoint anchoring the Ledger's latest hash.
 */
data class SignedCheckpoint(
    val hash: String,
    val signatureBytes: ByteArray,
    val timestampMs: Long,
    val rowCount: Long = 0,
    val keyAlias: String = "permsense_ledger_signer",
    val publicKeyDer: ByteArray? = null
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignedCheckpoint) return false

        if (hash != other.hash) return false
        if (!signatureBytes.contentEquals(other.signatureBytes)) return false
        if (timestampMs != other.timestampMs) return false
        if (rowCount != other.rowCount) return false
        if (keyAlias != other.keyAlias) return false
        if (publicKeyDer != null) {
            if (other.publicKeyDer == null) return false
            if (!publicKeyDer.contentEquals(other.publicKeyDer)) return false
        } else if (other.publicKeyDer != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = hash.hashCode()
        result = 31 * result + signatureBytes.contentHashCode()
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + rowCount.hashCode()
        result = 31 * result + keyAlias.hashCode()
        result = 31 * result + (publicKeyDer?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Standard byte layout for checkpoint signatures.
 */
fun checkpointSignPayload(
    hash: String,
    timestampMs: Long,
    rowCount: Long
): ByteArray {
    return "$hash|$timestampMs|$rowCount".toByteArray(Charsets.UTF_8)
}

/**
 * Signer interface for non-exportable hardware-backed or software ECDSA signing.
 */
interface LedgerSigner {

    val keyAlias: String

    /**
     * Signs arbitrary payload bytes using ECDSA with SHA-256.
     */
    fun sign(payload: ByteArray): ByteArray

    /**
     * Verifies an ECDSA signature over the payload bytes.
     */
    fun verify(payload: ByteArray, signature: ByteArray, publicKeyDer: ByteArray? = null): Boolean

    /**
     * Returns the public key in X.509 DER-encoded bytes.
     */
    fun getPublicKeyDer(): ByteArray

    /**
     * X.509 certificates (leaf first) proving where the signing key lives, or empty when the key
     * is a plain software key. Exported with the evidence so a verifier can tell a hardware-backed
     * key from one an attacker generated.
     */
    fun attestationChainDer(): List<ByteArray> = emptyList()

    /** The challenge that was bound into the attestation certificate, if any. */
    fun attestationChallenge(): ByteArray? = null
}

/**
 * Software-backed ECDSA signer (secp256r1) for JVM unit tests and offline verification.
 */
class SoftwareEcSigner(
    override val keyAlias: String = "permsense_software_signer",
    keyPair: KeyPair? = null
) : LedgerSigner {

    private val pair: KeyPair = keyPair ?: run {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        kpg.generateKeyPair()
    }

    override fun sign(payload: ByteArray): ByteArray {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(pair.private)
        signer.update(payload)
        return signer.sign()
    }

    override fun verify(
        payload: ByteArray,
        signature: ByteArray,
        publicKeyDer: ByteArray?
    ): Boolean {
        val verifier = Signature.getInstance("SHA256withECDSA")
        val keyToUse: PublicKey = if (publicKeyDer != null) {
            val spec = X509EncodedKeySpec(publicKeyDer)
            KeyFactory.getInstance("EC").generatePublic(spec)
        } else {
            pair.public
        }
        verifier.initVerify(keyToUse)
        verifier.update(payload)
        return try {
            verifier.verify(signature)
        } catch (_: Exception) {
            false
        }
    }

    override fun getPublicKeyDer(): ByteArray {
        return pair.public.encoded
    }
}

/**
 * Key manager for symmetric AES-256 database encryption keys.
 */
object LedgerKeyManager {

    private const val AES_KEY_SIZE = 256
    private const val PBKDF2_ITERATIONS = 10000

    /**
     * Generates a fresh cryptographically strong 256-bit AES key.
     */
    fun generateAesKey(): SecretKey {
        val kg = KeyGenerator.getInstance("AES")
        kg.init(AES_KEY_SIZE, SecureRandom())
        return kg.generateKey()
    }

    /**
     * Derives a 256-bit AES key deterministically from a passphrase and salt.
     */
    fun deriveKeyFromPassword(password: CharArray, salt: ByteArray): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, AES_KEY_SIZE)
        val secret = factory.generateSecret(spec)
        return SecretKeySpec(secret.encoded, "AES")
    }
}
