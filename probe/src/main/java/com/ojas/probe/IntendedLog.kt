package com.ojas.probe

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * What the probe MEANT to do, written down as it goes and signed, so "what PermSense should have
 * seen" is provable rather than asserted.
 *
 * One line per access, each carrying the hash of the line before it and a signature from a key held
 * in this phone's Keystore. The first line names the key (and, where the phone can, the attestation
 * chain that says it lives in secure hardware). A recipient checks the chain and the signatures
 * with `permsense-verify intended-log`; nothing here needs to be trusted beyond that key.
 *
 * The canonical form of each line is a fixed-order `|`-joined string, not the JSON text, so the
 * check does not depend on how a JSON library orders or spaces its keys. No field may contain `|`.
 */
class IntendedLog(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val lock = Any()

    /** One intended access. */
    data class Access(
        val tsMs: Long,
        val versionCode: Int,
        val op: String,
        val trigger: String,
        val step: String,
        val interactive: Boolean,
        val keyguard: Boolean,
        val importance: Int,
        val expectedStratum: String,
        /** OK, DENIED (permission not granted), or UNAVAILABLE (the phone could not do it). */
        val outcome: String,
        val detail: String,
    )

    fun record(access: Access) = synchronized(lock) {

        ensureHeader(access.versionCode)

        val lines = file.readLines().filter { it.isNotBlank() }
        val last = JSONObject(lines.last())
        val prev = sha256Hex(canonicalOf(last))
        val seq = if (last.getString("type") == "header") 1 else last.getInt("seq") + 1

        val canonical = listOf(
            "a1", seq, access.tsMs, access.versionCode, access.op, access.trigger, access.step,
            access.interactive, access.keyguard, access.importance, access.expectedStratum,
            access.outcome, clean(access.detail), prev,
        ).joinToString("|")

        val line = JSONObject()
            .put("type", "access")
            .put("seq", seq)
            .put("tsMs", access.tsMs)
            .put("versionCode", access.versionCode)
            .put("op", access.op)
            .put("trigger", access.trigger)
            .put("step", access.step)
            .put("interactive", access.interactive)
            .put("keyguard", access.keyguard)
            .put("importance", access.importance)
            .put("expectedStratum", access.expectedStratum)
            .put("outcome", access.outcome)
            .put("detail", clean(access.detail))
            .put("prev", prev)
            .put("sig", sign(canonical))

        file.appendText(line.toString() + "\n")
    }

    fun lineCount(): Int = synchronized(lock) { if (file.exists()) file.readLines().count { it.isNotBlank() } else 0 }

    fun file(): File = file

    /** Starts a fresh log (a new run of the test). The key stays; the first line says when it began. */
    fun reset() = synchronized(lock) { file.delete() }

    private fun ensureHeader(versionCode: Int) {
        if (file.exists() && file.length() > 0) return

        ensureKey()
        val store = keyStore()
        val publicKey = Base64.encodeToString(store.getCertificate(ALIAS).publicKey.encoded, Base64.NO_WRAP)
        val chain = store.getCertificateChain(ALIAS).orEmpty().map { Base64.encodeToString(it.encoded, Base64.NO_WRAP) }
        val createdMs = System.currentTimeMillis()

        val canonical = listOf("h1", createdMs, versionCode, publicKey, chain.size).joinToString("|")

        val header = JSONObject()
            .put("type", "header")
            .put("format", 1)
            .put("createdMs", createdMs)
            .put("versionCode", versionCode)
            .put("publicKey", publicKey)
            .put("attestation", JSONArray(chain))
            .put("sig", sign(canonical))

        file.writeText(header.toString() + "\n")
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun ensureKey() {
        val store = keyStore()
        if (store.containsAlias(ALIAS)) return

        fun spec(withChallenge: Boolean): KeyGenParameterSpec =
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .apply {
                    if (withChallenge) setAttestationChallenge(ByteArray(32).also { SecureRandom().nextBytes(it) })
                }
                .build()

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        try {
            generator.initialize(spec(withChallenge = true))
            generator.generateKeyPair()
        } catch (_: Exception) {
            // A phone that cannot attest still signs; the header then carries a one-certificate chain.
            generator.initialize(spec(withChallenge = false))
            generator.generateKeyPair()
        }
    }

    private fun sign(canonical: String): String {
        val key = keyStore().getKey(ALIAS, null) as PrivateKey
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(canonical.toByteArray(Charsets.UTF_8))
            sign()
        }
        return Base64.encodeToString(sig, Base64.NO_WRAP)
    }

    /** The canonical string of an already-written line, to chain the next line from it. */
    private fun canonicalOf(line: JSONObject): String =
        if (line.getString("type") == "header") {
            listOf(
                "h1", line.getLong("createdMs"), line.getInt("versionCode"),
                line.getString("publicKey"), line.getJSONArray("attestation").length(),
            ).joinToString("|")
        } else {
            listOf(
                "a1", line.getInt("seq"), line.getLong("tsMs"), line.getInt("versionCode"), line.getString("op"),
                line.getString("trigger"), line.getString("step"), line.getBoolean("interactive"),
                line.getBoolean("keyguard"), line.getInt("importance"), line.getString("expectedStratum"),
                line.getString("outcome"), line.getString("detail"), line.getString("prev"),
            ).joinToString("|")
        }

    private fun clean(text: String): String = text.replace(Regex("[^A-Za-z0-9_=.:,-]"), "_").take(80)

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val FILE_NAME = "intended_log.jsonl"
        private const val ALIAS = "quickcash_intended_log_v1"
    }
}
