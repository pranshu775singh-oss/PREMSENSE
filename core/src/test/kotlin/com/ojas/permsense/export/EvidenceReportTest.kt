package com.ojas.permsense.export

import com.ojas.permsense.findings.Finding
import com.ojas.permsense.findings.SuggestedAction
import com.ojas.permsense.i18n.Lang
import com.ojas.permsense.ledger.SoftwareEcSigner
import com.ojas.permsense.model.Ops
import com.ojas.permsense.remediation.AppOpModes
import com.ojas.permsense.remediation.AppState
import com.ojas.permsense.remediation.PrivilegedChannel
import com.ojas.permsense.remediation.RemediationExecutor
import com.ojas.permsense.remediation.RemediationKind
import com.ojas.permsense.remediation.RemediationRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class EvidenceReportTest {

    private val zone = ZoneId.of("UTC")

    private fun render(lang: Lang = Lang.EN, rows: Int = 6, label: (String) -> String = { it }): Pair<String, ExportInput> {
        val signer = SoftwareEcSigner("report")
        val writer = ExportFixtures.ledger(rows, signer)
        val input = ExportFixtures.input(writer, signer)
        val digest = EvidenceExport.build(input).digest
        return EvidenceReport.render(input, digest, lang, label, zone) to input
    }

    @Test
    fun theReportStatesWhatItDoesNotProveBeforeAnythingElse() {
        val (html, _) = render()

        val limits = html.indexOf("does not prove")
        assertTrue(limits > 0)
        assertTrue(limits < html.indexOf("Findings"))
        assertTrue(html.contains("no claim that this report is admissible"))
    }

    @Test
    fun theReportCarriesTheFingerprintOfTheMachineReadableFile() {
        val signer = SoftwareEcSigner("fp")
        val writer = ExportFixtures.ledger(5, signer)
        val input = ExportFixtures.input(writer, signer)
        val export = EvidenceExport.build(input)

        val html = EvidenceReport.render(input, export.digest, Lang.EN, { it }, zone)

        assertTrue(html.contains(export.digest))
    }

    @Test
    fun everyFindingShowsWhatWhoWhenAndWhy() {
        val (html, input) = render(label = { if (it == "com.example.lender") "Quick Lender" else it })

        for (f in input.findings) {
            assertTrue(html.contains(f.packageName))
        }
        assertTrue(html.contains("Quick Lender"))
        assertTrue(html.contains("Why it was flagged"))
        assertTrue(html.contains("When"))
        assertTrue(html.contains("Basis"))
    }

    @Test
    fun anInventoryOnlyFindingSaysItWasNotUsedYet() {
        val (html, _) = render()

        assertTrue(html.contains("Not used yet"))
    }

    @Test
    fun hindiReportsAreWrittenInHindi() {
        val (html, _) = render(Lang.HI)

        assertTrue(html.contains("lang=\"hi\""))
        assertTrue(html.contains("साक्ष्य रिपोर्ट"))
        assertTrue(html.contains("क्यों चिह्नित किया गया"))
        assertTrue(html.contains("किसी कानूनी कार्यवाही में स्वीकार्य"))
    }

    @Test
    fun anAppNameCannotInjectMarkupIntoTheReport() {
        val (html, _) = render(label = { "<script>alert(1)</script>" })

        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;"))
    }

    @Test
    fun theRuleReviewCaveatIsInTheReport() {
        val (html, _) = render()

        assertTrue(html.contains("have not yet been confirmed by a reviewer"))
        assertTrue(html.contains("RBI-01"))
    }

    @Test
    fun aReportOfAnEmptyLedgerStillRenders() {
        val signer = SoftwareEcSigner("empty")
        val writer = com.ojas.permsense.ledger.LedgerWriter()
        val input = ExportFixtures.input(writer, signer, findings = emptyList())

        val html = EvidenceReport.render(input, "d".repeat(64), Lang.EN, { it }, zone)

        assertTrue(html.contains("Nothing was flagged."))
        assertTrue(html.contains("</html>"))
    }

    @Test
    fun changesMadeThroughPermSenseAreListedWithTheirOutcome() {
        val signer = SoftwareEcSigner("actions")
        val writer = ExportFixtures.ledger(3, signer)
        val phone = object : PrivilegedChannel {
            var granted = true
            override fun readState(request: RemediationRequest) = AppState(permissionGranted = granted)
            override fun execute(request: RemediationRequest) { granted = false }
        }
        val executor = RemediationExecutor(phone, writer, "com.ojas.permsense", { true }, { ExportFixtures.BASE + 600_000L })
        executor.execute(
            RemediationRequest(RemediationKind.REVOKE_PERMISSION, "com.example.lender", "android.permission.READ_CONTACTS"),
            uid = 10200,
        )

        val html = EvidenceReport.render(ExportFixtures.input(writer, signer, findings = emptyList()), "a".repeat(64), Lang.EN, { it }, zone)

        assertTrue(html.contains("revoke permission"))
        assertTrue(html.contains("GRANTED → REVOKED"))
        assertTrue(html.contains("done"))
        assertFalse(html.contains("None."))
    }

    @Test
    fun theReportIsWellFormedEnoughToOpenAnywhere() {
        val (html, _) = render()

        assertTrue(html.startsWith("<!doctype html>"))
        assertEquals(html.split("<section").size, html.split("</section>").size)
        assertEquals(html.split("<article").size, html.split("</article>").size)
        assertTrue(html.contains("<meta charset=\"utf-8\">"))
    }

    @Test
    fun anOpIsNeverShownAsARawAndroidName() {
        val signer = SoftwareEcSigner("ops")
        val writer = ExportFixtures.ledger(4, signer)
        val rows = writer.all()
        val finding = Finding.PolicyViolation(
            "com.example.notes", "INV-01", "t", "e", "c", Ops.CONTACTS, Finding.Basis.LEDGER,
            rows.take(2), SuggestedAction.None, params = mapOf("count" to "2"),
        )

        val html = EvidenceReport.render(ExportFixtures.input(writer, signer, findings = listOf(finding)), "b".repeat(64), Lang.EN, { it }, zone)

        assertTrue(html.contains("contacts"))
        assertFalse(html.contains("android:read_contacts &middot;"))
        assertTrue(AppOpModes.name(1) == "IGNORED")
    }
}
