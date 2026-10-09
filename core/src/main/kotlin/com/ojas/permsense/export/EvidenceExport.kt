package com.ojas.permsense.export

import com.ojas.permsense.findings.Finding
import com.ojas.permsense.json.Json
import com.ojas.permsense.judgement.PolicyPack
import com.ojas.permsense.ledger.ChainStart
import com.ojas.permsense.ledger.GENESIS_HASH
import com.ojas.permsense.ledger.LedgerRecord
import com.ojas.permsense.ledger.LedgerSigner
import com.ojas.permsense.ledger.SignedCheckpoint
import com.ojas.permsense.ledger.sha256Hex
import java.util.Base64

/** What goes into a report. */
class ExportInput(
    val rows: List<LedgerRecord>,
    val checkpoints: List<SignedCheckpoint>,
    val chainStart: ChainStart,
    val findings: List<Finding>,
    val pack: PolicyPack?,
    /** Signs the seal. On a device this is the Keystore key, so the seal is hardware-backed. */
    val signer: LedgerSigner,
    val nowMs: Long,
    val appVersion: String,
    /** Anything the exporter chooses to state about the device. Kept minimal. */
    val device: Map<String, String> = emptyMap(),
)

class ExportResult(
    /** The machine-verifiable file: body, newline, seal, newline. Bytes, not text. */
    val file: ByteArray,
    /** SHA-256 of the body, as in the seal. Shown in the report so the two can be matched. */
    val digest: String,
)

/**
 * Writes the evidence file.
 *
 * Layout: line 1 is the body (canonical JSON, one line). Line 2 is the seal: the SHA-256 of
 * line 1's exact bytes and an ECDSA signature over it. Because the seal covers raw bytes, a
 * verifier never has to re-serialise JSON to check it (three implementations agreeing on
 * serialisation is a bug waiting to happen), and a change to any single byte of line 1, or to
 * line 2, is detected.
 *
 * Two independent kinds of integrity are in the body: the hash chain of ledger rows (which
 * survives a recipient trimming the file to the rows they care about is NOT supported; the whole
 * chain is exported), and the signed checkpoints inside the ledger itself.
 */
object EvidenceExport {

    const val FORMAT = "permsense-evidence"
    const val FORMAT_VERSION = 1
    const val SEAL_VERSION = 1

    /** Domain separator, so a seal signature can never be mistaken for a checkpoint signature. */
    const val SEAL_DOMAIN = "permsense-evidence-seal|v1|"

    /** The limits of what a report proves, in the words the report itself uses. */
    val STATEMENT_EN =
        "This report proves integrity after recording: that what PermSense wrote to its ledger has " +
                "not been altered since. It does not prove that the phone's operating system reported " +
                "the truth in the first place, and it cannot detect a phone that was already tampered " +
                "with, for example by its owner using root access, before the data was recorded. " +
                "PermSense makes no claim that this report is admissible in any legal proceeding."

    val STATEMENT_HI =
        "यह रिपोर्ट रिकॉर्डिंग के बाद की अखंडता साबित करती है: यानी PermSense ने अपने लेज़र में जो लिखा, वह " +
                "उसके बाद बदला नहीं गया है। यह साबित नहीं करती कि फ़ोन के ऑपरेटिंग सिस्टम ने शुरू में सही जानकारी दी थी, " +
                "और यह ऐसे फ़ोन को नहीं पहचान सकती जिसके साथ डेटा रिकॉर्ड होने से पहले ही छेड़छाड़ हो चुकी हो, " +
                "उदाहरण के लिए मालिक द्वारा रूट एक्सेस के उपयोग से। PermSense यह दावा नहीं करता कि यह रिपोर्ट " +
                "किसी कानूनी कार्यवाही में स्वीकार्य होगी।"

