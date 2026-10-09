package permsense.verifier

import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Builds evidence files from scratch, independently of the app, to test the verifier.
 *
 * Deliberately re-implements the file format from its written description rather than calling the
 * app's exporter, so the verifier is tested against the specification, not against the code that
 * happens to write it. (The app's exporter is cross-checked against this verifier separately.)
 */
class TestSigner(val keyPair: KeyPair, val chain: List<ByteArray> = emptyList(), val challenge: ByteArray? = null) {

    val publicKeyDer: ByteArray get() = keyPair.public.encoded

    fun sign(payload: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(payload)
            sign()
        }

    companion object {

        fun fresh(): TestSigner =
            TestSigner(KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair())

        /** The key certified by the OpenSSL-made fixtures. */
        fun fixtureKey(): KeyPair {
            val pk8 = Base64.getDecoder().decode(Fixtures.text("leaf_key.pk8.b64").trim())
            val private = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pk8))
            val public = Fixtures.cert("leaf_tee.pem").publicKey
            return KeyPair(public, private)
        }

        fun attested(leaf: String, vararg chain: String, challenge: ByteArray = Fixtures.CHALLENGE): TestSigner =
            TestSigner(
                fixtureKey(),
                chain = listOf(Fixtures.cert(leaf).encoded) + chain.map { Fixtures.cert(it).encoded },
                challenge = challenge,
            )
    }
}

object Fixtures {

    /** The challenge baked into the OpenSSL fixtures: bytes 01 02 ... 20. */
    val CHALLENGE: ByteArray = ByteArray(32) { (it + 1).toByte() }

    fun text(name: String): String =
        Fixtures::class.java.getResourceAsStream("/attestation/$name")!!.readBytes().toString(Charsets.UTF_8)

    fun cert(name: String): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(text(name).toByteArray())) as X509Certificate
}

object TestExports {

    const val GENESIS = EvidenceVerifier.GENESIS

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** A ledger row as a map, with its hash computed the way the written format defines it. */
    fun row(
        prev: String,
        index: Int,
        pkg: String = "com.example.app",
        op: String = "android:read_contacts",
        attributionTag: String? = null,
        timestampMs: Long = 1_800_000_000_000L + index * 60_000L,
    ): MutableMap<String, Any?> {
        val values = linkedMapOf<String, Any?>(
            "timestampMs" to timestampMs, "uid" to 10100L, "packageName" to pkg, "versionCode" to 1L,
            "op" to op, "accessKind" to "noted", "durationMs" to null, "attributionTag" to attributionTag,
            "flags" to 0L, "visibilityStratum" to "BACKGROUND", "screenState" to "SCREEN_ON",
            "sourceTier" to "T2", "observationCount" to 1L, "accessResult" to "OBSERVED",
            "sourceEventIds" to listOf("id-$index"), "recordKind" to "EPISODE", "userId" to 0L,
            "schemaVersion" to 3L, "prevHash" to prev,
        )
        values["hash"] = hash(values)
        return values
    }

    fun hash(r: Map<String, Any?>): String {
        @Suppress("UNCHECKED_CAST")
        val ids = (r["sourceEventIds"] as List<String>).distinct().sorted().joinToString(",")
        val fields = listOf(
            r["schemaVersion"], r["recordKind"], r["timestampMs"], r["userId"], r["uid"], r["packageName"],
            r["versionCode"], r["op"], r["accessKind"], r["durationMs"], r["attributionTag"], r["flags"],
            r["visibilityStratum"], r["screenState"], r["sourceTier"], r["observationCount"], r["accessResult"], ids,
        )
        val canonical = fields.joinToString("|") { if (it == null) "N" else it.toString().let { t -> "${t.length}:$t" } }
        return sha256Hex((r["prevHash"] as String).toByteArray() + byteArrayOf(0x1F) + canonical.toByteArray())
    }

