package com.ojas.permsense.baseline

import com.ojas.permsense.baseline.RowFx.DAY
import com.ojas.permsense.baseline.RowFx.HOUR
import com.ojas.permsense.baseline.RowFx.PKG
import com.ojas.permsense.baseline.RowFx.dayStart
import com.ojas.permsense.baseline.RowFx.day
import com.ojas.permsense.baseline.RowFx.steady
import com.ojas.permsense.model.AppVersionPoint
import com.ojas.permsense.model.Ops
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriftEngineTest {

    private val engine = DriftEngine()

    private fun versions(vararg points: Pair<Long, Int>) =
        mapOf(PKG to points.map { (v, d) -> AppVersionPoint(v, dayStart(d.toLong())) })

    private fun input(
        rows: List<com.ojas.permsense.ledger.LedgerRecord>,
        versions: Map<String, List<AppVersionPoint>>,
        nowDay: Int,
        nowHour: Int = 12,
    ) = DriftInput(
        rows = rows,
        versionHistory = versions,
        nowMs = dayStart(nowDay.toLong()) + nowHour * HOUR,
        zoneOffsetMs = 0L,
        countingSinceMs = dayStart(0),
        indicatorSinceMs = dayStart(0),
    )

    // v1 for days 0..13, v2 from day 14.
    private val twoVersions = versions(1L to 0, 2L to 14)

    @Test
    fun aRateThatTriplesAfterAnUpdateIsFound() {
        val before = steady(0, 14, 1, version = 1)
        val after = day(14, 8, version = 2) + day(15, 8, version = 2)

        val found = engine.evaluate(input(before + after, twoVersions, nowDay = 15)).single()

        assertEquals(1L, found.fromVersion)
        assertEquals(2L, found.toVersion)
        assertEquals(Ops.CONTACTS, found.op)
        assertTrue("probability ${found.probability}", found.probability >= 0.95)
        assertTrue(found.ratePerDayAfter > 3 * found.ratePerDayBefore)
        assertEquals(3.0, found.factor, 0.0)
    }

    @Test
    fun anUnchangedRateAfterAnUpdateIsNotAFinding() {
        val rows = steady(0, 14, 1, version = 1) + steady(14, 4, 1, version = 2)

        assertTrue(engine.evaluate(input(rows, twoVersions, nowDay = 17)).isEmpty())
    }

    @Test
    fun aSmallRiseThatIsNotThreefoldIsNotAFinding() {
        val rows = steady(0, 14, 4, version = 1) + steady(14, 3, 6, version = 2)

        assertTrue(engine.evaluate(input(rows, twoVersions, nowDay = 16)).isEmpty())
    }

    @Test
    fun tooFewEpisodesAfterTheUpdateIsNotEnoughToCall() {
        val rows = steady(0, 14, 0, version = 1) + day(14, 2, version = 2)

        assertTrue(engine.evaluate(input(rows, twoVersions, nowDay = 15)).isEmpty())
    }

    @Test
    fun anUpdateWithTooLittleHistoryBeforeItIsNotJudged() {
        // Update two days after sensing began: the "before" is too thin to compare with.
        val rows = day(0, 1) + day(1, 1) + day(2, 9, version = 2) + day(3, 9, version = 2)

        assertTrue(engine.evaluate(input(rows, versions(1L to 0, 2L to 2), nowDay = 3)).isEmpty())
    }

    @Test
    fun anOldUpdateIsHistoryNotNews() {
        val rows = steady(0, 14, 1, version = 1) + steady(14, 3, 10, version = 2)

        // 60 days later the update is outside the 30 day window.
        assertTrue(engine.evaluate(input(rows, twoVersions, nowDay = 75)).isEmpty())
    }

    @Test
    fun timeSpentBlindBeforeTheUpdateIsNotCountedAsQuiet() {
        // The collector was down for the whole fortnight before the update, so there is no real
        // "before" to compare against, only silence from a source that could not see.
        val gap = RowFx.gap(dayStart(0), dayStart(14))
        val rows = listOf(gap) + steady(14, 2, 8, version = 2)

        assertTrue(engine.evaluate(input(rows, twoVersions, nowDay = 15)).isEmpty())
    }

    @Test
    fun presenceOnlyRowsDoNotDriveDrift() {
        val before = (0 until 14).flatMap { day(it, 1, tier = "T1", version = 1) }
        val after = day(14, 10, tier = "T1", version = 2) + day(15, 10, tier = "T1", version = 2)

        assertTrue(engine.evaluate(input(before + after, twoVersions, nowDay = 15)).isEmpty())
    }

    @Test
    fun eachUpdateIsJudgedOnItsOwnWindow() {
        val rows = steady(0, 14, 1, version = 1) +
                steady(14, 6, 9, version = 2) +   // v2 is the noisy one
                steady(20, 3, 1, version = 3)     // v3 settles back down
        val history = versions(1L to 0, 2L to 14, 3L to 20)

        val found = engine.evaluate(input(rows, history, nowDay = 22))

        assertEquals(listOf(1L to 2L), found.map { it.fromVersion to it.toVersion })
    }

    @Test
    fun anAppWithOneVersionHasNoUpdateToJudge() {
        val rows = steady(0, 14, 1) + steady(14, 3, 20)

        assertTrue(engine.evaluate(input(rows, versions(1L to 0), nowDay = 16)).isEmpty())
    }

    @Test
    fun theAnswerDoesNotDependOnRowOrderAndIsRepeatable() {
        val rows = steady(0, 14, 1, version = 1) + day(14, 8, version = 2) + day(15, 8, version = 2)

        val a = engine.evaluate(input(rows, twoVersions, nowDay = 15))
        val b = engine.evaluate(input(rows.shuffled(java.util.Random(3)), twoVersions, nowDay = 15))
        val c = engine.evaluate(input(rows, twoVersions, nowDay = 15))

        assertEquals(a.map { it.probability }, b.map { it.probability })
        assertEquals(a.map { it.probability }, c.map { it.probability })
    }

    @Test
    fun evidenceIsBoundedToTheLatestEpisodes() {
        val rows = steady(0, 14, 1, version = 1) + steady(14, 3, 20, version = 2)

        val found = engine.evaluate(input(rows, twoVersions, nowDay = 16)).single()

        assertEquals(20, found.evidence.size)
        assertTrue(found.evidence.all { it.versionCode == 2L })
    }

    @Test
    fun aBackgroundShiftIsReportedPerContextNotJustPerOp() {
        val visibleSteady = steady(0, 14, 5, stratum = RowFx.VISIBLE, version = 1) +
                steady(14, 3, 5, stratum = RowFx.VISIBLE, version = 2)
        val backgroundWakes = steady(0, 14, 0, version = 1) + steady(14, 3, 6, stratum = RowFx.BACKGROUND, version = 2)

        val found = engine.evaluate(input(visibleSteady + backgroundWakes, twoVersions, nowDay = 16))

        assertEquals(listOf(RowFx.BACKGROUND), found.map { it.stratum })
    }

    @Test
    fun theEngineNeverReportsAnythingForUnrecordedVersionHistory() {
        val rows = steady(0, 14, 1) + steady(14, 3, 20)

        assertTrue(engine.evaluate(input(rows, emptyMap(), nowDay = 16)).isEmpty())
    }
}

