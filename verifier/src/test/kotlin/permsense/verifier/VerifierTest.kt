package permsense.verifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.X509Certificate
import java.util.Base64

/** The verifier tested against files built from the written format, with no app code involved. */
class VerifierTest {

    private val signer = TestSigner.fresh()

    private fun verify(bytes: ByteArray, roots: List<X509Certificate> = emptyList()) =
        EvidenceVerifier.verify(bytes, VerifierOptions(roots))

    private fun rejected(bytes: ByteArray, mention: String? = null) {
        val report = verify(bytes)
        assertFalse("should have been rejected", report.accepted)
        if (mention != null) {
            assertTrue("${report.checks}", report.failures.any { (it.name + " " + it.detail).contains(mention, ignoreCase = true) })
        }
    }

    // ------------------------------------------------------------------ the honest file

    @Test
    fun aWellFormedFileIsAccepted() {
        val report = verify(TestExports.file(signer))

        assertTrue(report.failures.toString(), report.accepted)
        assertEquals(Level.INTEGRITY_VERIFIED, report.level)
        assertEquals(5, report.summary!!.rows)
    }

    @Test
    fun anEmptyChainIsAccepted() {
        val report = verify(TestExports.file(signer, rows = emptyList(), checkpoints = emptyList()))

        assertTrue(report.failures.toString(), report.accepted)
    }

    // ------------------------------------------------------------------ hash chain

    @Test
    fun aRowThatDoesNotMatchItsHashIsRejected() {
        val rows = TestExports.chain(5)
        rows[2]["packageName"] = "com.example.changed"

        rejected(TestExports.file(signer, rows, checkpoints = emptyList()), "altered")
    }

    @Test
    fun aBrokenLinkIsRejected() {
        val rows = TestExports.chain(5).toMutableList()
        val fixed = TestExports.row(GENESIS_LIKE, 99)   // a valid row, but it does not follow row 1
        rows[2] = fixed

        rejected(TestExports.file(signer, rows, checkpoints = emptyList()), "link")
    }

    @Test
    fun aDroppedRowIsRejected() {
        val rows = TestExports.chain(6).toMutableList()
        rows.removeAt(3)

        rejected(TestExports.file(signer, rows, checkpoints = emptyList()), "link")
    }

    @Test
    fun aReorderedPairOfRowsIsRejected() {
        val rows = TestExports.chain(6).toMutableList()
        val a = rows[2]
        rows[2] = rows[3]
        rows[3] = a

        rejected(TestExports.file(signer, rows, checkpoints = emptyList()))
    }

    @Test
    fun aDuplicatedRowIsRejected() {
        val rows = TestExports.chain(4).toMutableList()
        rows.add(2, rows[2])

        rejected(TestExports.file(signer, rows, checkpoints = emptyList()))
    }

    @Test
    fun theStatedRowCountMustMatch() {
        rejected(
            TestExports.file(signer, checkpoints = emptyList()) { body ->
                @Suppress("UNCHECKED_CAST")
                body["ledger"] = (body["ledger"] as Map<String, Any?>) + ("rowCount" to 99L)
            },
            "count",
        )
    }

    @Test
    fun aMissingValueAndTheTextNullHashDifferently() {
        val none = TestExports.row(GENESIS_LIKE, 0, attributionTag = null)
        val text = TestExports.row(GENESIS_LIKE, 0, attributionTag = "NULL")

        assertTrue(none["hash"] != text["hash"])
    }

    @Test
    fun anExtraFieldInARowIsRejectedBecauseTheHashDoesNotCoverIt() {
        val rows = TestExports.chain(3)
        rows[1]["smuggled"] = "x"

        rejected(TestExports.file(signer, rows, checkpoints = emptyList()), "fields")
    }

    @Test
    fun aWrongTypeInARowIsRejected() {
        val rows = TestExports.chain(3)
        rows[1]["uid"] = "10100"

        rejected(TestExports.file(signer, rows, checkpoints = emptyList()), "integer")
    }

    @Test
    fun anUnsupportedRowSchemaIsRejected() {
        val rows = TestExports.chain(3)
        rows[0]["schemaVersion"] = 2L

        rejected(TestExports.file(signer, rows, checkpoints = emptyList()), "schema")
    }

