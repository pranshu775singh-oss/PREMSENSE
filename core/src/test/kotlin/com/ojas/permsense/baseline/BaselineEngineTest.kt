package com.ojas.permsense.baseline

import com.ojas.permsense.baseline.RowFx.BACKGROUND
import com.ojas.permsense.baseline.RowFx.HOUR
import com.ojas.permsense.baseline.RowFx.SCREEN_OFF
import com.ojas.permsense.baseline.RowFx.VISIBLE
import com.ojas.permsense.baseline.RowFx.day
import com.ojas.permsense.baseline.RowFx.dayStart
import com.ojas.permsense.baseline.RowFx.steady
import com.ojas.permsense.ledger.LedgerRecordKind
import com.ojas.permsense.ledger.Retention
import com.ojas.permsense.model.Ops
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BaselineEngineTest {

    private val engine = BaselineEngine()

    // ------------------------------------------------------------------ the core behaviour

    @Test
    fun aBurstInACellThatHasBeenQuietIsAFinding() {
        // Ten quiet days, then four background reads of contacts in one day.
        val rows = day(10, 4)

        val report = engine.evaluate(RowFx.input(rows, nowDay = 10))

        val f = report.findings.single()
        assertEquals(Ops.CONTACTS, f.op)
        assertEquals(BACKGROUND, f.stratum)
        assertEquals(4L, f.observed)
        assertTrue("p = ${f.pValue}", f.pValue < 0.01)
        assertEquals(4, f.evidence.size)
        assertEquals(dayStart(10), f.dayStartMs)
    }

    @Test
    fun aSingleEpisodeIsNeverADeviation() {
        val report = engine.evaluate(RowFx.input(day(10, 1), nowDay = 10))

        assertTrue(report.findings.isEmpty())
    }

    @Test
    fun anAppThatIsAlwaysBusyIsNotFlaggedForBeingBusy() {
        val rows = steady(from = 0, days = 14, perDay = 6, stratum = VISIBLE) + day(14, 8, stratum = VISIBLE)

        val report = engine.evaluate(RowFx.input(rows, nowDay = 14))

        assertTrue(report.findings.toString(), report.findings.isEmpty())
        assertEquals(1, report.testedCells)
    }

    @Test
    fun theSameCountIsAFindingInAQuietCellButNotInABusyOne() {
        val quiet = steady(0, 10, 0) + day(10, 6)
        val busy = steady(0, 10, 5, op = Ops.CALL_LOG) + day(10, 6, op = Ops.CALL_LOG)

        val report = engine.evaluate(RowFx.input(quiet + busy, nowDay = 10))

        assertEquals(listOf(Ops.CONTACTS), report.findings.map { it.op })
    }

    // ------------------------------------------------------------------ learning

    @Test
    fun aCellWithTooLittleHistoryIsReportedAsLearningNotSilentlyIgnored() {
        // Only three days of counting-grade sensing so far.
        val report = engine.evaluate(RowFx.input(day(3, 5), nowDay = 3))

        assertTrue(report.findings.isEmpty())
        assertEquals(1, report.learningCells)
        assertEquals(setOf(RowFx.PKG), report.learningPackages)
        assertEquals(3, report.historyDays)
    }

    @Test
    fun theLearningPeriodEndsAfterTheConfiguredNumberOfDays() {
        val atSix = engine.evaluate(RowFx.input(day(6, 5), nowDay = 6))
        val atSeven = engine.evaluate(RowFx.input(day(7, 5), nowDay = 7))

        assertTrue(atSix.findings.isEmpty())
        assertEquals(1, atSeven.findings.size)
    }

    @Test
    fun nothingIsEvaluatedUntilCountingGradeSensingHasEverStarted() {
        val report = engine.evaluate(RowFx.input(day(10, 5), nowDay = 10, countingSinceDay = null))

        assertTrue(report.findings.isEmpty())
        assertEquals(0, report.testedCells)
    }

    @Test
    fun theOsHistorySeedsCameraMicAndLocationButNotContacts() {
        // The live collector only started yesterday, but the OS history reaches back a week for
        // indicator ops. Camera can be judged at once; contacts cannot.
        val camera = day(7, 4, op = Ops.CAMERA, stratum = SCREEN_OFF)
        val contacts = day(7, 4, op = Ops.CONTACTS, stratum = SCREEN_OFF)

        val report = engine.evaluate(
            RowFx.input(camera + contacts, nowDay = 7, countingSinceDay = 6, indicatorSinceDay = 0)
        )

        assertEquals(listOf(Ops.CAMERA), report.findings.map { it.op })
        assertEquals(1, report.learningCells)
    }

    // ------------------------------------------------------------------ what counts

    @Test
    fun presenceOnlyRowsFromTheSnapshotPollerDoNotCount() {
        // A burst seen only by T1 is "active at least once per poll", not a count of four.
        val rows = day(10, 4, tier = "T1")

        val report = engine.evaluate(RowFx.input(rows, nowDay = 10))

        assertTrue(report.findings.isEmpty())
    }

    @Test
    fun turningTheLiveCollectorOnAfterAWeekOfPollingIsNotAnAnomaly() {
        // T1 presence rows for ten days (one a day), then T2 starts and sees the real, higher count.
        val polling = (0 until 10).flatMap { day(it, 1, tier = "T1") }
        val live = day(10, 3, tier = "T2")

        val report = engine.evaluate(RowFx.input(polling + live, nowDay = 10, countingSinceDay = 10))

        // Counting-grade sensing began today, so there is no history yet to call this unusual.
        assertTrue(report.findings.isEmpty())
    }

    @Test
    fun rowsFromTheOsHistoryAreCountedLikeLiveRows() {
        val rows = day(10, 4, tier = "T1_PLUS", op = Ops.CAMERA)

        val report = engine.evaluate(RowFx.input(rows, nowDay = 10))

        assertEquals(1, report.findings.size)
    }

    @Test
    fun unknownContextIsNotModelled() {
        val rows = day(10, 5, stratum = "UNKNOWN")

        assertTrue(engine.evaluate(RowFx.input(rows, nowDay = 10)).findings.isEmpty())
    }

    @Test
    fun nonSensitiveOpsAreNotModelled() {
        val rows = day(10, 5, op = "android:wake_lock")

        assertTrue(engine.evaluate(RowFx.input(rows, nowDay = 10)).findings.isEmpty())
    }

    @Test
    fun platformUidsAreNotModelled() {
        val rows = (0 until 5).map { RowFx.row(dayStart(10) + (it + 1) * HOUR, uid = 1000) }

        assertTrue(engine.evaluate(RowFx.input(rows, nowDay = 10)).findings.isEmpty())
    }

    // ------------------------------------------------------------------ blind days

    @Test
    fun daysSpentMostlyBlindAreLeftOutOfTraining() {
        // Ten days of history, six of them with the collector down for the whole day. Only four
        // watched days remain, below the learning minimum, so no verdict is given.
        val gaps = (2 until 8).map { RowFx.gap(dayStart(it.toLong()), dayStart(it + 1L)) }

        val report = engine.evaluate(RowFx.input(gaps + day(10, 5), nowDay = 10))

        assertTrue(report.findings.isEmpty())
        assertEquals(1, report.learningCells)
    }

    @Test
    fun aBriefGapDoesNotRemoveADay() {
        val gap = RowFx.gap(dayStart(3), dayStart(3) + HOUR)

        val report = engine.evaluate(RowFx.input(listOf(gap) + day(10, 5), nowDay = 10))

        assertEquals(1, report.findings.size)
    }

    // ------------------------------------------------------------------ days examined

    @Test
    fun aBurstTwoDaysAgoIsStillFoundWhenTheAppWasNotOpenedSince() {
        val rows = day(10, 5)

        val report = engine.evaluate(RowFx.input(rows, nowDay = 12))

        assertEquals(1, report.findings.size)
        assertEquals(dayStart(10), report.findings.single().dayStartMs)
    }

    @Test
    fun aBurstOlderThanTheExaminedWindowIsNotRaisedAgain() {
        val report = engine.evaluate(RowFx.input(day(10, 5), nowDay = 14))

        assertTrue(report.findings.isEmpty())
    }

    @Test
    fun eachDayIsJudgedAgainstOnlyTheDaysBeforeIt() {
        // A burst on day 9 must not make the same burst on day 10 look ordinary: a day is judged
        // against the past, never the future. Day 10 here is being judged with day 9 in its past.
        val rows = day(9, 5) + day(10, 5)

        val report = engine.evaluate(RowFx.input(rows, nowDay = 10))

        val f = report.findings.single()
        // The most surprising of the examined days is reported, once.
        assertEquals(dayStart(9), f.dayStartMs)
    }

    // ------------------------------------------------------------------ retention

    @Test
    fun anAggregateOfBusyDaysRaisesTheBaselineAndSilencesAFalseAlarm() {
        val old = (0 until 10).map {
            RowFx.row(dayStart(it.toLong()), kind = LedgerRecordKind.AGGREGATE, tier = Retention.AGGREGATE_COUNTING, count = 5)
        }

        val report = engine.evaluate(RowFx.input(old + day(10, 6), nowDay = 10))

        assertTrue(report.findings.isEmpty())
    }

    @Test
    fun presenceGradeAggregatesDoNotCount() {
        val old = (0 until 10).map {
            RowFx.row(dayStart(it.toLong()), kind = LedgerRecordKind.AGGREGATE, tier = Retention.AGGREGATE_PRESENCE, count = 9)
        }

        // Nine "episodes" a day of presence-only history must not be allowed to hide a real burst.
        assertEquals(1, engine.evaluate(RowFx.input(old + day(10, 5), nowDay = 10)).findings.size)
    }

    // ------------------------------------------------------------------ ranking and control

    @Test
    fun theMostSurprisingCellComesFirst() {
        val mild = day(10, 3, op = Ops.CALL_LOG)
        val wild = day(10, 12, op = Ops.CONTACTS)

        val report = engine.evaluate(RowFx.input(mild + wild, nowDay = 10))

        assertEquals(listOf(Ops.CONTACTS, Ops.CALL_LOG), report.findings.map { it.op })
        assertTrue(report.findings[0].pValue < report.findings[1].pValue)
    }

    @Test
    fun falseDiscoveryControlCanTightenTheBar() {
        // One genuinely wild cell and several that clear alpha = 0.01 but only just.
        val wild = day(10, 15, op = Ops.CONTACTS)
        val marginal = listOf(Ops.CALL_LOG, Ops.SMS, Ops.PHONE_STATE, Ops.CLIPBOARD, Ops.CAMERA)
            .flatMap { day(10, 2, op = it) }
        // Plenty of ordinary active cells in the same family of tests.
        val ordinary = (1..30).flatMap { steady(0, 10, 3, pkg = "com.example.app$it", stratum = VISIBLE) + day(10, 3, pkg = "com.example.app$it", stratum = VISIBLE) }

        val loose = BaselineEngine(BaselineConfig()).evaluate(RowFx.input(wild + marginal + ordinary, nowDay = 10))
        val strict = BaselineEngine(BaselineConfig(fdrQ = 0.05)).evaluate(RowFx.input(wild + marginal + ordinary, nowDay = 10))

        assertTrue(strict.findings.size <= loose.findings.size)
        assertTrue(strict.findings.any { it.op == Ops.CONTACTS })
    }

    @Test
    fun theResultDoesNotDependOnTheOrderOfTheRows() {
        val rows = day(10, 4) + day(10, 3, op = Ops.SMS) + steady(0, 10, 2, pkg = "com.example.other", stratum = VISIBLE)

        val a = engine.evaluate(RowFx.input(rows, nowDay = 10))
        val b = engine.evaluate(RowFx.input(rows.shuffled(java.util.Random(5)), nowDay = 10))

        assertEquals(a.findings.map { it.dedupKey to it.pValue }, b.findings.map { it.dedupKey to it.pValue })
    }

    @Test
    fun anEmptyLedgerGivesAnEmptyReport() {
        val report = engine.evaluate(RowFx.input(emptyList(), nowDay = 10))

        assertTrue(report.findings.isEmpty())
        assertEquals(0, report.testedCells)
        assertEquals(0, report.learningCells)
    }

    @Test
    fun theDayIsCutAtLocalMidnightNotUtcMidnight() {
        // In India (UTC+5:30) 20:00 UTC is already the next local day.
        val offset = 5L * HOUR + 30L * 60_000L
        val lateUtc = (0 until 4).map { RowFx.row(dayStart(10) + 20 * HOUR + it * HOUR / 2) }
        val input = RowFx.input(lateUtc, nowDay = 11, nowHour = 6)

        val report = engine.evaluate(input.copy(zoneOffsetMs = offset, countingSinceMs = dayStart(0), indicatorSinceMs = dayStart(0)))

        // All four episodes belong to local day 11 (they are after 18:30 UTC), the day being judged.
        assertEquals(1, report.findings.size)
        assertEquals(4L, report.findings.single().observed)
        assertEquals(dayStart(11) - offset, report.findings.single().dayStartMs)
    }
}