    fun chain(count: Int): List<MutableMap<String, Any?>> {
        val rows = ArrayList<MutableMap<String, Any?>>()
        var prev = GENESIS
        for (i in 0 until count) {
            val r = row(prev, i)
            rows += r
            prev = r["hash"] as String
        }
        return rows
    }

    fun checkpoint(signer: TestSigner, rows: List<Map<String, Any?>>, upTo: Int): Map<String, Any?> {
        val hash = rows[upTo - 1]["hash"] as String
        val ts = 1_800_000_100_000L + upTo
        val payload = "$hash|$ts|$upTo".toByteArray()
        return mapOf(
            "hash" to hash, "timestampMs" to ts, "rowCount" to upTo.toLong(), "keyAlias" to "test",
            "signature" to b64(signer.sign(payload)), "publicKey" to b64(signer.publicKeyDer),
        )
    }

    /** Writes the two-line file. [edit] may change the body before it is sealed. */
    fun file(
        signer: TestSigner,
        rows: List<Map<String, Any?>> = chain(5),
        checkpoints: List<Map<String, Any?>>? = null,
        createdAtMs: Long = 1_800_000_200_000L,
        findings: List<Map<String, Any?>> = listOf(mapOf("evidenceHashes" to emptyList<String>())),
        edit: (MutableMap<String, Any?>) -> Unit = {},
    ): ByteArray {

        val cps = checkpoints ?: listOf(checkpoint(signer, rows, rows.size))

        val body = linkedMapOf<String, Any?>(
            "format" to "permsense-evidence",
            "formatVersion" to 1L,
            "createdAtMs" to createdAtMs,
            "generator" to mapOf("app" to "test"),
            "statement" to mapOf("en" to "x"),
            "ledger" to mapOf(
                "schemaVersion" to 3L, "genesisHash" to GENESIS, "startsAt" to "GENESIS",
                "firstPrevHash" to (rows.firstOrNull()?.get("prevHash") ?: GENESIS),
                "rowCount" to rows.size.toLong(), "rows" to rows,
            ),
            "checkpoints" to cps,
            "attestation" to mapOf(
                "challenge" to (signer.challenge?.let { b64(it) } ?: ""),
                "certificates" to signer.chain.map { b64(it) },
            ),
            "findings" to findings,
            "rulePack" to mapOf("id" to "t", "version" to "1", "rulesAwaitingReview" to emptyList<String>()),
        )
        edit(body)

        return seal(signer, body)
    }

    fun seal(signer: TestSigner, body: Map<String, Any?>): ByteArray {
        val bodyBytes = Writer.write(body).toByteArray(Charsets.UTF_8)
        val digest = sha256Hex(bodyBytes)
        val sig = signer.sign("permsense-evidence-seal|v1|$digest".toByteArray())
        val seal = mapOf(
            "algorithm" to "SHA-256", "digest" to digest, "publicKey" to b64(signer.publicKeyDer),
            "sealVersion" to 1L, "signature" to b64(sig),
        )
        return bodyBytes + byteArrayOf(0x0A) + Writer.write(seal).toByteArray() + byteArrayOf(0x0A)
    }

    /** A tiny JSON writer, independent of the app's. Numbers here are integers. */
    object Writer {
        fun write(v: Any?): String = when (v) {
            null -> "null"
            is Boolean, is Long, is Int -> v.toString()
            is String -> "\"" + v.flatMap { c -> if (c == '"' || c == '\\') listOf('\\', c) else if (c < ' ') "\\u%04x".format(c.code).toList() else listOf(c) }.joinToString("") + "\""
            is Map<*, *> -> v.entries.sortedBy { it.key as String }.joinToString(",", "{", "}") { write(it.key) + ":" + write(it.value) }
            is List<*> -> v.joinToString(",", "[", "]") { write(it) }
            else -> error("cannot write ${v::class}")
        }
    }

    fun publicKeyOf(der: ByteArray) = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
}
