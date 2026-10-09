package permsense.verifier

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Date

enum class Level {
    /** Something does not check out. Do not rely on this report. */
    REJECTED,

    /**
     * The file is internally consistent and signed: no row, checkpoint or finding has been altered
     * since it was written. This does NOT show the file came from a genuine device, because anyone
     * can build a consistent, signed file with a key of their own.
     */
    INTEGRITY_VERIFIED,

    /** As above, and the signing key is proven to live in secure hardware on a genuine device. */
    HARDWARE_ATTESTED,
}

data class Check(val name: String, val passed: Boolean, val detail: String)

data class Summary(
    val rows: Int,
    val firstRowMs: Long?,
    val lastRowMs: Long?,
    val checkpoints: Int,
    val signedCheckpoints: Int,
    val findings: Int,
    val createdAtMs: Long,
    val chainStart: String,
    val securityLevel: String?,
    val rulePackVersion: String?,
    val rulesAwaitingReview: List<String>,
)

class Report(
    val level: Level,
    val checks: List<Check>,
    val notes: List<String>,
    val summary: Summary?,
) {
    val accepted: Boolean get() = level != Level.REJECTED
    val failures: List<Check> get() = checks.filter { !it.passed }
}

class VerifierOptions(
    /** Roots that attestation certificate chains must lead to. */
    val trustedRoots: List<X509Certificate> = emptyList(),
)

/**
 * Checks a PermSense evidence report without trusting PermSense.
 *
 * The file is two lines: the body (one line of JSON) and the seal (one line of JSON).
 *   1. The seal holds the SHA-256 of the body's exact bytes and a signature over it, so changing
 *      any byte of the body, or of the seal, is caught before anything inside is believed.
 *   2. Every ledger row is rehashed from its own content and must link to the row before it.
 *   3. Every signed checkpoint must verify, and must name a row that really is in the chain.
 *   4. If the signing key carries an Android key attestation chain, it is checked up to a trusted
 *      root, and the key it certifies must be the key that signed.
 *
 * What a pass proves: the content has not been altered since PermSense wrote it (and, with a
 * hardware attestation, that a genuine device's secure hardware signed it). It does not prove the
 * operating system's data was true, and it cannot see a device compromised before recording.
 */
object EvidenceVerifier {

    const val FORMAT = "permsense-evidence"
    const val FORMAT_VERSION = 1L
    const val ROW_SCHEMA = 3L
    const val GENESIS = "0000000000000000000000000000000000000000000000000000000000000000"

    private const val SEAL_DOMAIN = "permsense-evidence-seal|v1|"

    private val ROW_KEYS = setOf(
        "timestampMs", "uid", "packageName", "versionCode", "op", "accessKind", "durationMs",
        "attributionTag", "flags", "visibilityStratum", "screenState", "sourceTier",
        "observationCount", "accessResult", "sourceEventIds", "recordKind", "userId",
        "schemaVersion", "prevHash", "hash",
    )

    private val HEX64 = Regex("^[0-9a-f]{64}$")

    internal class Reject(val check: String, message: String) : Exception(message)

