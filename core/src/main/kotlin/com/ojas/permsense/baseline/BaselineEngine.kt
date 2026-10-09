package com.ojas.permsense.baseline

import com.ojas.permsense.findings.Finding
import com.ojas.permsense.ledger.LedgerRecord
import com.ojas.permsense.ledger.LedgerRecordKind
import com.ojas.permsense.ledger.Retention
import com.ojas.permsense.stats.CellCounts
import com.ojas.permsense.stats.FalseDiscoveryRate
import com.ojas.permsense.stats.GammaPoisson

data class BaselineConfig(
    /** Days of history a cell needs before it can raise anything. Shown to the user as "learning". */
    val minLearningDays: Int = 7,
    /** A day is a candidate when its upper-tail probability is below this. */
    val alpha: Double = 0.01,
    /** Fewer episodes than this on a day is never a deviation, however quiet the cell was. */
    val minEpisodes: Long = 2L,
    /** Most recent days to examine, so a deviation is not missed if the app was not opened. */
    val evaluateDays: Int = 3,
    /** A day with more than this fraction spent blind (collector down) is left out of training. */
    val maxGapFractionPerDay: Double = 0.25,
    /** When set, Benjamini-Hochberg control at this false discovery rate across the day's tests. */
    val fdrQ: Double? = null,
)

data class BaselineInput(
    val rows: List<LedgerRecord>,
    val nowMs: Long,
    val zoneOffsetMs: Long,
    /** When counting-grade sensing (the live collector) began, or null if it never has. */
    val countingSinceMs: Long?,
    /** Same for camera, microphone and location, which the OS history can seed earlier. */
    val indicatorSinceMs: Long?,
)

data class BaselineReport(
    val findings: List<Finding.BaselineDeviation>,
    /** Cells that had enough history and were tested. */
    val testedCells: Int,
    /** Cells that had unusual activity but too little history to judge. Never silently dropped. */
    val learningCells: Int,
    val learningPackages: Set<String>,
    /** Whole days of counting-grade sensing so far, to say how long the learning period has run. */
    val historyDays: Int,
)

/**
 * Layer 2. For every (app, op, context) cell, model the number of episodes per day as Poisson with
 * a Gamma prior on the rate and ask: given this app's own past, how surprising is today's count?
 *
 * The posterior predictive is negative binomial, so there is no small-lambda blow-up of the
 * z-score approach, and only two numbers per cell are needed: total episodes and days observed.
 * Counting-grade rows only; a T1 snapshot can say "at least once", not "how many".
 */
class BaselineEngine(private val config: BaselineConfig = BaselineConfig()) {

    private class Candidate(
        val cell: CellKey,
        val day: Long,
        val observed: Long,
        val expectedPerDay: Double,
        val p: Double,
    )

    fun evaluate(input: BaselineInput): BaselineReport {

        val today = Retention.dayIndex(input.nowMs, input.zoneOffsetMs)
        val gaps = Cells.Gaps(input.rows)

        val countsByCell = HashMap<CellKey, HashMap<Long, Int>>()
        for (row in input.rows) {
            if (!Cells.eligible(row)) continue
            val day = Retention.dayIndex(row.timestampMs, input.zoneOffsetMs)
            countsByCell
                .getOrPut(Cells.keyOf(row)) { HashMap() }
                .merge(day, Cells.weight(row), Int::plus)
        }

        val badDays = HashMap<Long, Boolean>()
        fun isBlindDay(day: Long): Boolean =
            badDays.getOrPut(day) {
                val start = Retention.dayStartMs(day, input.zoneOffsetMs)
                gaps.overlapMs(start, start + Retention.DAY_MS) > config.maxGapFractionPerDay * Retention.DAY_MS
            }

        val allP = ArrayList<Double>()
        val testedCells = HashSet<CellKey>()
        val candidates = ArrayList<Candidate>()
        val learningPackages = LinkedHashSet<String>()
        var learningCells = 0

        for ((cell, byDay) in countsByCell.entries.sortedBy { it.key.toString() }) {

            val sinceMs = Cells.startMs(cell.op, input.countingSinceMs, input.indicatorSinceMs) ?: continue
            val startDay = Retention.dayIndex(sinceMs, input.zoneOffsetMs)

            var examined = false

            for (back in 0 until config.evaluateDays) {

                val day = today - back
                val observed = (byDay[day] ?: 0).toLong()
                if (observed < config.minEpisodes) continue

                var days = 0
                var total = 0L
                for (d in startDay until day) {
                    if (isBlindDay(d)) continue
                    days++
                    total += byDay[d] ?: 0
                }

                if (days < config.minLearningDays) {
                    if (!examined) {
                        learningCells++
                        learningPackages += cell.packageName
                        examined = true
                    }
                    continue
                }

                val history = CellCounts(total, days.toDouble())
                val prior = Cells.priorFor(cell.stratum)
                val p = GammaPoisson.upperTailProbability(history, observed, prior)

                allP += p
                testedCells += cell
                candidates += Candidate(
                    cell = cell,
                    day = day,
                    observed = observed,
                    expectedPerDay = GammaPoisson.posteriorMeanPerDay(history, prior),
                    p = p,
                )
            }
        }

        val rejectedByFdr: Set<Double>? = config.fdrQ?.let { q ->
            FalseDiscoveryRate.benjaminiHochberg(allP, q).map { allP[it] }.toSet()
        }

        // The most surprising day per cell, if it clears the bar.
        val chosen = candidates
            .filter { it.p < config.alpha }
            .filter { rejectedByFdr == null || it.p in rejectedByFdr }
            .groupBy { it.cell }
            .map { (_, list) -> list.minWith(compareBy({ it.p }, { -it.day })) }
            .sortedWith(compareBy({ it.p }, { it.cell.packageName }, { it.cell.op }, { it.cell.stratum }))

        val findings = chosen.map { c ->
            val dayStart = Retention.dayStartMs(c.day, input.zoneOffsetMs)
            Finding.BaselineDeviation(
                packageName = c.cell.packageName,
                op = c.cell.op,
                stratum = c.cell.stratum,
                pValue = c.p,
                observed = c.observed,
                expectedPerDay = c.expectedPerDay,
                dayStartMs = dayStart,
                evidence = evidenceFor(input.rows, c.cell, dayStart, dayStart + Retention.DAY_MS),
            )
        }

        val historyDays = input.countingSinceMs
            ?.let { (today - Retention.dayIndex(it, input.zoneOffsetMs)).toInt().coerceAtLeast(0) }
            ?: 0

        return BaselineReport(
            findings = findings,
            testedCells = testedCells.size,
            learningCells = learningCells,
            learningPackages = learningPackages,
            historyDays = historyDays,
        )
    }

    private fun evidenceFor(rows: List<LedgerRecord>, cell: CellKey, from: Long, to: Long): List<LedgerRecord> =
        rows.filter {
            it.recordKind != LedgerRecordKind.AGGREGATE.name &&
                    Cells.eligible(it) &&
                    Cells.keyOf(it) == cell &&
                    it.timestampMs in from until to
        }.sortedWith(compareBy({ it.timestampMs }, { it.hash }))
}
