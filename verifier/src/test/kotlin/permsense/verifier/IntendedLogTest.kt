package permsense.verifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * The probe app's signed log, checked from its written description rather than from the code that
 * writes it (the same independence the evidence-report tests have).
 */
class IntendedLogTest {

    // ------------------------------------------------------------------ building logs

    private class Spec(
        val op: String = "android:read_contacts",
        val trigger: String = "JOB",
        val step: String = "sync-contacts",
        val interactive: Boolean = false,
        val keyguard: Boolean = true,
        val importance: Long = 400,
        val expectedStratum: String = "SCREEN_OFF",
        val outcome: String = "OK",
        val detail: String = "rows=120",
    )

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    private fun sign(signer: TestSigner, canonical: String) = b64(signer.sign(canonical.toByteArray()))

    private fun json(values: Map<String, Any?>): String =
        values.entries.joinToString(",", "{", "}") { (k, v) ->
            "\"$k\":" + when (v) {
                null -> "null"
                is String -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                is List<*> -> v.joinToString(",", "[", "]") { "\"$it\"" }
                else -> v.toString()
            }
        }

    private fun build(
        signer: TestSigner,
        specs: List<Spec> = (1..6).map { Spec(detail = "rows=${100 + it}") },
        versionCode: Long = 2,
        createdMs: Long = 1_800_000_000_000L,
        chainOverride: List<ByteArray>? = null,
    ): List<String> {

        val publicKey = b64(signer.publicKeyDer)
        val chain = (chainOverride ?: signer.chain).map { b64(it) }

        val headerCanonical = listOf("h1", createdMs, versionCode, publicKey, chain.size).joinToString("|")
        val lines = ArrayList<String>()
        lines += json(
            linkedMapOf(
                "type" to "header", "format" to 1L, "createdMs" to createdMs, "versionCode" to versionCode,
                "publicKey" to publicKey, "attestation" to chain, "sig" to sign(signer, headerCanonical),
            )
        )

        var previous = headerCanonical
        specs.forEachIndexed { i, s ->
            val seq = (i + 1).toLong()
            val ts = createdMs + 1_000 * seq
            val prev = sha256Hex(previous)
            val canonical = listOf(
                "a1", seq, ts, versionCode, s.op, s.trigger, s.step, s.interactive, s.keyguard,
                s.importance, s.expectedStratum, s.outcome, s.detail, prev,
            ).joinToString("|")
            lines += json(
                linkedMapOf(
                    "type" to "access", "seq" to seq, "tsMs" to ts, "versionCode" to versionCode, "op" to s.op,
                    "trigger" to s.trigger, "step" to s.step, "interactive" to s.interactive, "keyguard" to s.keyguard,
                    "importance" to s.importance, "expectedStratum" to s.expectedStratum, "outcome" to s.outcome,
                    "detail" to s.detail, "prev" to prev, "sig" to sign(signer, canonical),
                )
            )
            previous = canonical
        }
        return lines
    }

    private fun file(lines: List<String>): ByteArray = (lines.joinToString("\n") + "\n").toByteArray()

    private val roots get() = VerifierOptions(trustedRoots = listOf(Fixtures.cert("root.pem")))

    // ------------------------------------------------------------------ acceptance