    @Test
    fun aChainThatClaimsToStartAtGenesisButDoesNotIsRejected() {
        val rows = (0 until 3).fold(mutableListOf<MutableMap<String, Any?>>()) { acc, i ->
            acc.also { it += TestExports.row(if (i == 0) GENESIS_LIKE else it.last()["hash"] as String, i) }
        }

        rejected(TestExports.file(signer, rows, checkpoints = emptyList()), "genesis")
    }

    // ------------------------------------------------------------------ checkpoints

    @Test
    fun aCheckpointThatNamesTheWrongRowIsRejected() {
        val rows = TestExports.chain(6)
        val cp = TestExports.checkpoint(signer, rows, 4).toMutableMap()
        // Re-sign it for a different row so the signature is genuine but the position is wrong.
        val hash = rows[1]["hash"] as String
        val payload = "$hash|${cp["timestampMs"]}|4".toByteArray()
        cp["hash"] = hash
        cp["signature"] = TestExports.b64(signer.sign(payload))

        rejected(TestExports.file(signer, rows, checkpoints = listOf(cp)), "does not match the row")
    }

    @Test
    fun aCheckpointWithABadSignatureIsRejected() {
        val rows = TestExports.chain(4)
        val cp = TestExports.checkpoint(signer, rows, 4).toMutableMap()
        cp["timestampMs"] = (cp["timestampMs"] as Long) + 1

        rejected(TestExports.file(signer, rows, checkpoints = listOf(cp)), "invalid signature")
    }

    @Test
    fun aSealMadeWithADifferentKeyThanTheCheckpointsIsRejected() {
        val rows = TestExports.chain(4)
        val cp = TestExports.checkpoint(signer, rows, 4)
        val attacker = TestSigner.fresh()

        rejected(TestExports.file(attacker, rows, checkpoints = listOf(cp)), "different key")
    }

    @Test
    fun checkpointsFromTwoKeysAreRejected() {
        val rows = TestExports.chain(6)
        val other = TestSigner.fresh()

        rejected(
            TestExports.file(signer, rows, checkpoints = listOf(TestExports.checkpoint(signer, rows, 3), TestExports.checkpoint(other, rows, 6))),
            "more than one key",
        )
    }

    @Test
    fun aCheckpointBeyondTheEndOfTheChainIsRejected() {
        val rows = TestExports.chain(3)
        val ahead = TestExports.checkpoint(signer, TestExports.chain(9), 9)

        rejected(TestExports.file(signer, rows, checkpoints = listOf(ahead)), "not in the file")
    }

    // ------------------------------------------------------------------ the seal

    @Test
    fun aBodyChangedAfterSealingIsRejected() {
        val file = TestExports.file(signer).toString(Charsets.UTF_8)
        val changed = file.replaceFirst("\"app\":\"test\"", "\"app\":\"tesT\"")

        rejected(changed.toByteArray(), "changed after it was sealed")
    }

    @Test
    fun aSealSignedByNobodyInParticularIsRejected() {
        val file = TestExports.file(signer).toString(Charsets.UTF_8)
        val lines = file.split('\n')
        val seal = lines[1].replace(Regex("\"signature\":\"[^\"]+\""), "\"signature\":\"" + TestExports.b64(ByteArray(70)) + "\"")

        rejected((lines[0] + "\n" + seal + "\n").toByteArray(), "signature")
    }

    @Test
    fun nonCanonicalBase64IsRejectedSoNoCharacterCanChangeUnseen() {
        val file = TestExports.file(signer).toString(Charsets.UTF_8)
        val lines = file.split('\n')

        // A P-256 public key is 91 bytes, so its base64 ends in "==" and the character before the
        // padding carries four unused bits. Flip one: a different text that decodes to the same key.
        val publicKey = TestExports.b64(signer.publicKeyDer)
        assertTrue(publicKey.endsWith("=="))
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val at = publicKey.length - 3
        val twin = publicKey.substring(0, at) + alphabet[alphabet.indexOf(publicKey[at]) xor 1] + "=="

        assertTrue(twin != publicKey)
        assertTrue(Base64.getDecoder().decode(twin).contentEquals(signer.publicKeyDer))   // same bytes

        val seal = lines[1].replace(publicKey, twin)
        rejected((lines[0] + "\n" + seal + "\n").toByteArray(), "canonical")
    }