class ShiftEngineTest {

    private val engine = ShiftEngine()

    @Test
    fun aRateThatStaysTripledForDaysIsFound() {
        val rows = steady(0, 20, 1) + steady(20, 10, 6)

        val found = engine.evaluate(RowFx.input(rows, nowDay = 30, nowHour = 6)).single()

        assertEquals(Ops.CONTACTS, found.op)
        assertEquals(RowFx.BACKGROUND, found.stratum)
        assertTrue("before ${found.ratePerDayBefore}", found.ratePerDayBefore in 0.5..1.5)
        assertTrue("recent ${found.ratePerDayRecent}", found.ratePerDayRecent > 4.0)
        assertTrue(found.daysRaised in 1..10)
        // The excursion began when the rate rose, not before.
        assertTrue(found.sinceMs >= dayStart(20) - DAY && found.sinceMs <= dayStart(23))
    }

    @Test
    fun aSteadyRateIsNeverAFinding() {
        // Counts wander between 0 and 3 around a mean of 1.5.
        val pattern = listOf(1, 2, 0, 3, 1, 2, 1, 0, 2, 3, 1, 1, 2, 0, 1, 2, 3, 1, 0, 2)
        val rows = (0 until 40).flatMap { day(it, pattern[it % pattern.size]) }

        assertTrue(engine.evaluate(RowFx.input(rows, nowDay = 40)).isEmpty())
    }

    @Test
    fun aShiftThatHasSubsidedIsNotReportedAsOngoing() {
        // The alarm statistic stays high for a while after the rate comes back down. A finding
        // saying the rate is raised now would be wrong.
        val rows = steady(0, 20, 1) + steady(20, 10, 6) + steady(30, 6, 1)

        assertTrue(engine.evaluate(RowFx.input(rows, nowDay = 36, nowHour = 6)).isEmpty())
    }

    @Test
    fun aBriefSpikeIsNotASustainedShift() {
        val rows = steady(0, 20, 1) + day(20, 7) + steady(21, 9, 1)

        assertTrue(engine.evaluate(RowFx.input(rows, nowDay = 30, nowHour = 6)).isEmpty())
    }

    @Test
    fun aCellWithLessThanTwoWeeksOfHistoryIsNotWatched() {
        val rows = steady(0, 6, 1) + steady(6, 6, 8)

        assertTrue(engine.evaluate(RowFx.input(rows, nowDay = 12, nowHour = 6)).isEmpty())
    }

    @Test
    fun aCompletelySilentCellThatWakesUpIsFound() {
        // Never used for three weeks, then one or more episodes a day for a week.
        val rows = steady(0, 1, 0) + steady(21, 8, 3)

        val found = engine.evaluate(RowFx.input(rows, nowDay = 29, nowHour = 6))

        assertEquals(1, found.size)
    }

    @Test
    fun daysSpentBlindAreSkippedNotTreatedAsQuiet() {
        // A long outage in the middle must not make the recovered, ordinary rate look like a rise.
        val outage = RowFx.gap(dayStart(10), dayStart(20))
        val rows = listOf(outage) + steady(0, 10, 1) + steady(20, 12, 1)

        assertTrue(engine.evaluate(RowFx.input(rows, nowDay = 32, nowHour = 6)).isEmpty())
    }

    @Test
    fun presenceOnlyRowsAreIgnored() {
        val rows = (0 until 20).flatMap { day(it, 1, tier = "T1") } + (20 until 30).flatMap { day(it, 6, tier = "T1") }

        assertTrue(engine.evaluate(RowFx.input(rows, nowDay = 30, nowHour = 6)).isEmpty())
    }

    @Test
    fun theTodaysPartialDayIsNotPartOfTheSeries() {
        // Only complete days feed the statistic; a busy morning today must not trigger it alone.
        val rows = steady(0, 24, 1) + day(24, 12)

        assertTrue(engine.evaluate(RowFx.input(rows, nowDay = 24, nowHour = 9)).isEmpty())
    }
}