    fun verify(file: ByteArray, options: VerifierOptions = VerifierOptions()): Report {

        val checks = ArrayList<Check>()
        val notes = ArrayList<String>()

        fun pass(name: String, detail: String) { checks += Check(name, true, detail) }

        try {
            // ------------------------------------------------------------ 1. the seal
            val (bodyBytes, sealText) = split(file)

            val seal = parseObject(sealText, "seal")
            requireKeys(seal, setOf("algorithm", "digest", "publicKey", "sealVersion", "signature"), "seal")

            if (seal["algorithm"] != "SHA-256") throw Reject("document seal", "unsupported digest algorithm")
            if (seal["sealVersion"] != 1L) throw Reject("document seal", "unsupported seal version")

            val digest = hex(MessageDigest.getInstance("SHA-256").digest(bodyBytes))
            if (seal["digest"] != digest) {
                throw Reject("document seal", "the body does not match its digest: the file was changed after it was sealed")
            }

            val sealKey = publicKey(str(seal, "publicKey", "seal"), "seal")
            val sealPayload = (SEAL_DOMAIN + digest).toByteArray(Charsets.UTF_8)
            if (!verifySignature(sealKey, sealPayload, b64(str(seal, "signature", "seal"), "seal signature"))) {
                throw Reject("document seal", "the seal signature is not valid")
            }
            pass("document seal", "the body is exactly what was signed (SHA-256 $digest)")

            // ------------------------------------------------------------ 2. the body
            val bodyText = decodeUtf8(bodyBytes)
            val body = parseObject(bodyText, "body")

            if (body["format"] != FORMAT) throw Reject("format", "not a PermSense evidence file")
            if (body["formatVersion"] != FORMAT_VERSION) throw Reject("format", "unsupported format version ${body["formatVersion"]}")
            val createdAt = long(body, "createdAtMs", "body")
            pass("format", "PermSense evidence, version $FORMAT_VERSION")

            val ledger = obj(body, "ledger")
            val rows = list(ledger, "rows").mapIndexed { i, r -> row(r, i) }

            if (rows.size.toLong() != long(ledger, "rowCount", "ledger")) {
                throw Reject("ledger rows", "the stated row count does not match the rows present")
            }

            // ------------------------------------------------------------ 3. the hash chain
            val firstPrev = str(ledger, "firstPrevHash", "ledger")
            val startsAt = str(ledger, "startsAt", "ledger")
            if (startsAt !in setOf("GENESIS", "SIGNED_ANCHOR", "UNSIGNED_ANCHOR")) {
                throw Reject("hash chain", "unknown chain start \"$startsAt\"")
            }
            if (!HEX64.matches(firstPrev)) throw Reject("hash chain", "the first link is not a hash")

            var expectedPrev = firstPrev
            rows.forEachIndexed { i, r ->
                if (r.prevHash != expectedPrev) throw Reject("hash chain", "row $i does not link to the row before it")
                if (r.computeHash() != r.hash) throw Reject("hash chain", "row $i does not match its hash: it was altered")
                expectedPrev = r.hash
            }
            if (startsAt == "GENESIS" && firstPrev != GENESIS) {
                throw Reject("hash chain", "a chain that claims to start at genesis does not")
            }
            pass("hash chain", "${rows.size} rows rehashed and linked")

            // ------------------------------------------------------------ 4. checkpoints
            val checkpoints = list(body, "checkpoints").mapIndexed { i, c -> checkpoint(c, i) }

            val signed = checkpoints.filter { it.signature.isNotEmpty() }
            val keys = signed.map { it.publicKey.toList() }.distinct()
            if (keys.size > 1) throw Reject("checkpoints", "checkpoints were signed with more than one key")

            for ((i, cp) in checkpoints.withIndex()) {
                if (cp.signature.isNotEmpty()) {
                    val key = publicKey(Base64.getEncoder().encodeToString(cp.publicKey), "checkpoint $i")
                    if (!verifySignature(key, cp.payload(), cp.signature)) {
                        throw Reject("checkpoints", "checkpoint $i has an invalid signature")
                    }
                }
            }

            val anchor = checkpoints.firstOrNull { it.hash == firstPrev }
            var offset = 0L
            when (startsAt) {
                "GENESIS" -> Unit
                "SIGNED_ANCHOR" -> {
                    if (anchor == null || anchor.signature.isEmpty()) {
                        throw Reject("checkpoints", "the chain starts at an anchor with no signed checkpoint")
                    }
                    offset = anchor.rowCount
                }
                "UNSIGNED_ANCHOR" -> {
                    if (anchor == null) throw Reject("checkpoints", "the chain starts at an anchor with no checkpoint")
                    offset = anchor.rowCount
                    notes += "Earlier history was summarised to save space; the start of this chain is not signed."
                }
            }

            var positioned = 0
            for ((i, cp) in checkpoints.withIndex()) {
                if (cp === anchor) continue
                val index = cp.rowCount - 1 - offset
                when {
                    index < 0 -> notes += "Checkpoint $i covers history that has since been summarised and cannot be checked here."
                    index >= rows.size -> throw Reject("checkpoints", "checkpoint $i claims rows that are not in the file")
                    rows[index.toInt()].hash != cp.hash -> throw Reject("checkpoints", "checkpoint $i does not match the row it names")
                    else -> positioned++
                }
            }

            if (signed.isNotEmpty()) {
                if (!signed[0].publicKey.contentEquals(sealKey.encoded)) {
                    throw Reject("checkpoints", "the seal was made with a different key than the checkpoints")
                }
                pass("checkpoints", "${signed.size} signed checkpoint(s) valid, $positioned matched to the chain")
            } else {
                notes += "No checkpoint in this file is signed, so only the seal vouches for it."
                pass("checkpoints", "no signed checkpoints")
            }

            // ------------------------------------------------------------ 5. findings
            val hashes = rows.map { it.hash }.toSet()
            val findings = list(body, "findings")
            findings.forEachIndexed { i, f ->
                val fo = f as? Map<*, *> ?: throw Reject("findings", "finding $i is not an object")
                val evidence = fo["evidenceHashes"] as? List<*> ?: throw Reject("findings", "finding $i has no evidence list")
                for (h in evidence) {
                    if (h !is String || h !in hashes) throw Reject("findings", "finding $i cites a row that is not in the file")
                }
            }
            pass("findings", "${findings.size} finding(s) cite only rows that are present")

            // ------------------------------------------------------------ 6. hardware attestation
            val attestation = obj(body, "attestation")
            val certs = (attestation["certificates"] as? List<*>).orEmpty()
            var securityLevel: String? = null
            var level = Level.INTEGRITY_VERIFIED

            if (certs.isEmpty()) {
                notes += "The signing key has no hardware attestation, so this does not show the file came from a genuine device."
            } else {
                val result = attest(certs, attestation, sealKey, createdAt, options, checks)
                securityLevel = result.first
                if (result.second) level = Level.HARDWARE_ATTESTED
                else notes += result.third
            }

            val rulePack = body["rulePack"] as? Map<*, *>
            val review = (rulePack?.get("rulesAwaitingReview") as? List<*>).orEmpty().filterIsInstance<String>()
            if (review.isNotEmpty()) {
                notes += "These rules cite regulation clauses that no reviewer has confirmed yet: ${review.joinToString()}."
            }

            return Report(
                level = level,
                checks = checks,
                notes = notes,
                summary = Summary(
                    rows = rows.size,
                    firstRowMs = rows.minOfOrNull { it.timestampMs },
                    lastRowMs = rows.maxOfOrNull { it.timestampMs },
                    checkpoints = checkpoints.size,
                    signedCheckpoints = signed.size,
                    findings = findings.size,
                    createdAtMs = createdAt,
                    chainStart = startsAt,
                    securityLevel = securityLevel,
                    rulePackVersion = rulePack?.get("version") as? String,
                    rulesAwaitingReview = review,
                ),
            )

        } catch (e: Reject) {
            checks += Check(e.check, false, e.message ?: "failed")
            return Report(Level.REJECTED, checks, notes, null)
        } catch (e: JsonProblem) {
            checks += Check("parse", false, "the file is not valid: ${e.message}")
            return Report(Level.REJECTED, checks, notes, null)
        } catch (e: GeneralSecurityException) {
            checks += Check("cryptography", false, "a key or signature could not be read: ${e.message}")
            return Report(Level.REJECTED, checks, notes, null)
        } catch (e: RuntimeException) {
            checks += Check("parse", false, "the file is malformed: ${e.javaClass.simpleName}")
            return Report(Level.REJECTED, checks, notes, null)
        }
    }

