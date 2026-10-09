package com.ojas.permsense.ledger

/**
 * Retention policy: raw events are kept for [RETENTION_DAYS], then collapsed to daily aggregates.
 *
 * Aggregates keep what the statistics need (how many episodes, per app, op, stratum, day and app
 * version) and drop what only a surveillance record would need (exact times, source ids, flags).
 */
object Retention {

    const val RETENTION_DAYS = 90

    const val DAY_MS = 24L * 60L * 60L * 1000L

    /** Aggregates of counting-grade rows, and of rows that only show an app was active. */
    const val AGGREGATE_COUNTING = "AGG:CNT"
    const val AGGREGATE_PRESENCE = "AGG:PRES"

    /** Index of the local day containing [timestampMs], for a fixed UTC offset. */
    fun dayIndex(timestampMs: Long, zoneOffsetMs: Long): Long =
        Math.floorDiv(timestampMs + zoneOffsetMs, DAY_MS)

    /** UTC milliseconds at which local day [dayIndex] starts. */
    fun dayStartMs(dayIndex: Long, zoneOffsetMs: Long): Long =
        dayIndex * DAY_MS - zoneOffsetMs

    /**
     * Rows older than this are outside retention. Aligned to the start of a local day so a day is
     * never split between raw rows and an aggregate. Ingestion and pruning both use this value, so
     * an event that would be pruned at once is never admitted in the first place.
     */
    fun cutoffFor(nowMs: Long, zoneOffsetMs: Long): Long =
        dayStartMs(dayIndex(nowMs - RETENTION_DAYS * DAY_MS, zoneOffsetMs), zoneOffsetMs)

    /**
     * Collapses [pruned] rows into AGGREGATE records, not yet chained (prevHash and hash empty).
     * Episodes and backfill count once each. Existing aggregates contribute their own count, so
     * collapsing twice never loses or invents a day. Gap and remediation rows are dropped.
     */
    fun aggregate(
        pruned: List<LedgerRecord>,
        zoneOffsetMs: Long,
        boundaryHash: String,
    ): List<LedgerRecord> {

        data class Key(
            val uid: Int,
            val packageName: String,
            val op: String,
            val stratum: String,
            val day: Long,
            val versionCode: Long,
            /** Whether the counts are real counts (live collector, OS history) or only presence. */
            val counting: Boolean,
        )

        val counts = java.util.TreeMap<String, Pair<Key, Int>>()

        for (row in pruned) {

            val weight = when (row.recordKind) {
                LedgerRecordKind.EPISODE.name, LedgerRecordKind.BACKFILL.name -> 1
                LedgerRecordKind.AGGREGATE.name -> row.observationCount
                else -> continue
            }

            val key = Key(
                uid = row.uid,
                packageName = row.packageName,
                op = row.op,
                stratum = row.visibilityStratum,
                day = dayIndex(row.timestampMs, zoneOffsetMs),
                versionCode = row.versionCode,
                counting = row.isCountingGrade(),
            )

            // A sortable text key makes the output order independent of input order.
            val sortKey = "%020d|%d|%s|%s|%s|%d|%b".format(
                key.day, key.uid, key.packageName, key.op, key.stratum, key.versionCode, key.counting,
            )

            val existing = counts[sortKey]
            counts[sortKey] = key to ((existing?.second ?: 0) + weight)
        }

        return counts.values.map { (key, count) ->
            LedgerRecord(
                timestampMs = dayStartMs(key.day, zoneOffsetMs),
                uid = key.uid,
                packageName = key.packageName,
                versionCode = key.versionCode,
                op = key.op,
                accessKind = "aggregate",
                durationMs = null,
                attributionTag = null,
                flags = 0,
                visibilityStratum = key.stratum,
                screenState = "AGGREGATE",
                sourceTier = if (key.counting) AGGREGATE_COUNTING else AGGREGATE_PRESENCE,
                observationCount = count,
                accessResult = "OBSERVED",
                sourceEventIds = listOf(
                    "agg:${key.day}:" + sha256Hex(
                        "$boundaryHash|${key.uid}|${key.packageName}|${key.op}|${key.stratum}|${key.versionCode}|${key.counting}"
                            .toByteArray(Charsets.UTF_8),
                    ).take(24),
                ),
                recordKind = LedgerRecordKind.AGGREGATE.name,
                prevHash = "",
            )
        }
    }
}

/**
 * True when the row's episode count can be trusted as a count. The live collector (T2) and the OS
 * history (T1+) see individual accesses. A T1 snapshot only shows that an access happened since
 * the last poll, so a statistic built on counts must not mix it in: turning the live collector on
 * would look like a sudden rise in activity.
 */
fun LedgerRecord.isCountingGrade(): Boolean =
    when (recordKind) {
        LedgerRecordKind.AGGREGATE.name -> sourceTier == Retention.AGGREGATE_COUNTING
        LedgerRecordKind.EPISODE.name, LedgerRecordKind.BACKFILL.name ->
            sourceTier.split(',').any { it == "T2" || it == "T1_PLUS" || it == "T3" }
        else -> false
    }