    @Test
    fun theSealDomainSeparatesItFromCheckpointSignatures() {
        // A checkpoint signature must never be accepted as a seal, even over the same digest.
        val file = TestExports.file(signer).toString(Charsets.UTF_8)
        val lines = file.split('\n')
        val digest = Regex("\"digest\":\"([0-9a-f]+)\"").find(lines[1])!!.groupValues[1]
        val wrong = TestExports.b64(signer.sign(digest.toByteArray()))
        val seal = lines[1].replace(Regex("\"signature\":\"[^\"]+\""), "\"signature\":\"$wrong\"")

        rejected((lines[0] + "\n" + seal + "\n").toByteArray(), "signature")
    }

    // ------------------------------------------------------------------ findings

    @Test
    fun aFindingThatCitesAMissingRowIsRejected() {
        rejected(
            TestExports.file(signer, findings = listOf(mapOf("evidenceHashes" to listOf("a".repeat(64))))),
            "not in the file",
        )
    }

    @Test
    fun aFindingThatCitesAPresentRowIsAccepted() {
        val rows = TestExports.chain(4)

        val report = verify(TestExports.file(signer, rows, findings = listOf(mapOf("evidenceHashes" to listOf(rows[1]["hash"])))))

        assertTrue(report.failures.toString(), report.accepted)
    }

    // ------------------------------------------------------------------ attestation

    private val roots: List<X509Certificate> get() = listOf(Fixtures.cert("root.pem"))

    private fun attested(leaf: String, vararg chain: String, challenge: ByteArray = Fixtures.CHALLENGE): ByteArray {
        val s = TestSigner.attested(leaf, *chain, challenge = challenge)
        return TestExports.file(s)
    }

    @Test
    fun aHardwareBackedKeyWithAChainToATrustedRootIsHardwareAttested() {
        val report = verify(attested("leaf_tee.pem", "int.pem", "root.pem"), roots)

        assertTrue(report.failures.toString(), report.accepted)
        assertEquals(Level.HARDWARE_ATTESTED, report.level)
        assertEquals("trusted environment (TEE)", report.summary!!.securityLevel)
    }

    @Test
    fun strongBoxIsRecognised() {
        val report = verify(attested("leaf_strongbox.pem", "int.pem", "root.pem"), roots)

        assertEquals(Level.HARDWARE_ATTESTED, report.level)
        assertEquals("StrongBox", report.summary!!.securityLevel)
    }

    @Test
    fun theChainNeedNotIncludeTheRootItself() {
        val report = verify(attested("leaf_tee.pem", "int.pem"), roots)

        assertEquals(Level.HARDWARE_ATTESTED, report.level)
    }

    @Test
    fun aKeyHeldInSoftwareIsNeverCalledHardware() {
        val report = verify(attested("leaf_software.pem", "int.pem", "root.pem"), roots)

        assertTrue(report.accepted)
        assertEquals(Level.INTEGRITY_VERIFIED, report.level)
        assertTrue(report.notes.any { it.contains("software") })
    }

    @Test
    fun aChainThatDoesNotLeadToATrustedRootProvesNothing() {
        val report = verify(attested("leaf_other.pem", "otherint.pem", "otherroot.pem"), roots)

        assertTrue(report.accepted)
        assertEquals(Level.INTEGRITY_VERIFIED, report.level)
        assertTrue(report.notes.any { it.contains("trusted root") })
    }

    @Test
    fun withNoTrustedRootsNothingIsEverHardwareAttested() {
        val report = verify(attested("leaf_tee.pem", "int.pem", "root.pem"), emptyList())

        assertEquals(Level.INTEGRITY_VERIFIED, report.level)
    }

    @Test
    fun aBrokenLinkInTheCertificateChainIsRejected() {
        // The leaf says it was issued by the intermediate, but the chain offers the other root's.
        rejected(attested("leaf_tee.pem", "otherint.pem", "root.pem"), "not signed by")
    }

    @Test
    fun anAttestationForADifferentChallengeIsRejected() {
        rejected(
            attested("leaf_tee.pem", "int.pem", "root.pem", challenge = ByteArray(32) { 9 }),
            "challenge",
        )
    }