    // ================================================================== attestation

    /** Returns (security level name, hardware-attested?, reason when not). */
    private fun attest(
        certs: List<*>,
        attestation: Map<String, Any?>,
        signingKey: PublicKey,
        createdAt: Long,
        options: VerifierOptions,
        checks: MutableList<Check>,
    ): Triple<String?, Boolean, String> {

        val challenge = attestation["challenge"] as? String
            ?: throw Reject("attestation", "the report does not state the attestation challenge")

        val result = checkChain(certs, signingKey, createdAt, options, b64(challenge, "challenge"))

        checks += Check(
            "attestation",
            true,
            "key is in ${result.securityLevelName}; certificate chain of ${result.chainSize} " +
                    if (result.anchored) "leads to a trusted root" else "was NOT matched to a trusted root",
        )

        return when {
            !result.anchored -> Triple(result.securityLevelName, false,
                "The attestation chain was not matched to a trusted root, so it proves nothing about the device.")
            !result.hardware -> Triple(result.securityLevelName, false,
                "The signing key is held in software, not secure hardware.")
            else -> Triple(result.securityLevelName, true, "")
        }
    }

    /** What an Android key attestation chain shows about a signing key. */
    internal class ChainResult(
        val securityLevelName: String,
        val hardware: Boolean,
        val anchored: Boolean,
        val chainSize: Int,
    )