    @Test
    fun anUntouchedLogIsAcceptedAndItsAccessesAreReadBack() {
        val report = IntendedLogVerifier.verify(file(build(TestSigner.fresh())))

        assertEquals(report.checks.toString(), Level.INTEGRITY_VERIFIED, report.level)
        assertEquals(6, report.accesses.size)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), report.accesses.map { it.seq })
        assertEquals("SCREEN_OFF", report.accesses.first().expectedStratum)
        assertEquals("android:read_contacts", report.accesses.first().op)
        assertTrue(report.notes.any { it.contains("no attestation chain") })
    }

    @Test
    fun aLogWithOnlyAHeaderIsValidAndEmpty() {
        val report = IntendedLogVerifier.verify(file(build(TestSigner.fresh(), specs = emptyList())))

        assertEquals(Level.INTEGRITY_VERIFIED, report.level)
        assertTrue(report.accesses.isEmpty())
    }

    @Test
    fun aKeyAttestedToSecureHardwareMakesTheLogHardwareAttested() {
        val signer = TestSigner.attested("leaf_tee.pem", "int.pem", "root.pem")

        val report = IntendedLogVerifier.verify(file(build(signer)), roots)

        assertEquals(report.checks.toString() + report.notes, Level.HARDWARE_ATTESTED, report.level)
        assertEquals("trusted environment (TEE)", report.securityLevel)
    }

    @Test
    fun aSoftwareKeyIsIntegrityOnlyNeverHardware() {
        val signer = TestSigner.attested("leaf_software.pem", "int.pem", "root.pem")

        val report = IntendedLogVerifier.verify(file(build(signer)), roots)

        assertEquals(Level.INTEGRITY_VERIFIED, report.level)
        assertTrue(report.notes.any { it.contains("software") })
    }

    @Test
    fun aChainThatDoesNotReachATrustedRootProvesNothingAboutTheDevice() {
        val signer = TestSigner.attested("leaf_tee.pem", "otherint.pem", "otherroot.pem")

        val report = IntendedLogVerifier.verify(file(build(signer)), roots)

        assertTrue(report.level != Level.HARDWARE_ATTESTED)
    }

    // ------------------------------------------------------------------ rejection

    @Test
    fun changingAnyByteOfTheLogIsCaught() {
        val original = file(build(TestSigner.fresh(), specs = (1..8).map { Spec(detail = "rows=$it") }))
        var tried = 0

        for (i in original.indices step 2) {
            val mutated = original.copyOf()
            mutated[i] = (mutated[i].toInt() xor 0x01).toByte()
            tried++

            val report = IntendedLogVerifier.verify(mutated)
            assertFalse("a change at byte $i of ${original.size} was not caught", report.accepted)
        }

        assertTrue(tried > 500)
    }

    @Test
    fun aLineRemovedFromTheMiddleIsCaught() {
        val lines = build(TestSigner.fresh())

        val report = IntendedLogVerifier.verify(file(lines.filterIndexed { i, _ -> i != 3 }))

        assertFalse(report.accepted)
        assertTrue(report.checks.last().detail, report.checks.last().detail.contains("order") || report.checks.last().detail.contains("link"))
    }

    @Test
    fun twoLinesSwappedAreCaught() {
        val lines = build(TestSigner.fresh()).toMutableList()
        lines[2] = lines[3].also { lines[3] = lines[2] }

        assertFalse(IntendedLogVerifier.verify(file(lines)).accepted)
    }

    @Test
    fun aLineRepeatedIsCaught() {
        val lines = build(TestSigner.fresh()).toMutableList()
        lines.add(3, lines[3])

        assertFalse(IntendedLogVerifier.verify(file(lines)).accepted)
    }

    @Test
    fun aLineInventedWithoutTheKeyIsCaught() {
        val real = TestSigner.fresh()
        val forger = TestSigner.fresh()
        val genuine = build(real)
        val forged = build(forger)

        // The forger's access line, signed by the forger, spliced into the real log.
        val spliced = genuine.take(4) + forged[4] + genuine.drop(5)

        assertFalse(IntendedLogVerifier.verify(file(spliced)).accepted)
    }

    @Test
    fun aWholeLogRewrittenWithAnotherKeyIsAcceptedOnlyAsThatKeysLog() {
        // Nothing here can tell whose key it is: that is what attestation, and the person who ran
        // the test, are for. The verifier says so by never calling this hardware-attested.
        val report = IntendedLogVerifier.verify(file(build(TestSigner.fresh())))

        assertEquals(Level.INTEGRITY_VERIFIED, report.level)
    }

    @Test
    fun anAttestationForAnotherKeyIsRejected() {
        // The chain is genuine, but its first certificate certifies the intermediate's key, not the
        // key that signed the log.
        val key = TestSigner.fixtureKey()
        val signer = TestSigner(key, chain = listOf(Fixtures.cert("int.pem").encoded, Fixtures.cert("root.pem").encoded))

        val report = IntendedLogVerifier.verify(file(build(signer)), roots)

        assertFalse(report.accepted)
        assertTrue(report.checks.last().detail.contains("not the key that signed"))
    }

    @Test
    fun anExpiredAttestationIsRejected() {
        val signer = TestSigner.attested("leaf_expired.pem", "int.pem", "root.pem")

        assertFalse(IntendedLogVerifier.verify(file(build(signer)), roots).accepted)
    }

    @Test
    fun cuttingTheEndOffIsNotDetectedByTheLogAloneAndTheReportSaysWhatItCovers() {
        // A prefix of a valid chain is a valid chain. This is a property of any hash chain, stated
        // here so nobody assumes otherwise: compare the count with what the run should have produced.
        val lines = build(TestSigner.fresh())

        val report = IntendedLogVerifier.verify(file(lines.take(4)))

        assertTrue(report.accepted)
        assertEquals(3, report.accesses.size)
    }

    @Test
    fun aFieldCarryingTheSeparatorIsRefusedBeforeItCanBlurTwoFieldsTogether() {
        val lines = build(TestSigner.fresh(), specs = listOf(Spec(step = "a|b")))

        assertFalse(IntendedLogVerifier.verify(file(lines)).accepted)
    }

    @Test
    fun anUnknownStratumOrOutcomeIsRefused() {
        assertFalse(IntendedLogVerifier.verify(file(build(TestSigner.fresh(), specs = listOf(Spec(expectedStratum = "SOMEWHERE"))))).accepted)
        assertFalse(IntendedLogVerifier.verify(file(build(TestSigner.fresh(), specs = listOf(Spec(outcome = "MAYBE"))))).accepted)
    }

    @Test
    fun garbageIsRejectedNotCrashedOn() {
        for (junk in listOf("", "\n", "not json\n", "{}\n", "[]\n", "{\"type\":\"header\"}\n", "\u0000\u0001\n")) {
            assertFalse("\"$junk\"", IntendedLogVerifier.verify(junk.toByteArray()).accepted)
        }
        assertFalse(IntendedLogVerifier.verify(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x0A)).accepted)
    }

    @Test
    fun aMissingFinalNewlineOrAStrayCarriageReturnIsRefused() {
        val text = String(file(build(TestSigner.fresh())))

        assertFalse(IntendedLogVerifier.verify(text.removeSuffix("\n").toByteArray()).accepted)
        assertFalse(IntendedLogVerifier.verify(text.replace("\n", "\r\n").toByteArray()).accepted)
    }

    // ------------------------------------------------------------------ samples for other checkers

    @Test
    fun writeSamples() {
        val dir = File("build/samples").also { it.mkdirs() }
        File(dir, "intended_ok.jsonl").writeBytes(file(build(TestSigner.fresh())))
        assertTrue(File(dir, "intended_ok.jsonl").length() > 500)
    }

    // ------------------------------------------------------------------ the command line

    @Test
    fun theCommandLineSaysAcceptedOrRejectedWithTheRightExitStatus() {
        val dir = File(System.getProperty("java.io.tmpdir"), "permsense-intended-${System.nanoTime()}").also { it.mkdirs() }
        try {
            val good = File(dir, "good.jsonl").also { it.writeBytes(file(build(TestSigner.fresh()))) }
            val bad = File(dir, "bad.jsonl").also { it.writeBytes(file(build(TestSigner.fresh())).also { b -> b[40] = (b[40].toInt() xor 1).toByte() }) }

            assertEquals(0, runIntendedLog(listOf(good.path)))
            assertEquals(2, runIntendedLog(listOf(bad.path)))
            assertEquals(3, runIntendedLog(listOf(good.path, "--require-hardware")))
            assertEquals(64, runIntendedLog(emptyList()))
            assertEquals(64, runIntendedLog(listOf(File(dir, "missing").path)))
            assertEquals(0, run(listOf("intended-log", good.path)))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theTextReportStatesWhatItDoesNotShow() {
        val report = IntendedLogVerifier.verify(file(build(TestSigner.fresh())))

        val text = renderIntended(report)

        assertTrue(text.contains("INTEGRITY VERIFIED"))
        assertTrue(text.contains("does not show the accesses really happened"))
        assertTrue(text.contains("By operation"))
    }
}
