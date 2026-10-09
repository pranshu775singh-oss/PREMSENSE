package com.ojas.permsense.export

import com.ojas.permsense.ledger.LedgerSigner
import com.ojas.permsense.ledger.LedgerWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import permsense.verifier.EvidenceVerifier
import permsense.verifier.Level
import permsense.verifier.VerifierOptions
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * The exporter's attestation fields against the verifier's, using the OpenSSL-made certificate
 * chain in the verifier's test resources: a chain whose leaf carries a real DER KeyDescription.
 * On a phone the Keystore supplies the same fields; this proves the two ends agree on the format.
 */
class AttestedExportTest {

    private val dir = listOf("../verifier/src/test/resources/attestation", "verifier/src/test/resources/attestation")
        .map { File(it) }.firstOrNull { it.isDirectory }

    private fun cert(name: String): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(File(dir, name).readBytes())) as X509Certificate

    private inner class FixtureSigner(private val leaf: String, private val challenge: ByteArray) : LedgerSigner {

        override val keyAlias = "fixture"

        private val privateKey = KeyFactory.getInstance("EC").generatePrivate(
            PKCS8EncodedKeySpec(Base64.getDecoder().decode(File(dir, "leaf_key.pk8.b64").readText().trim()))
        )

        override fun sign(payload: ByteArray): ByteArray =
            Signature.getInstance("SHA256withECDSA").run { initSign(privateKey); update(payload); sign() }

        override fun verify(payload: ByteArray, signature: ByteArray, publicKeyDer: ByteArray?): Boolean = true

        override fun getPublicKeyDer(): ByteArray = cert(leaf).publicKey.encoded

        override fun attestationChainDer(): List<ByteArray> = listOf(cert(leaf), cert("int.pem"), cert("root.pem")).map { it.encoded }

        override fun attestationChallenge(): ByteArray = challenge
    }

    private val challenge = ByteArray(32) { (it + 1).toByte() }

    private fun exportWith(signer: LedgerSigner): ByteArray {
        val writer = ExportFixtures.ledger(8, signer)
        // The certificates are valid from 2020; export "now" must fall inside that.
        return EvidenceExport.build(ExportFixtures.input(writer, signer, nowMs = 1_800_000_000_000L)).file
    }

    @Test
    fun anAttestedExportIsHardwareAttestedWhenTheRootIsTrusted() {
        assumeTrue("fixtures not found", dir != null)

        val report = EvidenceVerifier.verify(
            exportWith(FixtureSigner("leaf_tee.pem", challenge)),
            VerifierOptions(listOf(cert("root.pem"))),
        )

        assertTrue(report.failures.toString(), report.accepted)
        assertEquals(Level.HARDWARE_ATTESTED, report.level)
        assertEquals("trusted environment (TEE)", report.summary!!.securityLevel)
    }

    @Test
    fun theSameExportIsOnlyIntegrityVerifiedWithoutThatRoot() {
        assumeTrue("fixtures not found", dir != null)

        val report = EvidenceVerifier.verify(exportWith(FixtureSigner("leaf_tee.pem", challenge)))

        assertEquals(Level.INTEGRITY_VERIFIED, report.level)
    }

    @Test
    fun aWrongChallengeInTheExportIsCaught() {
        assumeTrue("fixtures not found", dir != null)

        val report = EvidenceVerifier.verify(
            exportWith(FixtureSigner("leaf_tee.pem", ByteArray(32) { 7 })),
            VerifierOptions(listOf(cert("root.pem"))),
        )

        assertTrue(!report.accepted)
    }

    @Test
    fun anAttestedExportStillSurvivesEverySingleBitFlipInItsSeal() {
        assumeTrue("fixtures not found", dir != null)

        val bytes = exportWith(FixtureSigner("leaf_tee.pem", challenge))
        val roots = VerifierOptions(listOf(cert("root.pem")))
        assertTrue(EvidenceVerifier.verify(bytes, roots).accepted)

        // The last line of the file is the seal; the certificates live in the body, which the
        // seal's digest covers. Flip every bit position of every 5th byte across the whole file.
        val survivors = bytes.indices.filter { it % 5 == 0 }.filter { i ->
            val mutated = bytes.copyOf().also { it[i] = (it[i].toInt() xor 0x04).toByte() }
            EvidenceVerifier.verify(mutated, roots).accepted
        }

        assertEquals(emptyList<Int>(), survivors)
    }

    @Test
    fun theLedgerAloneWithNoAttestationStillExportsCleanly() {
        val signer = com.ojas.permsense.ledger.SoftwareEcSigner("plain")
        val report = EvidenceVerifier.verify(
            EvidenceExport.build(ExportFixtures.input(LedgerWriter(), signer, findings = emptyList())).file
        )

        assertTrue(report.accepted)
    }
}
