package com.ojas.permsense.export

import com.ojas.permsense.export.ExportFixtures.BASE
import com.ojas.permsense.export.ExportFixtures.MINUTE
import com.ojas.permsense.json.Json
import com.ojas.permsense.ledger.LedgerFx
import com.ojas.permsense.ledger.LedgerSigner
import com.ojas.permsense.ledger.LedgerWriter
import com.ojas.permsense.ledger.SoftwareEcSigner
import com.ojas.permsense.ledger.sha256Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import permsense.verifier.EvidenceVerifier
import permsense.verifier.Level
import permsense.verifier.Report
import java.util.Base64
import kotlin.random.Random

/**
 * The exporter and the independent verifier, tested against each other. The verifier shares no
 * code with the exporter, so agreement here means the written format is what it is documented to
 * be, and the adversarial tests show that tampering cannot slip through.
 */
class ExportVerificationTest {

    private fun verify(bytes: ByteArray): Report = EvidenceVerifier.verify(bytes)

    private fun flip(bytes: ByteArray, index: Int, mask: Int = 0x01): ByteArray =
        bytes.copyOf().also { it[index] = (it[index].toInt() xor mask).toByte() }

    // ------------------------------------------------------------------ the honest path

    @Test
    fun anUntouchedExportIsAccepted() {
        val (export, input) = ExportFixtures.export(rows = 12)

        val report = verify(export.file)

        assertTrue(report.failures.toString(), report.accepted)
        assertEquals(Level.INTEGRITY_VERIFIED, report.level)
        val s = report.summary!!
        assertEquals(12, s.rows)
        assertEquals(3, s.signedCheckpoints)
        assertEquals(input.findings.size, s.findings)
        assertEquals("GENESIS", s.chainStart)
        assertEquals(BASE, s.firstRowMs)
    }

    @Test
    fun theFileIsABodyLineAndASealLine() {
        val (export, _) = ExportFixtures.export(rows = 6)
        val text = export.file.toString(Charsets.UTF_8)

        val lines = text.split('\n')

        assertEquals(3, lines.size)        // body, seal, and the empty string after the final newline
        assertEquals("", lines[2])
        assertEquals(export.digest, sha256Hex(lines[0].toByteArray(Charsets.UTF_8)))
        assertTrue(lines[1].contains("\"algorithm\":\"SHA-256\""))
    }

    @Test
    fun theSameLedgerAlwaysExportsToTheSameBody() {
        val signer = SoftwareEcSigner("same")
        val writer = ExportFixtures.ledger(8, signer)

        val a = EvidenceExport.build(ExportFixtures.input(writer, signer))
        val b = EvidenceExport.build(ExportFixtures.input(writer, signer))

        assertEquals(a.digest, b.digest)
    }

    @Test
    fun anEmptyLedgerExportsAndVerifies() {
        val signer = SoftwareEcSigner("empty")
        val writer = LedgerWriter()
        val report = verify(EvidenceExport.build(ExportFixtures.input(writer, signer, findings = emptyList())).file)

        assertTrue(report.failures.toString(), report.accepted)
        assertEquals(0, report.summary!!.rows)
    }

    @Test
    fun aPrunedLedgerWithASignedAnchorStillVerifies() {
        val signer = SoftwareEcSigner("pruned")
        val writer = ExportFixtures.ledger(20, signer)
        writer.pruneBefore(BASE + 90 * MINUTE, signer, nowMs = BASE + 600 * MINUTE)

        val report = verify(EvidenceExport.build(ExportFixtures.input(writer, signer, findings = emptyList())).file)

        assertTrue(report.failures.toString(), report.accepted)
        assertEquals("SIGNED_ANCHOR", report.summary!!.chainStart)
        assertTrue(report.notes.any { it.contains("summarised") })
    }

    @Test
    fun anUnsignedAnchorIsAcceptedButSaidOutLoud() {
        val signer = SoftwareEcSigner("unsigned")
        val writer = ExportFixtures.ledger(20, signer)
        writer.pruneBefore(BASE + 90 * MINUTE, signer = null, nowMs = BASE + 600 * MINUTE)

        val report = verify(EvidenceExport.build(ExportFixtures.input(writer, signer, findings = emptyList())).file)

        assertTrue(report.accepted)
        assertTrue(report.notes.any { it.contains("not signed") })
    }

