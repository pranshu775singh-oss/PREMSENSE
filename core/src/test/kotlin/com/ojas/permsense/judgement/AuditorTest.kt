package com.ojas.permsense.judgement

import com.ojas.permsense.baseline.RowFx
import com.ojas.permsense.baseline.RowFx.DAY
import com.ojas.permsense.baseline.RowFx.HOUR
import com.ojas.permsense.baseline.RowFx.dayStart
import com.ojas.permsense.findings.Finding
import com.ojas.permsense.findings.SuggestedAction
import com.ojas.permsense.model.AppVersionPoint
import com.ojas.permsense.model.Ops
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** All three judgement layers together, on a scenario like the live demo. */
class AuditorTest {

    private val pkg = "com.ojas.quickcash"
    private val P = "android.permission."

    private fun app(version: Long, pkg: String = this.pkg, system: Boolean = false) = Fx.app(
        version = version,
        lastUpdate = dayStart(if (version == 1L) 0 else 14),
        pkg = pkg,
        requested = listOf(P + "READ_CONTACTS"),
        granted = listOf(P + "READ_CONTACTS"),
        system = system,
    )

    private val history = mapOf(
        pkg to listOf(AppVersionPoint(1, dayStart(0)), AppVersionPoint(2, dayStart(14))),
    )

    /** v1 reads contacts once a day in the background; v2 starts reading eight times a day. */
    private val rows =
        RowFx.steady(0, 14, 1, pkg = pkg, version = 1) +
                RowFx.steady(14, 2, 8, pkg = pkg, version = 2)

    private fun input(
        rows: List<com.ojas.permsense.ledger.LedgerRecord> = this.rows,
        inventory: List<com.ojas.permsense.model.AppInventory> = listOf(app(2)),
    ) = AuditInput(
        ledgerRows = rows,
        inventory = inventory,
        nowMs = dayStart(15) + 12 * HOUR,
        observingSinceMs = dayStart(0),
        zoneOffsetMs = 0L,
        countingSinceMs = dayStart(0),
        indicatorSinceMs = dayStart(0),
        versionHistory = history,
    )

    @Test
    fun anUpdateThatMakesAnAppReadContactsEightTimesADayRaisesEveryLayer() {
        val result = Auditor().analyse(input())

        val kinds = result.findings.map {
            when (it) {
                is Finding.PolicyViolation -> "policy:${it.ruleId}"
                is Finding.BaselineDeviation -> "baseline"
                is Finding.UpdateDrift -> "drift"
                is Finding.SustainedShift -> "shift"
            }
        }.toSet()

        assertTrue(kinds.toString(), "policy:RBI-01" in kinds)
        assertTrue(kinds.toString(), "drift" in kinds)
        assertTrue(kinds.toString(), "baseline" in kinds)
    }

    @Test
    fun theDriftFindingNamesTheUpdateAndTheRates() {
        val drift = Auditor().analyse(input()).findings.filterIsInstance<Finding.UpdateDrift>().single()

        assertEquals(1L, drift.fromVersion)
        assertEquals(2L, drift.toVersion)
        assertTrue(drift.ratePerDayAfter > 3 * drift.ratePerDayBefore)
        assertTrue(drift.probability >= 0.95)
    }

    @Test
    fun theFindingsAreRankedWithTheMostSevereFirstAndNoDuplicates() {
        val findings = Auditor().analyse(input()).findings

        assertTrue(findings.zipWithNext().all { (a, b) -> a.severity >= b.severity })
        assertEquals(findings.size, findings.map { it.dedupKey }.toSet().size)
    }

    @Test
    fun aStatisticalFindingOffersTheRevokeThatWouldFixIt() {
        val baseline = Auditor().analyse(input()).findings.filterIsInstance<Finding.BaselineDeviation>().single()

        assertEquals(SuggestedAction.RevokePermission(pkg, P + "READ_CONTACTS"), baseline.suggestedAction)
    }

    @Test
    fun anAppThatIsNoLongerInstalledProducesNoStatisticalFindings() {
        val result = Auditor().analyse(input(inventory = emptyList()))

        assertTrue(result.findings.none { it !is Finding.PolicyViolation })
    }

    @Test
    fun systemAppsAreNeverJudgedStatistically() {
        val result = Auditor().analyse(input(inventory = listOf(app(2, system = true))))

        assertTrue(result.findings.none { it is Finding.BaselineDeviation || it is Finding.UpdateDrift || it is Finding.SustainedShift })
    }

    @Test
    fun withoutCountingGradeSensingTheStatisticalLayersStaySilentAndTheRulesStillWork() {
        val tooLittle = AuditInput(
            ledgerRows = rows,
            inventory = listOf(app(2)),
            nowMs = dayStart(15) + 12 * HOUR,
            observingSinceMs = null,
            countingSinceMs = null,
            indicatorSinceMs = null,
            versionHistory = history,
        )

        val result = Auditor().analyse(tooLittle)

        assertTrue(result.findings.filterIsInstance<Finding.BaselineDeviation>().isEmpty())
        assertTrue(result.findings.filterIsInstance<Finding.UpdateDrift>().isEmpty())
        assertTrue(result.findings.any { it is Finding.PolicyViolation && it.ruleId == "RBI-01" })
    }

    @Test
    fun theLearningStateIsReportedSoTheUiCanSayStillLearning() {
        val fresh = RowFx.day(3, 5, pkg = pkg, op = Ops.CALL_LOG)

        val result = Auditor().analyse(
            AuditInput(fresh, listOf(app(2)), dayStart(3) + 12 * HOUR, dayStart(0), 0L, dayStart(0), dayStart(0), history)
        )

        assertEquals(1, result.baseline.learningCells)
        assertTrue(DAY > 0)
    }

    @Test
    fun theWholeAnalysisIsDeterministic() {
        val a = Auditor().analyse(input())
        val b = Auditor().analyse(input(rows = rows.shuffled(java.util.Random(9))))

        assertEquals(a.findings.map { it.dedupKey }, b.findings.map { it.dedupKey })
    }

    @Test
    fun theOlderLayerOneOnlyEntryPointStillWorks() {
        val findings = Auditor().audit(rows, listOf(app(2)), dayStart(15), observingSinceMs = null)

        assertTrue(findings.all { it is Finding.PolicyViolation })
    }
}