    /**
     * Checks an Android key attestation chain: it is well formed and in date, each certificate is
     * signed by the next, the first certificate certifies [signingKey], and (when
     * [expectedChallenge] is given) it was issued for that challenge. Whether it leads to a trusted
     * root, and whether the key is in secure hardware, are reported rather than assumed.
     *
     * @throws Reject when the chain is inconsistent
     */
    internal fun checkChain(
        certs: List<*>,
        signingKey: PublicKey,
        at: Long,
        options: VerifierOptions,
        expectedChallenge: ByteArray?,
    ): ChainResult {

        val factory = CertificateFactory.getInstance("X.509")
        val chain = certs.mapIndexed { i, c ->
            val der = b64(c as? String ?: throw Reject("attestation", "certificate $i is not text"), "certificate $i")
            factory.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }

        val leaf = chain.first()

        if (!leaf.publicKey.encoded.contentEquals(signingKey.encoded)) {
            throw Reject("attestation", "the attested key is not the key that signed this file")
        }

        val when_ = Date(at)
        for ((i, cert) in chain.withIndex()) {
            try {
                cert.checkValidity(when_)
            } catch (e: GeneralSecurityException) {
                throw Reject("attestation", "certificate $i was not valid when the report was made")
            }
        }
        for (i in 0 until chain.size - 1) {
            try {
                chain[i].verify(chain[i + 1].publicKey)
            } catch (e: GeneralSecurityException) {
                throw Reject("attestation", "certificate $i is not signed by certificate ${i + 1}")
            }
        }

        val extension = leaf.getExtensionValue(KeyDescription.OID)
            ?: throw Reject("attestation", "the key certificate carries no attestation record")
        val description = try {
            KeyDescription.parse(extension)
        } catch (e: DerProblem) {
            throw Reject("attestation", "the attestation record is malformed: ${e.message}")
        }

        if (expectedChallenge != null && !description.challenge.contentEquals(expectedChallenge)) {
            throw Reject("attestation", "the attestation challenge does not match: the certificate belongs to another key request")
        }

        val anchored = options.trustedRoots.any { root ->
            val sameCert = root.encoded.contentEquals(chain.last().encoded)
            val signedByRoot = try {
                chain.last().verify(root.publicKey)
                true
            } catch (_: GeneralSecurityException) {
                false
            }
            sameCert || signedByRoot
        }

        return ChainResult(
            securityLevelName = description.securityLevelName,
            hardware = description.attestationSecurityLevel >= 1 && description.keymasterSecurityLevel >= 1,
            anchored = anchored,
            chainSize = chain.size,
        )
    }

    // ================================================================== rows and checkpoints

