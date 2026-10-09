package com.ojas.permsense.baseline

import com.ojas.permsense.judgement.isThirdPartyUid
import com.ojas.permsense.ledger.LedgerRecord
import com.ojas.permsense.ledger.LedgerRecordKind
import com.ojas.permsense.ledger.Retention
import com.ojas.permsense.ledger.isCountingGrade
import com.ojas.permsense.model.Ops
import com.ojas.permsense.model.Visibility
import com.ojas.permsense.stats.Prior

/**
 * One cell of the model: an app, an op, and the context the access happened in. Each cell is
 * judged only against its own history, never against a category of apps, so no category lookup
 * (which would need the network) is ever required.
 */
data class CellKey(
    val userId: Int,
    val packageName: String,
    val op: String,
    val stratum: String,
)

internal object Cells {

    /** The contexts a rate is modelled in. UNKNOWN is not a context, it is missing information. */
    private val MODELLED = setOf(
        Visibility.VISIBLE.name,
        Visibility.FOREGROUND_SERVICE.name,
        Visibility.BACKGROUND.name,
        Visibility.SCREEN_OFF.name,
    )

    fun keyOf(row: LedgerRecord) = CellKey(row.userId, row.packageName, row.op, row.visibilityStratum)

    /** Rows that contribute to a count: real counts of sensitive ops by third-party apps. */
    fun eligible(row: LedgerRecord): Boolean =
        row.op in Ops.SENSITIVE &&
                row.visibilityStratum in MODELLED &&
                row.isThirdPartyUid() &&
                row.isCountingGrade() &&
                (row.accessResult == null || row.accessResult == "OBSERVED")

    /** Episodes a row stands for: one, or the stored count when it is a daily aggregate. */
    fun weight(row: LedgerRecord): Int =
        if (row.recordKind == LedgerRecordKind.AGGREGATE.name) row.observationCount else 1

    /**
     * Weak priors. Background and screen-off access is expected to be rare, so the prior sits
     * near zero; this is the contextual-integrity expectation that invisible access is unusual.
     * Access while the app is in use is routine, so its prior is wide and centred higher.
     */
    fun priorFor(stratum: String): Prior =
        when (stratum) {
            Visibility.BACKGROUND.name, Visibility.SCREEN_OFF.name -> Prior(alpha0 = 0.5, beta0 = 1.0)
            else -> Prior(alpha0 = 1.0, beta0 = 0.25)
        }

    /** When counting-grade sensing began for this op, or null if it never has. */
    fun startMs(op: String, countingSinceMs: Long?, indicatorSinceMs: Long?): Long? =
        if (op in Ops.INDICATOR) indicatorSinceMs ?: countingSinceMs else countingSinceMs

    /** Intervals during which the live collector was not watching. */
    class Gaps(rows: List<LedgerRecord>) {

        private val intervals: List<LongArray> =
            rows.filter { it.recordKind == LedgerRecordKind.GAP.name }
                .map { longArrayOf(it.timestampMs, it.timestampMs + (it.durationMs ?: 0L).coerceAtLeast(0L)) }
                .sortedBy { it[0] }

        /** Milliseconds of [startMs, endMs) that fall inside a gap. */
        fun overlapMs(startMs: Long, endMs: Long): Long {
            var total = 0L
            var coveredUntil = startMs
            for (gap in intervals) {
                val from = maxOf(gap[0], coveredUntil)
                val to = minOf(gap[1], endMs)
                if (to > from) {
                    total += to - from
                    coveredUntil = to
                }
            }
            return total
        }

        /** Days of real observation in [startMs, endMs): the span minus the time spent blind. */
        fun exposureDays(startMs: Long, endMs: Long): Double =
            ((endMs - startMs) - overlapMs(startMs, endMs)).coerceAtLeast(0L).toDouble() / Retention.DAY_MS
    }
}
