package com.ojas.permsense.baseline

import com.ojas.permsense.ledger.LedgerRecord
import com.ojas.permsense.ledger.LedgerRecordKind
import com.ojas.permsense.ledger.Retention
import com.ojas.permsense.model.Ops

/** Builds ledger rows directly, so a scenario of weeks of activity is a few readable lines. */
object RowFx {

    const val DAY = Retention.DAY_MS
    const val HOUR = 60L * 60L * 1000L

    /** Day 0 of every scenario. Any day will do; this one is a Monday in 2023. */
    const val DAY0 = 19_700L

    const val PKG = "com.example.app"
    const val UID = 10100
    const val BACKGROUND = "BACKGROUND"
    const val SCREEN_OFF = "SCREEN_OFF"
    const val VISIBLE = "VISIBLE"

    private var serial = 0L

    fun dayStart(day: Long): Long = (DAY0 + day) * DAY

    fun row(
        timestampMs: Long,
        pkg: String = PKG,
        op: String = Ops.CONTACTS,
        stratum: String = BACKGROUND,
        tier: String = "T2",
        version: Long = 1L,
        uid: Int = UID,
        kind: LedgerRecordKind = LedgerRecordKind.EPISODE,
        count: Int = 1,
    ): LedgerRecord {
        serial++
        return LedgerRecord(
            timestampMs = timestampMs,
            uid = uid,
            packageName = pkg,
            versionCode = version,
            op = op,
            accessKind = "noted",
            durationMs = null,
            attributionTag = null,
            flags = 0,
            visibilityStratum = stratum,
            screenState = "SCREEN_ON",
            sourceTier = tier,
            observationCount = count,
            accessResult = "OBSERVED",
            sourceEventIds = listOf("id-$serial"),
            recordKind = kind.name,
            prevHash = "p$serial",
            hash = "h%08d".format(serial),
        )
    }

    /** [count] episodes spread across scenario day [day], an hour or more apart. */
    fun day(
        day: Int,
        count: Int,
        pkg: String = PKG,
        op: String = Ops.CONTACTS,
        stratum: String = BACKGROUND,
        tier: String = "T2",
        version: Long = 1L,
    ): List<LedgerRecord> =
        (0 until count).map { i ->
            row(dayStart(day.toLong()) + (1 + i) * HOUR, pkg, op, stratum, tier, version)
        }

    /** The same daily count for each of [days] days starting at [from]. */
    fun steady(
        from: Int,
        days: Int,
        perDay: Int,
        pkg: String = PKG,
        op: String = Ops.CONTACTS,
        stratum: String = BACKGROUND,
        version: Long = 1L,
    ): List<LedgerRecord> =
        (from until from + days).flatMap { day(it, perDay, pkg, op, stratum, "T2", version) }

    fun gap(startMs: Long, endMs: Long): LedgerRecord {
        serial++
        return LedgerRecord(
            timestampMs = startMs,
            uid = 1000,
            packageName = "android.framework",
            versionCode = 0L,
            op = "system:collector_gap",
            accessKind = "SHIZUKU_DISCONNECTED",
            durationMs = endMs - startMs,
            attributionTag = null,
            flags = 0,
            visibilityStratum = "UNKNOWN",
            screenState = "UNKNOWN",
            sourceTier = "T2",
            observationCount = 1,
            accessResult = "GAP",
            sourceEventIds = listOf("gap-$serial"),
            recordKind = LedgerRecordKind.GAP.name,
            prevHash = "p$serial",
            hash = "h%08d".format(serial),
        )
    }

    fun input(
        rows: List<LedgerRecord>,
        nowDay: Int,
        nowHour: Int = 12,
        countingSinceDay: Int? = 0,
        indicatorSinceDay: Int? = countingSinceDay,
    ) = BaselineInput(
        rows = rows,
        nowMs = dayStart(nowDay.toLong()) + nowHour * HOUR,
        zoneOffsetMs = 0L,
        countingSinceMs = countingSinceDay?.let { dayStart(it.toLong()) },
        indicatorSinceMs = indicatorSinceDay?.let { dayStart(it.toLong()) },
    )
}
