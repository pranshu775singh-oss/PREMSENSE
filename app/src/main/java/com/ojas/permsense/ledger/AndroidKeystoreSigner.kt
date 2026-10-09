package com.ojas.permsense.ledger

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException

import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Signs ledger checkpoints with a non-exportable ECDSA P-256 key that lives in the Android
 * Keystore (StrongBox when the phone has it, otherwise the TEE).
 *
 * The key is generated with an attestation challenge. Keystore then issues a certificate chain
 * that names the hardware security level and the challenge, which the offline verifier checks.
 * That is what separates "signed by this phone's secure hardware" from "signed by any key".
 *
 * Fails loudly. There is deliberately no software fallback: a silent software key would sign
 * checkpoints that look Keystore-backed but are not, and would differ on every launch so no
 * earlier checkpoint could ever be verified again.
 */
class AndroidKeystoreSigner(
    context: Context,
    override val keyAlias: String = DEFAULT_ALIAS,
) : LedgerSigner {

    private val challengeFile = File(context.applicationContext.filesDir, CHALLENGE_FILE)

    private val keyStore: KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private val challenge: ByteArray

    init {
        challenge = loadOrCreateChallenge()
        if (!keyStore.containsAlias(keyAlias)) {
            generateKey()
        }
    }

    override fun sign(payload: ByteArray): ByteArray {
        val key = keyStore.getKey(keyAlias, null) as? PrivateKey
            ?: throw IllegalStateException("Checkpoint signing key $keyAlias is missing from the Keystore.")
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(payload)
            sign()
        }
    }

    override fun verify(payload: ByteArray, signature: ByteArray, publicKeyDer: ByteArray?): Boolean {
        val key = if (publicKeyDer != null) {
            java.security.KeyFactory.getInstance("EC")
                .generatePublic(java.security.spec.X509EncodedKeySpec(publicKeyDer))
        } else {
            keyStore.getCertificate(keyAlias).publicKey
        }
        return try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(payload)
                verify(signature)
            }
        } catch (_: java.security.GeneralSecurityException) {
            false
        }
    }

    override fun getPublicKeyDer(): ByteArray = keyStore.getCertificate(keyAlias).publicKey.encoded

    override fun attestationChainDer(): List<ByteArray> =
        keyStore.getCertificateChain(keyAlias).orEmpty().map { it.encoded }

    override fun attestationChallenge(): ByteArray = challenge.copyOf()

    private fun loadOrCreateChallenge(): ByteArray {
        if (challengeFile.exists() && challengeFile.length() == CHALLENGE_BYTES.toLong()) {
            return challengeFile.readBytes()
        }
        return ByteArray(CHALLENGE_BYTES).also {
            SecureRandom().nextBytes(it)
            challengeFile.writeBytes(it)
        }
    }

    private fun generateKey() {

        fun spec(strongBox: Boolean): KeyGenParameterSpec =
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAttestationChallenge(challenge)
                .setIsStrongBoxBacked(strongBox)
                .build()

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)

        try {
            generator.initialize(spec(strongBox = true))
            generator.generateKeyPair()
        } catch (_: StrongBoxUnavailableException) {
            generator.initialize(spec(strongBox = false))
            generator.generateKeyPair()
        }
    }

    companion object {
        const val DEFAULT_ALIAS = "permsense_checkpoint_signer_v2"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val CHALLENGE_BYTES = 32
        private const val CHALLENGE_FILE = "checkpoint.challenge"

        /**
         * Deletes the signing key and its attestation challenge (part of the one-tap wipe). Exports
         * made earlier still verify: each carries the public key and certificate chain it needs.
         */
        fun destroy(context: Context, keyAlias: String = DEFAULT_ALIAS) {
            runCatching {
                KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(keyAlias)
            }
            File(context.applicationContext.filesDir, CHALLENGE_FILE).delete()
        }
    }
}
