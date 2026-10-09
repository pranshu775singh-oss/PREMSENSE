package permsense.verifier

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.PublicKey

/** One access the probe app meant to make, as it wrote it down at the moment it made it. */
data class IntendedAccess(
    val seq: Long,
    val tsMs: Long,
    val versionCode: Long,
    val op: String,
    val trigger: String,
    val step: String,
    val interactive: Boolean,
    val keyguard: Boolean,
    val importance: Long,
    /** What PermSense should call the moment: VISIBLE, FOREGROUND_SERVICE, BACKGROUND or SCREEN_OFF. */
    val expectedStratum: String,
    /** OK, DENIED (permission not granted) or UNAVAILABLE (the phone could not do it). */
    val outcome: String,
    val detail: String,
)

class IntendedLogReport(
    val level: Level,
    val checks: List<Check>,
    val notes: List<String>,
    val accesses: List<IntendedAccess>,
    val createdAtMs: Long?,
    val securityLevel: String?,
) {
    val accepted: Boolean get() = level != Level.REJECTED
}

/**
 * Checks the probe app's signed log of intended accesses, without trusting the probe app.
 *
 * The log is one JSON object per line. The first names the signing key (and, where the phone can,
 * the attestation chain for it). Every later line carries the hash of the line before it and a
 * signature, so any change to any line, any line removed, added, repeated or reordered, is caught.
 *
 * What a pass proves: this is the log the probe wrote, unaltered. It does not prove the accesses
 * really happened; that is what comparing it with PermSense's own ledger is for.
 */
object IntendedLogVerifier {

    private val HEADER_KEYS = setOf("type", "format", "createdMs", "versionCode", "publicKey", "attestation", "sig")
    private val ACCESS_KEYS = setOf(
        "type", "seq", "tsMs", "versionCode", "op", "trigger", "step", "interactive", "keyguard",
        "importance", "expectedStratum", "outcome", "detail", "prev", "sig",
    )
    private val STRATA = setOf("VISIBLE", "FOREGROUND_SERVICE", "BACKGROUND", "SCREEN_OFF")
    private val OUTCOMES = setOf("OK", "DENIED", "UNAVAILABLE")
    private val HEX64 = Regex("^[0-9a-f]{64}$")

    fun verify(file: ByteArray, options: VerifierOptions = VerifierOptions()): IntendedLogReport {

        val checks = ArrayList<Check>()
        val notes = ArrayList<String>()

        try {
            // ---------------------------------------------------------------- layout
            val text = decodeUtf8(file)
            if (!text.endsWith("\n")) throw EvidenceVerifier.Reject("file layout", "the file does not end with a newline")
            if (text.contains('\r')) throw EvidenceVerifier.Reject("file layout", "unexpected carriage return")

            val lines = text.removeSuffix("\n").split("\n")
            if (lines.any { it.isEmpty() }) throw EvidenceVerifier.Reject("file layout", "there is an empty line")
            checks += Check("file layout", true, "${lines.size - 1} access line(s) after the header")

            // ---------------------------------------------------------------- header
            val header = parse(lines[0], "header")
            if (header.keys != HEADER_KEYS) throw EvidenceVerifier.Reject("header", "the header has unexpected or missing fields")
            if (header["type"] != "header") throw EvidenceVerifier.Reject("header", "the first line is not a header")
            if (header["format"] != 1L) throw EvidenceVerifier.Reject("header", "unsupported format")

            val createdMs = long(header, "createdMs", "header")
            val versionCode = long(header, "versionCode", "header")
            val publicKeyText = str(header, "publicKey", "header")
            val certs = header["attestation"] as? List<*> ?: throw EvidenceVerifier.Reject("header", "\"attestation\" must be a list")

            val key = EvidenceVerifier.publicKey(publicKeyText, "log")

            val headerCanonical = canonicalHeader(createdMs, versionCode, publicKeyText, certs.size)
            requireSignature(key, headerCanonical, str(header, "sig", "header"), "header")
            checks += Check("header", true, "the log names its signing key and the header is signed by it")

            // ---------------------------------------------------------------- the chain of lines
            var previousCanonical = headerCanonical
            val accesses = ArrayList<IntendedAccess>()

            for ((index, line) in lines.drop(1).withIndex()) {

                val where = "line ${index + 2}"
                val o = parse(line, where)

                if (o.keys != ACCESS_KEYS) throw EvidenceVerifier.Reject("chain", "$where has unexpected or missing fields")
                if (o["type"] != "access") throw EvidenceVerifier.Reject("chain", "$where is not an access")

                val access = IntendedAccess(
                    seq = long(o, "seq", where),
                    tsMs = long(o, "tsMs", where),
                    versionCode = long(o, "versionCode", where),
                    op = text(o, "op", where),
                    trigger = text(o, "trigger", where),
                    step = text(o, "step", where),
                    interactive = bool(o, "interactive", where),
                    keyguard = bool(o, "keyguard", where),
                    importance = long(o, "importance", where),
                    expectedStratum = text(o, "expectedStratum", where),
                    outcome = text(o, "outcome", where),
                    detail = text(o, "detail", where),
                )
                val prev = str(o, "prev", where)

                if (access.seq != (index + 1).toLong()) throw EvidenceVerifier.Reject("chain", "$where is out of order (expected number ${index + 1})")
                if (access.expectedStratum !in STRATA) throw EvidenceVerifier.Reject("chain", "$where names an unknown stratum")
                if (access.outcome !in OUTCOMES) throw EvidenceVerifier.Reject("chain", "$where names an unknown outcome")
                if (!HEX64.matches(prev)) throw EvidenceVerifier.Reject("chain", "$where: the link is not a hash")
                if (prev != sha256Hex(previousCanonical)) throw EvidenceVerifier.Reject("chain", "$where does not link to the line before it")

                val canonical = canonicalAccess(access, prev)
                requireSignature(key, canonical, str(o, "sig", where), where)

                accesses += access
                previousCanonical = canonical
            }
            checks += Check("chain", true, "${accesses.size} line(s) linked and signed, none missing, added or reordered")

            // ---------------------------------------------------------------- attestation
            var level = Level.INTEGRITY_VERIFIED
            var securityLevel: String? = null

            if (certs.size <= 1) {
                notes += "The signing key has no attestation chain, so this does not show the log came from a genuine device."
            } else {
                val result = EvidenceVerifier.checkChain(certs, key, createdMs, options, expectedChallenge = null)
                securityLevel = result.securityLevelName
                checks += Check(
                    "attestation", true,
                    "key is in ${result.securityLevelName}; certificate chain of ${result.chainSize} " +
                            if (result.anchored) "leads to a trusted root" else "was NOT matched to a trusted root",
                )
                when {
                    !result.anchored -> notes += "The attestation chain was not matched to a trusted root, so it proves nothing about the device."
                    !result.hardware -> notes += "The signing key is held in software, not secure hardware."
                    else -> level = Level.HARDWARE_ATTESTED
                }
            }

            if (accesses.zipWithNext().any { (a, b) -> b.tsMs < a.tsMs }) {
                notes += "Some entries are timestamped earlier than the entry before them (the phone's clock was set back during the run)."
            }

            return IntendedLogReport(level, checks, notes, accesses, createdMs, securityLevel)

        } catch (e: EvidenceVerifier.Reject) {
            checks += Check(e.check, false, e.message ?: "failed")
            return IntendedLogReport(Level.REJECTED, checks, notes, emptyList(), null, null)
        } catch (e: JsonProblem) {
            checks += Check("parse", false, "the log is not valid: ${e.message}")
            return IntendedLogReport(Level.REJECTED, checks, notes, emptyList(), null, null)
        } catch (e: GeneralSecurityException) {
            checks += Check("cryptography", false, "a key or signature could not be read: ${e.message}")
            return IntendedLogReport(Level.REJECTED, checks, notes, emptyList(), null, null)
        } catch (e: RuntimeException) {
            checks += Check("parse", false, "the log is malformed: ${e.javaClass.simpleName}")
            return IntendedLogReport(Level.REJECTED, checks, notes, emptyList(), null, null)
        }
    }