    private class Row(
        val values: Map<String, Any?>,
    ) {
        val timestampMs: Long get() = values["timestampMs"] as Long
        val prevHash: String get() = values["prevHash"] as String
        val hash: String get() = values["hash"] as String

        /** Rebuilds the hash from the row's own content, exactly as the app defines it. */
        fun computeHash(): String {

            @Suppress("UNCHECKED_CAST")
            val ids = (values["sourceEventIds"] as List<String>).distinct().sorted().joinToString(",")

            val fields = listOf(
                values["schemaVersion"], values["recordKind"], values["timestampMs"], values["userId"],
                values["uid"], values["packageName"], values["versionCode"], values["op"],
                values["accessKind"], values["durationMs"], values["attributionTag"], values["flags"],
                values["visibilityStratum"], values["screenState"], values["sourceTier"],
                values["observationCount"], values["accessResult"], ids,
            )

            // A present value is its length in characters, a colon, then its text. A missing
            // value is a lone "N", which no present value can look like.
            val canonical = fields.joinToString("|") {
                if (it == null) "N" else it.toString().let { text -> "${text.length}:$text" }
            }

            val input = prevHash.toByteArray(Charsets.UTF_8) + byteArrayOf(0x1F) + canonical.toByteArray(Charsets.UTF_8)
            return hex(MessageDigest.getInstance("SHA-256").digest(input))
        }
    }

    private fun row(raw: Any?, index: Int): Row {

        @Suppress("UNCHECKED_CAST")
        val r = raw as? Map<String, Any?> ?: throw Reject("ledger rows", "row $index is not an object")

        if (r.keys != ROW_KEYS) {
            throw Reject("ledger rows", "row $index has unexpected or missing fields: ${(r.keys - ROW_KEYS) + (ROW_KEYS - r.keys)}")
        }

        for (k in listOf("timestampMs", "uid", "versionCode", "flags", "observationCount", "userId", "schemaVersion")) {
            if (r[k] !is Long) throw Reject("ledger rows", "row $index: \"$k\" must be an integer")
        }
        for (k in listOf("packageName", "op", "accessKind", "visibilityStratum", "screenState", "sourceTier", "recordKind", "prevHash", "hash")) {
            if (r[k] !is String) throw Reject("ledger rows", "row $index: \"$k\" must be text")
        }
        for (k in listOf("durationMs", "attributionTag", "accessResult")) {
            val v = r[k]
            val ok = when (k) {
                "durationMs" -> v == null || v is Long
                else -> v == null || v is String
            }
            if (!ok) throw Reject("ledger rows", "row $index: \"$k\" has the wrong type")
        }
        val ids = r["sourceEventIds"] as? List<*>
        if (ids == null || ids.any { it !is String }) throw Reject("ledger rows", "row $index: \"sourceEventIds\" must be a list of text")
        if (r["schemaVersion"] != ROW_SCHEMA) throw Reject("ledger rows", "row $index uses an unsupported schema")
        if (!HEX64.matches(r["prevHash"] as String) || !HEX64.matches(r["hash"] as String)) {
            throw Reject("ledger rows", "row $index: a hash is not 64 hex digits")
        }

        return Row(r)
    }

    private class Checkpoint(
        val hash: String,
        val timestampMs: Long,
        val rowCount: Long,
        val signature: ByteArray,
        val publicKey: ByteArray,
    ) {
        fun payload(): ByteArray = "$hash|$timestampMs|$rowCount".toByteArray(Charsets.UTF_8)
    }

    private fun checkpoint(raw: Any?, index: Int): Checkpoint {

        @Suppress("UNCHECKED_CAST")
        val c = raw as? Map<String, Any?> ?: throw Reject("checkpoints", "checkpoint $index is not an object")

        val hash = str(c, "hash", "checkpoint $index")
        if (!HEX64.matches(hash)) throw Reject("checkpoints", "checkpoint $index: the hash is not 64 hex digits")

        val signature = b64(str(c, "signature", "checkpoint $index"), "checkpoint $index signature")
        val key = c["publicKey"]
        val publicKey = if (key is String && key.isNotEmpty()) b64(key, "checkpoint $index key") else ByteArray(0)
        if (signature.isNotEmpty() && publicKey.isEmpty()) throw Reject("checkpoints", "checkpoint $index is signed but names no key")

        return Checkpoint(hash, long(c, "timestampMs", "checkpoint $index"), long(c, "rowCount", "checkpoint $index"), signature, publicKey)
    }

