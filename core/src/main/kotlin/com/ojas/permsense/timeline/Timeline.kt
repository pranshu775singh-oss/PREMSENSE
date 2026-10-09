package com.ojas.permsense.timeline

import com.ojas.permsense.ledger.LedgerRecord
import com.ojas.permsense.ledger.LedgerRecordKind
import com.ojas.permsense.ledger.Retention

/** How many recorded accesses fell on one local day, split by what the phone was doing at the time. */
data class DayBucket(
    val dayStartMs: Long,
    val byStratum: Map<String, Int>,
) {
    val total: Int get() = byStratum.values.sum()
}

/** One line of an app's history. */
data class TimelineEvent(
    val timestampMs: Long,
    val kind: Kind,
    val op: String,
    val stratum: String,
    /** Accesses grouped into this entry (an episode merges accesses less than a minute apart). */
    val count: Int,
    /** For a change PermSense made: what it did and how it ended. */
    val detail: String? = null,
) {
    enum class Kind { ACCESS, CHANGE }
}

data class AppTimeline(
    /** Oldest day first; always exactly the number of days asked for, empty days included. */
    val days: List<DayBucket>,
    /** Newest first. */
    val events: List<TimelineEvent>,
    /** Whether any recorded access falls before the first day shown. */
    val hasOlderHistory: Boolean,
)

/**
 * One app's history from the ledger: a per-day chart and a list of events. Pure, so what the screen
 * draws is what the unit tests check.
 */
object Timeline {

    fun build(
        rows: List<LedgerRecord>,
        packageName: String,
        nowMs: Long,
        zoneOffsetMs: Long,
        days: Int = 14,
        maxEvents: Int = 200,
    ): AppTimeline {

        require(days in 1..366)

        val today = Retention.dayIndex(nowMs, zoneOffsetMs)
        val first = today - (days - 1)

        val mine = rows.filter { it.packageName == packageName }

        val accesses = mine.filter {
            it.recordKind == LedgerRecordKind.EPISODE.name || it.recordKind == LedgerRecordKind.BACKFILL.name
        }

        val perDay = HashMap<Long, MutableMap<String, Int>>()
        var older = false

        for (row in accesses) {
            val day = Retention.dayIndex(row.timestampMs, zoneOffsetMs)
            when {
                day < first -> older = true
                day > today -> Unit      // a clock that jumped forward; not drawn
                else -> perDay.getOrPut(day) { HashMap() }.merge(row.visibilityStratum, 1, Int::plus)
            }
        }

        if (!older) older = mine.any { it.recordKind == LedgerRecordKind.AGGREGATE.name }

        val buckets = (first..today).map { day ->
            DayBucket(
                dayStartMs = Retention.dayStartMs(day, zoneOffsetMs),
                byStratum = perDay[day]?.toSortedMap() ?: emptyMap(),
            )
        }

        val events = mine.mapNotNull { row ->
            when (row.recordKind) {
                LedgerRecordKind.EPISODE.name, LedgerRecordKind.BACKFILL.name -> TimelineEvent(
                    timestampMs = row.timestampMs,
                    kind = TimelineEvent.Kind.ACCESS,
                    op = row.op,
                    stratum = row.visibilityStratum,
                    count = row.observationCount.coerceAtLeast(1),
                )
                // The change is recorded twice (intent, then result); the result is what happened.
                LedgerRecordKind.REMEDIATION.name ->
                    if (row.attributionTag.orEmpty().contains("phase=RESULT")) {
                        TimelineEvent(
                            timestampMs = row.timestampMs,
                            kind = TimelineEvent.Kind.CHANGE,
                            op = row.op,
                            stratum = row.visibilityStratum,
                            count = 1,
                            detail = row.accessKind + ":" + (row.accessResult ?: ""),
                        )
                    } else null
                else -> null
            }
        }
            .sortedByDescending { it.timestampMs }
            .take(maxEvents)

        return AppTimeline(buckets, events, older)
    }
}
