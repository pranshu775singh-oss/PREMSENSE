package com.ojas.permsense.baseline

import com.ojas.permsense.findings.Finding
import com.ojas.permsense.ledger.LedgerRecord
import com.ojas.permsense.ledger.LedgerRecordKind
import com.ojas.permsense.ledger.Retention
import com.ojas.permsense.model.AppVersionPoint
import com.ojas.permsense.stats.CellCounts
import com.ojas.permsense.stats.GammaPoisson
import com.ojas.permsense.stats.PoissonCusum
import com.ojas.permsense.stats.RateComparison
import kotlin.math.max

data class DriftConfig(
    /** "Practically larger": the rate must have at least tripled. */
    val kappa: Double = 3.0,
    /** Posterior probability of that increase needed to raise a finding. */
    val probabilityThreshold: Double = 0.95,
    /** The window after the update must hold at least this many episodes. */
    val minPostCount: Long = 3L,
    /** Days of watching before the update needed for a fair comparison. */
    val minPreExposureDays: Double = 3.0,
    val minPostExposureDays: Double = 0.25,
    /** How far back before the update to look. */
    val preWindowDays: Int = 14,
    /** Updates older than this are history, not news. */
    val maxChangeAgeDays: Int = 30,
    val samples: Int = 20_000,
    val seed: Long = 42L,
)

data class DriftInput(
    val rows: List<LedgerRecord>,
    /** Every version of each app seen so far, with when it took effect. */
    val versionHistory: Map<String, List<AppVersionPoint>>,
    val nowMs: Long,
    val zoneOffsetMs: Long,
    val countingSinceMs: Long?,
    val indicatorSinceMs: Long?,
)

/**
 * Layer 3, anchored on updates. At each version change, compare the rate of each (op, context)
 * before and after and ask for the probability that it grew by at least [DriftConfig.kappa].
 *
 * The probability is estimated by Monte Carlo with a fixed seed, so the same ledger always gives
 * the same answer and tests that depend on it are not flaky.
 */
class DriftEngine(private val config: DriftConfig = DriftConfig()) {

    fun evaluate(input: DriftInput): List<Finding.UpdateDrift> {

        val gaps = Cells.Gaps(input.rows)
        val day = Retention.DAY_MS

        val eligibleByPackage = input.rows
            .filter { it.recordKind != LedgerRecordKind.AGGREGATE.name && Cells.eligible(it) }
            .groupBy { it.packageName }

        val findings = ArrayList<Finding.UpdateDrift>()

        for ((pkg, versions) in input.versionHistory.entries.sortedBy { it.key }) {

            val points = versions.sortedWith(compareBy({ it.effectiveFromMs }, { it.versionCode }))
            val rows = eligibleByPackage[pkg].orEmpty()
            if (points.size < 2 || rows.isEmpty()) continue

            for (i in 1 until points.size) {

                val from = points[i - 1]
                val to = points[i]
                val changeMs = to.effectiveFromMs

                if (changeMs > input.nowMs) continue
                if (input.nowMs - changeMs > config.maxChangeAgeDays * day) continue

                val postEnd = minOf(input.nowMs, points.getOrNull(i + 1)?.effectiveFromMs ?: input.nowMs)

                val cells = rows
                    .filter { it.timestampMs in changeMs until postEnd }
                    .groupBy { Cells.keyOf(it) }

                for ((cell, postRows) in cells.entries.sortedBy { it.key.toString() }) {

                    val postCount = postRows.sumOf { Cells.weight(it) }.toLong()
                    if (postCount < config.minPostCount) continue

                    val startMs = Cells.startMs(cell.op, input.countingSinceMs, input.indicatorSinceMs) ?: continue
                    val preStart = max(startMs, changeMs - config.preWindowDays * day)
                    if (preStart >= changeMs) continue

                    val preExposure = gaps.exposureDays(preStart, changeMs)
                    val postExposure = gaps.exposureDays(changeMs, postEnd)
                    if (preExposure < config.minPreExposureDays || postExposure < config.minPostExposureDays) continue

                    val preCount = rows
                        .filter { Cells.keyOf(it) == cell && it.timestampMs in preStart until changeMs }
                        .sumOf { Cells.weight(it) }.toLong()

                    val pre = CellCounts(preCount, preExposure)
                    val post = CellCounts(postCount, postExposure)
                    val prior = Cells.priorFor(cell.stratum)

                    val probability = RateComparison.probabilityIncrease(
                        pre, post, prior, config.kappa, config.samples, config.seed,
                    )
                    if (probability < config.probabilityThreshold) continue

                    findings += Finding.UpdateDrift(
                        packageName = pkg,
                        op = cell.op,
                        stratum = cell.stratum,
                        fromVersion = from.versionCode,
                        toVersion = to.versionCode,
                        probability = probability,
                        factor = config.kappa,
                        ratePerDayBefore = GammaPoisson.posteriorMeanPerDay(pre, prior),
                        ratePerDayAfter = GammaPoisson.posteriorMeanPerDay(post, prior),
                        evidence = postRows.sortedWith(compareBy({ it.timestampMs }, { it.hash })).takeLast(20),
                    )
                }
            }
        }

        return findings.sortedWith(
            compareByDescending<Finding.UpdateDrift> { it.probability }
                .thenBy { it.packageName }.thenBy { it.op }.thenBy { it.stratum }
        )
    }
}