    // ================================================================== plumbing

    /** The file is body + "\n" + seal + "\n". The body's exact bytes are what the seal covers. */
    private fun split(file: ByteArray): Pair<ByteArray, String> {

        val first = file.indexOf(0x0A.toByte())
        if (first <= 0) throw Reject("file layout", "expected a body line followed by a seal line")

        var bodyEnd = first
        if (bodyEnd > 0 && file[bodyEnd - 1] == 0x0D.toByte()) bodyEnd-- // tolerate CRLF

        val rest = file.copyOfRange(first + 1, file.size)
        var restEnd = rest.size
        if (restEnd > 0 && rest[restEnd - 1] == 0x0A.toByte()) restEnd--
        else throw Reject("file layout", "the file does not end with a newline")
        if (restEnd > 0 && rest[restEnd - 1] == 0x0D.toByte()) restEnd--

        val sealBytes = rest.copyOfRange(0, restEnd)
        if (sealBytes.contains(0x0A.toByte())) throw Reject("file layout", "there is more than one line after the body")

        return file.copyOfRange(0, bodyEnd) to decodeUtf8(sealBytes)
    }

    private fun decodeUtf8(bytes: ByteArray): String =
        try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw Reject("file layout", "the file is not valid UTF-8")
        }

    private fun parseObject(text: String, what: String): Map<String, Any?> =
        try {
            MiniJson.parseObject(text)
        } catch (e: JsonProblem) {
            throw Reject("parse", "the $what is not valid JSON: ${e.message}")
        }

    private fun requireKeys(map: Map<String, Any?>, keys: Set<String>, what: String) {
        if (map.keys != keys) throw Reject("document seal", "the $what has unexpected or missing fields")
    }

    @Suppress("UNCHECKED_CAST")
    private fun obj(map: Map<String, Any?>, key: String): Map<String, Any?> =
        map[key] as? Map<String, Any?> ?: throw Reject("format", "\"$key\" must be an object")

    private fun list(map: Map<String, Any?>, key: String): List<Any?> =
        map[key] as? List<Any?> ?: throw Reject("format", "\"$key\" must be a list")

    private fun str(map: Map<String, Any?>, key: String, what: String): String =
        map[key] as? String ?: throw Reject("format", "$what: \"$key\" must be text")

    private fun long(map: Map<String, Any?>, key: String, what: String): Long =
        map[key] as? Long ?: throw Reject("format", "$what: \"$key\" must be an integer")

    /**
     * Canonical base64 only. Base64 lets several different texts decode to the same bytes (the
     * unused trailing bits of the last character), so a decoder that accepts all of them would let
     * a changed character through unnoticed. Anything that does not re-encode to exactly the same
     * text is refused.
     */
    internal fun b64(text: String, what: String): ByteArray {
        val bytes = try {
            Base64.getDecoder().decode(text)
        } catch (e: IllegalArgumentException) {
            throw Reject("format", "$what is not valid base64")
        }
        if (Base64.getEncoder().encodeToString(bytes) != text) {
            throw Reject("format", "$what is not canonical base64")
        }
        return bytes
    }

    internal fun publicKey(b64Text: String, what: String): PublicKey =
        try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(b64(b64Text, "$what key")))
        } catch (e: GeneralSecurityException) {
            throw Reject("format", "$what key is not a valid EC public key")
        }

    internal fun verifySignature(key: PublicKey, payload: ByteArray, signature: ByteArray): Boolean =
        try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(payload)
                verify(signature)
            }
        } catch (e: GeneralSecurityException) {
            false
        }

    internal fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(digits[(b.toInt() shr 4) and 0xF])
            sb.append(digits[b.toInt() and 0xF])
        }
        return sb.toString()
    }
}
