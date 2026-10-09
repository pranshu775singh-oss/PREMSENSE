package com.ojas.permsense.export

import com.ojas.permsense.i18n.Lang
import com.ojas.permsense.ledger.SoftwareEcSigner
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

/**
 * Writes real exporter output to build/samples/, so that other implementations of the checker (the
 * browser page, a reader's own script) can be tried against files made by the real code, and so the
 * human-readable report can be opened and read.
 */
class SampleFilesTest {

    @Test
    fun writeSamples() {
        val dir = File("build/samples").also { it.mkdirs() }

        val signer = SoftwareEcSigner("sample")
        val writer = ExportFixtures.ledger(16, signer)
        val input = ExportFixtures.input(writer, signer)
        val export = EvidenceExport.build(input)

        File(dir, "evidence_ok.json").writeBytes(export.file)

        for (lang in Lang.values()) {
            File(dir, "report_${lang.tag}.html").writeText(
                EvidenceReport.render(
                    input, export.digest, lang,
                    { if (it == "com.example.lender") "Quick Lender (test)" else it },
                    ZoneId.of("Asia/Kolkata"),
                ),
                Charsets.UTF_8,
            )
        }

        assertTrue(File(dir, "evidence_ok.json").length() > 1_000)
    }
}
