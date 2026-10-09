package com.ojas.permsense.export

import com.ojas.permsense.findings.Finding
import com.ojas.permsense.findings.SuggestedAction
import com.ojas.permsense.judgement.PolicyPackLoader
import com.ojas.permsense.ledger.LedgerFx
import com.ojas.permsense.ledger.LedgerSigner
import com.ojas.permsense.ledger.LedgerWriter
import com.ojas.permsense.ledger.SoftwareEcSigner
import com.ojas.permsense.model.Ops

/** Builds realistic exports for the tests of the exporter and the verifier. */
object ExportFixtures {

    const val BASE = 1_760_000_000_000L
    const val MINUTE = 60_000L

    private val packages = listOf("com.example.lender", "com.example.notes", "com.example.maps")
    private val ops = listOf(Ops.CONTACTS, Ops.CAMERA, Ops.FINE_LOC, Ops.MIC)

    /** A ledger of [rows] rows with a signed checkpoint after every [checkpointEvery]. */
    fun ledger(rows: Int, signer: LedgerSigner, checkpointEvery: Int = 4): LedgerWriter {

        val writer = LedgerWriter()

        for (i in 0 until rows) {
            val ts = BASE + i * 10 * MINUTE
            writer.append(
                listOf(
                    LedgerFx.episode(
                        listOf("evt-$i"),
                        ts,
                        pkg = packages[i % packages.size],
                        op = ops[i % ops.size],
                        version = 100L + i / 5,
                    )
                ),
                nowMs = ts,
            )
            writer.flush(ts + 10 * MINUTE)
            if ((i + 1) % checkpointEvery == 0) writer.createCheckpoint(signer, ts + 5 * MINUTE)
        }

        return writer
    }

    fun findings(writer: LedgerWriter): List<Finding> {
        val rows = writer.all()
        return listOf(
            Finding.PolicyViolation(
                packageName = packages[0],
                ruleId = "INV-01",
                title = "Used contacts while your screen was off",
                explanation = "This app used contacts 2 time(s) while it was not on screen.",
                citation = "Contextual integrity",
                op = Ops.CONTACTS,
                basis = Finding.Basis.LEDGER,
                evidence = rows.take(2),
                suggestedAction = SuggestedAction.OpenAppSettings(packages[0]),
                params = mapOf("count" to "2"),
            ),
            Finding.PolicyViolation(
                packageName = packages[1],
                ruleId = "RBI-01",
                title = "Lending app asks for your contacts",
                explanation = "x",
                citation = "RBI",
                op = null,
                basis = Finding.Basis.INVENTORY,
                evidence = emptyList(),
                suggestedAction = SuggestedAction.None,
                severity = 2,
                params = mapOf("state" to "requested"),
            ),
        )
    }

    fun input(
        writer: LedgerWriter,
        signer: LedgerSigner,
        nowMs: Long = BASE + 500 * MINUTE,
        findings: List<Finding> = findings(writer),
    ) = ExportInput(
        rows = writer.all(),
        checkpoints = writer.allCheckpoints(),
        chainStart = writer.chainStart(),
        findings = findings,
        pack = PolicyPackLoader.loadBundled(),
        signer = signer,
        nowMs = nowMs,
        appVersion = "1.0-test",
        device = mapOf("model" to "Test Phone", "sdk" to "36"),
    )

    fun export(rows: Int, signer: LedgerSigner = SoftwareEcSigner("export_test"), checkpointEvery: Int = 4): Pair<ExportResult, ExportInput> {
        val writer = ledger(rows, signer, checkpointEvery)
        val input = input(writer, signer)
        return EvidenceExport.build(input) to input
    }
}
