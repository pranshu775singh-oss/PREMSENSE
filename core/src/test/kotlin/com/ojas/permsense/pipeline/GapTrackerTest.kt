package com.ojas.permsense.pipeline

import com.ojas.permsense.pipeline.CollectorObservation.Running
import com.ojas.permsense.pipeline.CollectorObservation.Unreachable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GapTrackerTest {

    private fun step(prev: CollectorState?, obs: CollectorObservation) = GapTracker.update(prev, obs)

    @Test
    fun theFirstSightingOfTheCollectorRecordsNoGap() {
        val s = step(null, Running(startedAtMs = 1_000, droppedEvents = 0, nowMs = 5_000))

        assertTrue(s.gaps.isEmpty())
        assertEquals(CollectorState(5_000, 1_000, 0), s.state)
    }

    @Test
    fun aCollectorThatNeverComesUpRecordsNothing() {
        val s = step(null, Unreachable(10_000))

        assertNull(s.state)
        assertTrue(s.gaps.isEmpty())
    }

    @Test
    fun aCollectorThatKeepsRunningHasNoGapAndTheSightingMovesForward() {
        val first = step(null, Running(1_000, 0, 5_000)).state
        val second = step(first, Running(1_000, 0, 65_000))

        assertTrue(second.gaps.isEmpty())
        assertEquals(65_000, second.state!!.lastAliveMs)
        assertEquals(1_000, second.state!!.sessionStartedAtMs)
    }

    @Test
    fun aRestartIsBlindnessFromTheLastSightingUntilTheNewRunBegan() {
        val before = CollectorState(lastAliveMs = 100_000, sessionStartedAtMs = 1_000, droppedEvents = 0)

        val s = step(before, Running(startedAtMs = 400_000, droppedEvents = 0, nowMs = 410_000))

        assertEquals(listOf(CollectorGap(100_000, 400_000, GapTracker.RESTARTED)), s.gaps)
        assertEquals(CollectorState(410_000, 400_000, 0), s.state)
    }

    @Test
    fun beingUnreachableRecordsNothingYetButRemembersWhenItWasLastSeen() {
        val before = CollectorState(100_000, 1_000, 0)

        val down = step(before, Unreachable(200_000))

        assertTrue(down.gaps.isEmpty())
        assertEquals(before, down.state)

        // When it is next seen, running as a new run, the whole stretch is a gap.
        val back = step(down.state, Running(startedAtMs = 900_000, droppedEvents = 0, nowMs = 905_000))
        assertEquals(listOf(CollectorGap(100_000, 900_000, GapTracker.RESTARTED)), back.gaps)
    }

    @Test
    fun anOverflowedBufferIsAGapEvenWithoutARestart() {
        val before = CollectorState(100_000, 1_000, droppedEvents = 5)

        val s = step(before, Running(1_000, droppedEvents = 40, nowMs = 160_000))

        assertEquals(listOf(CollectorGap(100_000, 160_000, GapTracker.OVERFLOW)), s.gaps)
    }

    @Test
    fun anUnchangedDropCountIsNotAGap() {
        val before = CollectorState(100_000, 1_000, droppedEvents = 5)

        assertTrue(step(before, Running(1_000, 5, 160_000)).gaps.isEmpty())
    }

    @Test
    fun aClockThatRanBackwardsNeverMakesANegativeGap() {
        val before = CollectorState(500_000, 1_000, 0)

        val s = step(before, Running(startedAtMs = 300_000, droppedEvents = 0, nowMs = 510_000))

        val gap = s.gaps.single()
        assertTrue(gap.endMs >= gap.startMs)
    }

    @Test
    fun anOutageInProgressIsAnOpenGapFromTheLastSighting() {
        val before = CollectorState(lastAliveMs = 100_000, sessionStartedAtMs = 1_000, droppedEvents = 0)

        val gap = GapTracker.openGap(before, Unreachable(100_000 + 3_600_000))

        assertEquals(CollectorGap(100_000, 100_000 + 3_600_000, GapTracker.UNREACHABLE), gap)
    }

    @Test
    fun aShortSilenceIsJustTheTimeBetweenTwoChecks() {
        val before = CollectorState(100_000, 1_000, 0)

        assertNull(GapTracker.openGap(before, Unreachable(100_000 + GapTracker.OPEN_GAP_MIN_MS - 1)))
    }

    @Test
    fun aCollectorThatWasNeverSeenHasNoOpenGapAndARunningOneHasNone() {
        assertNull(GapTracker.openGap(null, Unreachable(10_000_000)))
        assertNull(GapTracker.openGap(CollectorState(100_000, 1_000, 0), Running(1_000, 0, 10_000_000)))
    }

    @Test
    fun openingAGapStoresNothingAndTheNextSightingStillClosesTheWholeStretch() {
        val before = CollectorState(100_000, 1_000, 0)

        // The open gap is only advice for the analysis. The stored state is untouched...
        assertEquals(before, step(before, Unreachable(5_000_000)).state)

        // ...so when the collector comes back, the full outage is recorded exactly once.
        val back = step(before, Running(startedAtMs = 5_000_000, droppedEvents = 0, nowMs = 5_010_000))
        assertEquals(listOf(CollectorGap(100_000, 5_000_000, GapTracker.RESTARTED)), back.gaps)
    }

    @Test
    fun severalRestartsInARowEachGetTheirOwnGap() {
        var state: CollectorState? = null
        val gaps = ArrayList<CollectorGap>()

        for (start in listOf(1_000L, 500_000L, 1_000_000L)) {
            val s = step(state, Running(start, 0, start + 10_000))
            state = s.state
            gaps += s.gaps
        }

        assertEquals(2, gaps.size)
        assertEquals(listOf(11_000L, 510_000L), gaps.map { it.startMs })
        assertEquals(listOf(500_000L, 1_000_000L), gaps.map { it.endMs })
    }
}