    @Test
    fun theRuleReviewCaveatTravelsWithTheReport() {
        val (export, _) = ExportFixtures.export(rows = 4)

        val report = verify(export.file)

        assertTrue(report.notes.any { it.contains("RBI-01") && it.contains("reviewer") })
        assertEquals(5, report.summary!!.rulesAwaitingReview.size)
    }

    // ------------------------------------------------------------------ single byte changes

    @Test
    fun everySingleBitFlipInTheWholeFileIsRejected() {
        val (export, _) = ExportFixtures.export(rows = 6, checkpointEvery = 3)
        val bytes = export.file
        assertTrue(verify(bytes).accepted)

        val accepted = ArrayList<Int>()
        for (i in bytes.indices) {
            if (verify(flip(bytes, i, 0x01)).accepted) accepted += i
        }

        assertEquals("flipping these positions was not caught: ${accepted.take(20)} of ${bytes.size}", emptyList<Int>(), accepted)
    }

    @Test
    fun everyOtherKindOfSingleByteDamageIsRejectedToo() {
        val (export, _) = ExportFixtures.export(rows = 5, checkpointEvery = 5)
        val bytes = export.file

        for (mask in listOf(0x80, 0xFF, 0x20)) {
            val accepted = bytes.indices.filter { verify(flip(bytes, it, mask)).accepted }
            assertEquals("mask $mask not caught at ${accepted.take(10)}", emptyList<Int>(), accepted)
        }
    }

    @Test
    fun oneHundredRandomMutationsOfARealisticExportAreAllRejected() {
        // The acceptance test for the verifier: 100 exports, each with exactly one byte altered
        // somewhere in the data, and every one must be refused.
        val (export, _) = ExportFixtures.export(rows = 300, checkpointEvery = 25)
        val bytes = export.file
        assertTrue(verify(bytes).accepted)

        val rng = Random(2026)
        val positions = generateSequence { rng.nextInt(bytes.size) }.distinct().take(100).toList()

        for (position in positions) {
            val mutated = flip(bytes, position, 1 shl rng.nextInt(8))
            val report = verify(mutated)
            assertFalse("mutation at byte $position of ${bytes.size} was accepted", report.accepted)
        }
    }

    @Test
    fun changingARowsContentOrItsHashIsRejected() {
        val (export, _) = ExportFixtures.export(rows = 8)
        val text = export.file.toString(Charsets.UTF_8)

        // Replace the first op value, as an attacker editing a ledger row by hand would.
        val edited = text.replaceFirst("android:read_contacts", "android:read_clipboard")
        assertNotEquals(text, edited)

        assertFalse(verify(edited.toByteArray(Charsets.UTF_8)).accepted)
    }

    // ------------------------------------------------------------------ structural damage

    @Test
    fun aTruncatedFileIsRejectedAtEveryLength() {
        val (export, _) = ExportFixtures.export(rows = 5)
        val bytes = export.file

        for (length in 0 until bytes.size) {
            assertFalse("a $length byte prefix was accepted", verify(bytes.copyOf(length)).accepted)
        }
    }

    @Test
    fun extraBytesAreRejected() {
        val (export, _) = ExportFixtures.export(rows = 5)
        val bytes = export.file

        assertFalse(verify(bytes + byteArrayOf(0x0A)).accepted)
        assertFalse(verify(bytes + "{}".toByteArray()).accepted)
        assertFalse(verify(byteArrayOf(0x20) + bytes).accepted)
    }

    @Test
    fun theLinesCannotBeSwappedOrRepeated() {
        val (export, _) = ExportFixtures.export(rows = 5)
        val (body, seal) = export.file.toString(Charsets.UTF_8).split('\n')

        assertFalse(verify("$seal\n$body\n".toByteArray()).accepted)
        assertFalse(verify("$body\n$seal\n$seal\n".toByteArray()).accepted)
        assertFalse(verify("$body\n$body\n".toByteArray()).accepted)
    }

    @Test
    fun windowsLineEndingsFromATransferToolAreTolerated() {
        val (export, _) = ExportFixtures.export(rows = 5)
        val converted = export.file.toString(Charsets.UTF_8).replace("\n", "\r\n")

        assertTrue(verify(converted.toByteArray()).accepted)
    }

