package com.ojas.permsense.timeline

import com.ojas.permsense.baseline.RowFx
import com.ojas.permsense.baseline.RowFx.DAY
import com.ojas.permsense.baseline.RowFx.HOUR
import com.ojas.permsense.baseline.RowFx.dayStart
import com.ojas.permsense.ledger.LedgerRecordKind
import com.ojas.permsense.model.Ops
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTest {

    private val now = dayStart(20) + 12 * HOUR

    @Test
    fun theChartAlwaysHasExactlyTheDaysAskedForEmptyOnesIncluded() {
        val t = Timeline.build(RowFx.day(19, 2), RowFx.PKG, now, 0L, days = 14)

        assertEquals(14, t.days.size)
        assertEquals(dayStart(7), t.days.first().dayStartMs)
        assertEquals(dayStart(20), t.days.last().dayStartMs)
        assertEquals(13, t.days.count { it.total == 0 })
    }

    @Test
    fun accessesLandOnTheirOwnDayAndStratum() {
        val rows = RowFx.day(18, 3, stratum = RowFx.SCREEN_OFF) + RowFx.day(18, 1, stratum = RowFx.VISIBLE) + RowFx.day(20, 2)

        val t = Timeline.build(rows, RowFx.PKG, now, 0L)

        val d18 = t.days.single { it.dayStartMs == dayStart(18) }
        assertEquals(mapOf("SCREEN_OFF" to 3, "VISIBLE" to 1), d18.byStratum)
        assertEquals(4, d18.total)
        assertEquals(2, t.days.single { it.dayStartMs == dayStart(20) }.total)
    }

    @Test
    fun theUsersLocalMidnightDecidesTheDayNotUtc() {
        val offset = 5L * HOUR + 30L * 60L * 1000L        // India
        // 20:00 UTC on day 10 is 01:30 on day 11 in India.
        val late = RowFx.row(dayStart(10) + 20 * HOUR)

        val t = Timeline.build(listOf(late), RowFx.PKG, dayStart(11) + 12 * HOUR, offset, days = 3)

        assertEquals(listOf(0, 0, 1), t.days.map { it.total })
    }

    @Test
    fun otherAppsAreLeftOut() {
        val rows = RowFx.day(20, 3) + RowFx.day(20, 5, pkg = "com.other")

        val t = Timeline.build(rows, RowFx.PKG, now, 0L)

        assertEquals(3, t.days.last().total)
        assertEquals(3, t.events.size)
    }

    @Test
    fun eventsComeNewestFirstAndAreCapped() {
        val rows = RowFx.steady(10, 10, 4)

        val t = Timeline.build(rows, RowFx.PKG, now, 0L, maxEvents = 15)

        assertEquals(15, t.events.size)
        assertTrue(t.events.zipWithNext().all { (a, b) -> a.timestampMs >= b.timestampMs })
        assertEquals(rows.maxOf { it.timestampMs }, t.events.first().timestampMs)
    }

    @Test
    fun olderHistoryIsNoticedButNotDrawn() {
        val old = RowFx.row(dayStart(2) + HOUR)

        val t = Timeline.build(listOf(old), RowFx.PKG, now, 0L, days = 14)

        assertTrue(t.hasOlderHistory)
        assertEquals(0, t.days.sumOf { it.total })
    }

    @Test
    fun anAggregateMeansOlderHistoryExists() {
        val aggregate = RowFx.row(dayStart(1), kind = LedgerRecordKind.AGGREGATE, count = 12)

        assertTrue(Timeline.build(listOf(aggregate), RowFx.PKG, now, 0L).hasOlderHistory)
    }

    @Test
    fun nothingRecordedIsAnEmptyTimelineNotAnError() {
        val t = Timeline.build(emptyList(), RowFx.PKG, now, 0L)

        assertEquals(14, t.days.size)
        assertTrue(t.events.isEmpty())
        assertFalse(t.hasOlderHistory)
    }

    @Test
    fun aClockThatJumpedForwardNeverDrawsOutsideTheChart() {
        val future = RowFx.row(now + 30 * DAY)

        val t = Timeline.build(listOf(future), RowFx.PKG, now, 0L)

        assertEquals(0, t.days.sumOf { it.total })
    }

    @Test
    fun aChangeMadeThroughPermSenseShowsOnceItsResultNotItsIntent() {
        val base = RowFx.row(dayStart(20) + HOUR, op = Ops.CONTACTS)
        val intent = base.copy(recordKind = LedgerRecordKind.REMEDIATION.name, accessKind = "REVOKE_PERMISSION", attributionTag = "phase=INTENT;prior=GRANTED;new=PENDING", accessResult = "PENDING")
        val result = base.copy(timestampMs = base.timestampMs + 1, recordKind = LedgerRecordKind.REMEDIATION.name, accessKind = "REVOKE_PERMISSION", attributionTag = "phase=RESULT;prior=GRANTED;new=REVOKED", accessResult = "SUCCESS")

        val t = Timeline.build(listOf(intent, result), RowFx.PKG, now, 0L)

        val change = t.events.single()
        assertEquals(TimelineEvent.Kind.CHANGE, change.kind)
        assertEquals("REVOKE_PERMISSION:SUCCESS", change.detail)
    }

    @Test
    fun gapsAreNotPartOfAnAppsHistory() {
        val t = Timeline.build(listOf(RowFx.gap(now - HOUR, now)), "android.framework", now, 0L)

        assertTrue(t.events.isEmpty())
        assertNull(t.events.firstOrNull())
    }
}