    // ================================================================== canonical forms (as the probe writes them)

    private fun canonicalHeader(createdMs: Long, versionCode: Long, publicKey: String, chainLength: Int): String =
        listOf("h1", createdMs, versionCode, publicKey, chainLength).joinToString("|")

    private fun canonicalAccess(a: IntendedAccess, prev: String): String =
        listOf(
            "a1", a.seq, a.tsMs, a.versionCode, a.op, a.trigger, a.step, a.interactive, a.keyguard,
            a.importance, a.expectedStratum, a.outcome, a.detail, prev,
        ).joinToString("|")

    // ================================================================== plumbing

    private fun requireSignature(key: PublicKey, canonical: String, signature: String, where: String) {
        val bytes = EvidenceVerifier.b64(signature, "$where signature")
        if (!EvidenceVerifier.verifySignature(key, canonical.toByteArray(Charsets.UTF_8), bytes)) {
            throw EvidenceVerifier.Reject("signatures", "$where has an invalid signature: it was altered")
        }
    }

    private fun sha256Hex(text: String): String =
        EvidenceVerifier.hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))

    private fun parse(line: String, what: String): Map<String, Any?> =
        try {
            MiniJson.parseObject(line)
        } catch (e: JsonProblem) {
            throw EvidenceVerifier.Reject("parse", "$what is not valid JSON: ${e.message}")
        }

    private fun decodeUtf8(bytes: ByteArray): String =
        try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw EvidenceVerifier.Reject("file layout", "the file is not valid UTF-8")
        }

    private fun str(map: Map<String, Any?>, key: String, what: String): String =
        map[key] as? String ?: throw EvidenceVerifier.Reject("format", "$what: \"$key\" must be text")

    /** Text that goes into a `|`-joined canonical form, so it may not contain the separator. */
    private fun text(map: Map<String, Any?>, key: String, what: String): String {
        val value = str(map, key, what)
        if (value.contains('|') || value.contains('\n')) {
            throw EvidenceVerifier.Reject("format", "$what: \"$key\" contains a character that is not allowed")
        }
        return value
    }

    private fun long(map: Map<String, Any?>, key: String, what: String): Long =
        map[key] as? Long ?: throw EvidenceVerifier.Reject("format", "$what: \"$key\" must be an integer")

    private fun bool(map: Map<String, Any?>, key: String, what: String): Boolean =
        map[key] as? Boolean ?: throw EvidenceVerifier.Reject("format", "$what: \"$key\" must be true or false")
}