    @Test
    fun garbageIsRejectedWithoutAnyCrash() {
        val rng = Random(1)
        repeat(200) {
            val junk = rng.nextBytes(rng.nextInt(0, 400))
            assertFalse(verify(junk).accepted)
        }
        assertFalse(verify("not json\nnot json\n".toByteArray()).accepted)
        assertFalse(verify("{}\n{}\n".toByteArray()).accepted)
    }

    // ------------------------------------------------------------------ deliberate forgery

    /** Rebuilds the file from a changed body, sealing it with [signer]: an attacker who can sign. */
    private fun resealed(export: ExportResult, signer: LedgerSigner, edit: (MutableMap<String, Any?>) -> Unit): ByteArray {
        val body = Json.parseObject(export.file.toString(Charsets.UTF_8).substringBefore('\n')).toMutableMap()
        edit(body)
        val bodyBytes = Json.write(body).toByteArray(Charsets.UTF_8)
        val digest = sha256Hex(bodyBytes)
        val seal = mapOf(
            "algorithm" to "SHA-256",
            "digest" to digest,
            "publicKey" to Base64.getEncoder().encodeToString(signer.getPublicKeyDer()),
            "sealVersion" to 1,
            "signature" to Base64.getEncoder().encodeToString(signer.sign((EvidenceExport.SEAL_DOMAIN + digest).toByteArray())),
        )
        return bodyBytes + byteArrayOf(0x0A) + Json.write(seal).toByteArray() + byteArrayOf(0x0A)
    }

    @Test
    fun anAttackerWhoEditsARowAndReSealsWithTheirOwnKeyIsCaught() {
        val signer = SoftwareEcSigner("victim")
        val (export, _) = ExportFixtures.export(rows = 10, signer = signer)

        val forged = resealed(export, SoftwareEcSigner("attacker")) { body ->
            @Suppress("UNCHECKED_CAST")
            val ledger = (body["ledger"] as Map<String, Any?>).toMutableMap()
            @Suppress("UNCHECKED_CAST")
            val rows = (ledger["rows"] as List<Map<String, Any?>>).toMutableList()
            rows[3] = rows[3].toMutableMap().also { it["packageName"] = "com.example.innocent" }
            ledger["rows"] = rows
            body["ledger"] = ledger
        }

        val report = verify(forged)

        // The edited row no longer matches its hash (and the seal key is not the checkpoints' key).
        assertFalse(report.accepted)
    }

    @Test
    fun anAttackerWhoRewritesTheWholeChainStillCannotMatchTheOriginalCheckpoints() {
        val signer = SoftwareEcSigner("victim")
        val (export, _) = ExportFixtures.export(rows = 10, signer = signer)

        // Replace every row's hash chain consistently, but keep the original signed checkpoints.
        val forged = resealed(export, signer) { body ->
            @Suppress("UNCHECKED_CAST")
            val ledger = (body["ledger"] as Map<String, Any?>).toMutableMap()
            @Suppress("UNCHECKED_CAST")
            val rows = (ledger["rows"] as List<Map<String, Any?>>).toMutableList()
            val rewritten = LedgerWriter()
            for ((i, r) in rows.withIndex()) {
                rewritten.append(
                    listOf(LedgerFx.episode(listOf("forged-$i"), BASE + i * 10 * MINUTE, pkg = "com.example.forged")),
                    nowMs = BASE + i * 10 * MINUTE,
                )
            }
            rewritten.flush(BASE + 1_000 * MINUTE)
            ledger["rows"] = rewritten.all().map { EvidenceExport.rowToMap(it) }
            ledger["firstPrevHash"] = rewritten.all().first().prevHash
            body["ledger"] = ledger
        }

        assertFalse(verify(forged).accepted)
    }

    @Test
    fun aWholeFileBuiltFromScratchWithAFreshKeyIsOnlyEverIntegrityNeverHardware() {
        // Nobody can stop an attacker producing a consistent, signed file with a key of their own.
        // What the verifier must never do is call it hardware-attested.
        val attacker = SoftwareEcSigner("attacker")
        val (forgedExport, _) = ExportFixtures.export(rows = 10, signer = attacker)

        val report = verify(forgedExport.file)

        assertEquals(Level.INTEGRITY_VERIFIED, report.level)
        assertTrue(report.notes.any { it.contains("no hardware attestation") })
    }