    @Test
    fun aCertifiedKeyThatIsNotTheSigningKeyIsRejected() {
        val impostor = TestSigner(TestSigner.fresh().keyPair, chain = listOf(Fixtures.cert("leaf_tee.pem").encoded, Fixtures.cert("int.pem").encoded), challenge = Fixtures.CHALLENGE)

        rejected(TestExports.file(impostor), "not the key that signed")
    }

    @Test
    fun aLeafWithoutAnAttestationRecordIsRejected() {
        rejected(attested("leaf_noext.pem", "int.pem", "root.pem"), "no attestation record")
    }

    @Test
    fun aMalformedAttestationRecordIsRejectedNotCrashed() {
        rejected(attested("leaf_malformed.pem", "int.pem", "root.pem"), "malformed")
    }

    @Test
    fun aCertificateThatWasNotYetValidIsRejected() {
        rejected(attested("leaf_future.pem", "int.pem", "root.pem"), "not valid")
    }

    @Test
    fun aCertificateThatHadExpiredIsRejected() {
        rejected(attested("leaf_expired.pem", "int.pem", "root.pem"), "not valid")
    }

    @Test
    fun aGarbledCertificateIsRejectedNotCrashed() {
        val s = TestSigner.attested("leaf_tee.pem", "int.pem")
        val garbled = TestSigner(s.keyPair, chain = listOf(ByteArray(40) { 7 }), challenge = s.challenge)

        rejected(TestExports.file(garbled))
    }

    // ------------------------------------------------------------------ DER

    @Test
    fun derRefusesWhatIsNotWellFormed() {
        for (bad in listOf(
            byteArrayOf(), byteArrayOf(0x30), byteArrayOf(0x30, 0x05, 0x01), byteArrayOf(0x30, 0x80.toByte()),
            byteArrayOf(0x30, 0x84.toByte(), 0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()), byteArrayOf(0x1F, 0x01, 0x00),
        )) {
            try {
                Der.parse(bad)
                org.junit.Assert.fail("accepted ${bad.toList()}")
            } catch (_: DerProblem) {
            }
        }
    }

    @Test
    fun derReadsTheKeyDescriptionTheFixturesCarry() {
        val ext = Fixtures.cert("leaf_tee.pem").getExtensionValue(KeyDescription.OID)
        assertNotNull(ext)

        val description = KeyDescription.parse(ext)

        assertEquals(1, description.attestationSecurityLevel)
        assertEquals(4L, description.attestationVersion)
        assertTrue(description.challenge.contentEquals(Fixtures.CHALLENGE))
    }

    // ------------------------------------------------------------------ the shipped roots

    @Test
    fun everyBundledRootParsesAndCarriesAValidSelfSignature() {
        val roots = TrustedRoots.bundled()

        assertEquals(5, roots.size)
        for (root in roots) {
            // A root vouches for itself; a corrupted character anywhere would break this.
            root.verify(root.publicKey)
        }
    }

    @Test
    fun theBundledRootsAreTheOnesGoogleDocuments() {
        val roots = TrustedRoots.bundled()

        // Four RSA roots that share one key (reissued over the years), and the newer ECDSA root.
        val rsa = roots.filter { it.publicKey is java.security.interfaces.RSAPublicKey }
        assertEquals(4, rsa.size)
        assertEquals(1, rsa.map { it.publicKey.encoded.toList() }.distinct().size)
        assertEquals(1, roots.count { it.subjectX500Principal.name.contains("Key Attestation CA1") })

        // The two roots Google serves from its attestation endpoint today, by SHA-256 of the DER.
        val digests = roots.map {
            java.security.MessageDigest.getInstance("SHA-256").digest(it.encoded).joinToString("") { b -> "%02x".format(b) }
        }
        assertTrue("cedb1cb6dc896ae5ec797348bce9286753c2b38ee71ce0fbe34a9a1248800dfc" in digests)
        assertTrue("6d9db4ce6c5c0b293166d08986e05774a8776ceb525d9e4329520de12ba4bcc0" in digests)
    }

    @Test
    fun theTestRootIsNotAmongTheBundledRoots() {
        val bundled = TrustedRoots.bundled().map { it.encoded.toList() }

        assertFalse(Fixtures.cert("root.pem").encoded.toList() in bundled)
    }

    private companion object {
        /** Some hash that is not the genesis hash, for building deliberately wrong links. */
        val GENESIS_LIKE = "1".repeat(64)
        val GENESIS = TestExports.GENESIS
    }
}