data class ShiftConfig(
    /** Days of history needed before a cell is watched for drift. */
    val minDays: Int = 14,
    /** The rate counts as shifted once it has tripled, and by at least this much per day. */
    val kappa: Double = 3.0,
    val minShiftPerDay: Double = 1.0,
    /**
     * Alarm threshold. Chosen by simulation (see ShiftEngineTest): at 6 the false alarm interval
     * is thousands of days per cell, and a tripling is caught in under a week.
     */
    val threshold: Double = 6.0,
    val maxGapFractionPerDay: Double = 0.25,
)

/**
 * Layer 3, not tied to an update. A server-side switch inside an SDK can change an app's behaviour
 * with no new version at all, so every watched cell also runs a Poisson CUSUM over its daily
 * counts. A finding is raised only while the alarm is still on at the latest complete day.
 */
class ShiftEngine(private val config: ShiftConfig = ShiftConfig()) {

    fun evaluate(input: BaselineInput): List<Finding.SustainedShift> {

        val today = Retention.dayIndex(input.nowMs, input.zoneOffsetMs)
        val gaps = Cells.Gaps(input.rows)

        val countsByCell = HashMap<CellKey, HashMap<Long, Int>>()
        for (row in input.rows) {
            if (!Cells.eligible(row)) continue
            val day = Retention.dayIndex(row.timestampMs, input.zoneOffsetMs)
            countsByCell.getOrPut(Cells.keyOf(row)) { HashMap() }.merge(day, Cells.weight(row), Int::plus)
        }

        val findings = ArrayList<Finding.SustainedShift>()

        for ((cell, byDay) in countsByCell.entries.sortedBy { it.key.toString() }) {

            val sinceMs = Cells.startMs(cell.op, input.countingSinceMs, input.indicatorSinceMs) ?: continue
            val startDay = Retention.dayIndex(sinceMs, input.zoneOffsetMs)

            // Complete days only, and days spent largely blind are skipped, not counted as quiet.
            val days = (startDay until today).filter {
                val start = Retention.dayStartMs(it, input.zoneOffsetMs)
                gaps.overlapMs(start, start + Retention.DAY_MS) <= config.maxGapFractionPerDay * Retention.DAY_MS
            }
            if (days.size < config.minDays) continue

            val reference = max(7, days.size / 2)
            val referenceDays = days.take(reference)
            val referenceCounts = CellCounts(
                totalCount = referenceDays.sumOf { (byDay[it] ?: 0).toLong() },
                exposureDays = referenceDays.size.toDouble(),
            )

            val prior = Cells.priorFor(cell.stratum)
            val lambda0 = max(GammaPoisson.posteriorMeanPerDay(referenceCounts, prior), MIN_RATE)
            val lambda1 = max(config.kappa * lambda0, lambda0 + config.minShiftPerDay)

            val cusum = PoissonCusum(lambda0, lambda1, config.threshold)

            var excursionStart = -1
            var index = 0
            for (d in days.drop(reference)) {
                val before = cusum.value
                cusum.update((byDay[d] ?: 0).toLong())
                if (before == 0.0 && cusum.value > 0.0) excursionStart = index
                if (cusum.value == 0.0) excursionStart = -1
                index++
            }

            if (!cusum.alarmed || excursionStart < 0) continue

            val shiftedDays = days.drop(reference).drop(excursionStart)
            val sinceDay = shiftedDays.first()
            val recentTotal = shiftedDays.sumOf { (byDay[it] ?: 0).toLong() }

            // The alarm statistic stays high for days after a rate returns to normal. Only report
            // a shift that is still going on: the latest days must still be above the level at
            // which the statistic would be falling.
            val latest = shiftedDays.takeLast(minOf(RECENT_DAYS, shiftedDays.size))
            val latestRate = latest.sumOf { (byDay[it] ?: 0).toLong() }.toDouble() / latest.size
            if (latestRate < cusum.reference) continue
            val sinceMs2 = Retention.dayStartMs(sinceDay, input.zoneOffsetMs)

            findings += Finding.SustainedShift(
                packageName = cell.packageName,
                op = cell.op,
                stratum = cell.stratum,
                sinceMs = sinceMs2,
                daysRaised = shiftedDays.size,
                ratePerDayBefore = lambda0,
                ratePerDayRecent = recentTotal.toDouble() / shiftedDays.size,
                evidence = input.rows.filter {
                    it.recordKind != LedgerRecordKind.AGGREGATE.name &&
                            Cells.eligible(it) && Cells.keyOf(it) == cell && it.timestampMs >= sinceMs2
                }.sortedWith(compareBy({ it.timestampMs }, { it.hash })).takeLast(20),
            )
        }

        return findings.sortedWith(
            compareByDescending<Finding.SustainedShift> { it.ratePerDayRecent / it.ratePerDayBefore }
                .thenBy { it.packageName }.thenBy { it.op }.thenBy { it.stratum }
        )
    }

    private companion object {
        /** Floor for the in-control rate so ln(lambda1 / lambda0) stays finite for silent cells. */
        const val MIN_RATE = 0.02

        /** How many of the latest days must still show the raised rate. */
        const val RECENT_DAYS = 3
    }
}