    fun build(input: ExportInput): ExportResult {

        val rowHashes = input.rows.map { it.hash }.toSet()

        val body = linkedMapOf<String, Any?>(
            "format" to FORMAT,
            "formatVersion" to FORMAT_VERSION,
            "createdAtMs" to input.nowMs,
            "generator" to mapOf(
                "app" to "PermSense",
                "appVersion" to input.appVersion,
                "device" to input.device,
            ),
            "statement" to mapOf("en" to STATEMENT_EN, "hi" to STATEMENT_HI),
            "ledger" to mapOf(
                "schemaVersion" to (input.rows.firstOrNull()?.schemaVersion ?: 0),
                "genesisHash" to GENESIS_HASH,
                "startsAt" to input.chainStart.exportName(),
                "firstPrevHash" to (input.rows.firstOrNull()?.prevHash ?: GENESIS_HASH),
                "rowCount" to input.rows.size,
                "rows" to input.rows.map { rowToMap(it) },
            ),
            "checkpoints" to input.checkpoints.map { checkpointToMap(it) },
            "attestation" to mapOf(
                "challenge" to (input.signer.attestationChallenge()?.let { b64(it) } ?: ""),
                "certificates" to input.signer.attestationChainDer().map { b64(it) },
            ),
            "findings" to input.findings.map { findingToMap(it, rowHashes) },
            "rulePack" to mapOf(
                "id" to (input.pack?.packId ?: ""),
                "version" to (input.pack?.packVersion ?: ""),
                "rulesAwaitingReview" to (input.pack?.rulesAwaitingReview() ?: emptyList()),
            ),
        )

        val bodyText = Json.write(body)
        val bodyBytes = bodyText.toByteArray(Charsets.UTF_8)
        val digest = sha256Hex(bodyBytes)

        val signature = input.signer.sign((SEAL_DOMAIN + digest).toByteArray(Charsets.UTF_8))

        val seal = mapOf(
            "algorithm" to "SHA-256",
            "digest" to digest,
            "publicKey" to b64(input.signer.getPublicKeyDer()),
            "sealVersion" to SEAL_VERSION,
            "signature" to b64(signature),
        )

        val file = bodyBytes + byteArrayOf(0x0A) + Json.write(seal).toByteArray(Charsets.UTF_8) + byteArrayOf(0x0A)

        return ExportResult(file, digest)
    }

    private fun ChainStart.exportName(): String =
        when (this) {
            ChainStart.GENESIS -> "GENESIS"
            ChainStart.SIGNED_ANCHOR -> "SIGNED_ANCHOR"
            ChainStart.UNSIGNED_ANCHOR -> "UNSIGNED_ANCHOR"
            // The verifier rejects this; stating it honestly is better than hiding it.
            ChainStart.UNANCHORED -> "UNANCHORED"
        }

    fun rowToMap(r: LedgerRecord): Map<String, Any?> =
        linkedMapOf(
            "timestampMs" to r.timestampMs,
            "uid" to r.uid,
            "packageName" to r.packageName,
            "versionCode" to r.versionCode,
            "op" to r.op,
            "accessKind" to r.accessKind,
            "durationMs" to r.durationMs,
            "attributionTag" to r.attributionTag,
            "flags" to r.flags,
            "visibilityStratum" to r.visibilityStratum,
            "screenState" to r.screenState,
            "sourceTier" to r.sourceTier,
            "observationCount" to r.observationCount,
            "accessResult" to r.accessResult,
            "sourceEventIds" to r.sourceEventIds,
            "recordKind" to r.recordKind,
            "userId" to r.userId,
            "schemaVersion" to r.schemaVersion,
            "prevHash" to r.prevHash,
            "hash" to r.hash,
        )

    private fun checkpointToMap(c: SignedCheckpoint): Map<String, Any?> =
        linkedMapOf(
            "hash" to c.hash,
            "timestampMs" to c.timestampMs,
            "rowCount" to c.rowCount,
            "keyAlias" to c.keyAlias,
            "signature" to b64(c.signatureBytes),
            "publicKey" to (c.publicKeyDer?.let { b64(it) } ?: ""),
        )

    private fun findingToMap(f: Finding, present: Set<String>): Map<String, Any?> {

        val common = linkedMapOf<String, Any?>(
            "dedupKey" to f.dedupKey,
            "packageName" to f.packageName,
            "severity" to f.severity,
            "evidenceHashes" to f.evidence.map { it.hash }.filter { it in present },
        )

        when (f) {
            is Finding.PolicyViolation -> {
                common["kind"] = "policy"
                common["ruleId"] = f.ruleId
                common["title"] = f.title
                common["explanation"] = f.explanation
                common["citation"] = f.citation
                common["basis"] = f.basis.name
                common["params"] = f.params
            }
            is Finding.BaselineDeviation -> {
                common["kind"] = "baseline"
                common["op"] = f.op
                common["stratum"] = f.stratum
                common["observed"] = f.observed
                common["pValue"] = f.pValue.toString()
            }
            is Finding.UpdateDrift -> {
                common["kind"] = "drift"
                common["op"] = f.op
                common["stratum"] = f.stratum
                common["fromVersion"] = f.fromVersion
                common["toVersion"] = f.toVersion
                common["probability"] = f.probability.toString()
            }
            is Finding.SustainedShift -> {
                common["kind"] = "shift"
                common["op"] = f.op
                common["stratum"] = f.stratum
                common["daysRaised"] = f.daysRaised
            }
        }
        return common
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