    @Test
    fun aFindingThatCitesARowThatIsNotInTheFileIsRejected() {
        val signer = SoftwareEcSigner("victim")
        val (export, _) = ExportFixtures.export(rows = 10, signer = signer)

        val forged = resealed(export, signer) { body ->
            @Suppress("UNCHECKED_CAST")
            val findings = (body["findings"] as List<Map<String, Any?>>).toMutableList()
            findings[0] = findings[0].toMutableMap().also { it["evidenceHashes"] = listOf("f".repeat(64)) }
            body["findings"] = findings
        }

        val report = verify(forged)

        assertFalse(report.accepted)
        assertTrue(report.failures.any { it.name == "findings" })
    }

    @Test
    fun checkpointsSignedByAnotherKeyAreRejected() {
        val signer = SoftwareEcSigner("victim")
        val (export, _) = ExportFixtures.export(rows = 8, signer = signer)
        val other = SoftwareEcSigner("other")

        val forged = resealed(export, signer) { body ->
            @Suppress("UNCHECKED_CAST")
            val checkpoints = (body["checkpoints"] as List<Map<String, Any?>>).toMutableList()
            val c = checkpoints[0]
            val payload = "${c["hash"]}|${c["timestampMs"]}|${c["rowCount"]}".toByteArray()
            checkpoints[0] = c.toMutableMap().also {
                it["signature"] = Base64.getEncoder().encodeToString(other.sign(payload))
                it["publicKey"] = Base64.getEncoder().encodeToString(other.getPublicKeyDer())
            }
            body["checkpoints"] = checkpoints
        }

        assertFalse(verify(forged).accepted)
    }

    @Test
    fun aCheckpointForARowThatDoesNotExistYetIsRejected() {
        val signer = SoftwareEcSigner("victim")
        val (export, _) = ExportFixtures.export(rows = 8, signer = signer)

        val forged = resealed(export, signer) { body ->
            @Suppress("UNCHECKED_CAST")
            val checkpoints = (body["checkpoints"] as List<Map<String, Any?>>).toMutableList()
            val c = checkpoints[0].toMutableMap()
            c["rowCount"] = 500L
            val payload = "${c["hash"]}|${c["timestampMs"]}|500".toByteArray()
            c["signature"] = Base64.getEncoder().encodeToString(signer.sign(payload))
            checkpoints[0] = c
            body["checkpoints"] = checkpoints
        }

        assertFalse(verify(forged).accepted)
    }

    @Test
    fun anUnknownRowFieldIsRejectedBecauseTheHashWouldNotCoverIt() {
        val signer = SoftwareEcSigner("victim")
        val (export, _) = ExportFixtures.export(rows = 6, signer = signer)

        val forged = resealed(export, signer) { body ->
            @Suppress("UNCHECKED_CAST")
            val ledger = (body["ledger"] as Map<String, Any?>).toMutableMap()
            @Suppress("UNCHECKED_CAST")
            val rows = (ledger["rows"] as List<Map<String, Any?>>).toMutableList()
            rows[2] = rows[2].toMutableMap().also { it["note"] = "smuggled" }
            ledger["rows"] = rows
            body["ledger"] = ledger
        }

        assertFalse(verify(forged).accepted)
    }

    @Test
    fun aMissingAttributionTagCannotBeSwappedForTheWordNullWithoutBreakingTheHash() {
        // The original canonical form wrote a missing value as the four letters "NULL", which the
        // text "NULL" also produced. Fixed: a missing value is a lone "N".
        val writer = LedgerWriter()
        writer.append(listOf(LedgerFx.episode("a", BASE)), nowMs = BASE)
        writer.flush(BASE + 10 * MINUTE)
        val row = writer.all().single()
        assertEquals(null, row.attributionTag)

        val swapped = row.copy(attributionTag = "NULL")

        assertNotEquals(row.hash, com.ojas.permsense.ledger.sealRecord(swapped).hash)
    }
}
