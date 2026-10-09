package com.ojas.permsense.stats

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Prior on an access rate: Gamma(alpha0, beta0), with mean alpha0 / beta0 episodes per day.
 */
data class Prior(val alpha0: Double, val beta0: Double) {
    init {
        require(alpha0 > 0.0 && beta0 > 0.0) { "A Gamma prior needs positive parameters" }
    }
}

/**
 * The two sufficient statistics of one cell: how many episodes were seen, and over how many days
 * of observation. Exposure is a number of days and may be fractional (a day with a collector gap
 * only counts for the time it was actually watched).
 */
data class CellCounts(
    val totalCount: Long,
    val exposureDays: Double,
) {
    init {
        require(totalCount >= 0L && exposureDays >= 0.0)
    }
}

/**
 * Negative binomial: the number of failures before the r-th success when each trial succeeds with
 * probability p. This is the posterior predictive of a Poisson count under a Gamma prior.
 */
object NegativeBinomial {

    /** P(Y >= k). Computed through the incomplete beta function so that tiny tails stay accurate. */
    fun survival(k: Long, r: Double, p: Double): Double {

        require(r > 0.0) { "r must be positive" }
        require(p > 0.0 && p <= 1.0) { "p must be in (0, 1]" }

        if (k <= 0L) return 1.0
        if (p >= 1.0) return 0.0

        // P(Y <= k - 1) = I_p(r, k), so P(Y >= k) = 1 - I_p(r, k) = I_(1-p)(k, r).
        return SpecialFunctions.regularizedBeta(1.0 - p, k.toDouble(), r)
    }

    fun pmf(k: Long, r: Double, p: Double): Double {
        if (k < 0L) return 0.0
        val logPmf = SpecialFunctions.logGamma(k + r) - SpecialFunctions.logGamma(r) -
                SpecialFunctions.logGamma(k + 1.0) + r * ln(p) + k * ln(1.0 - p)
        return exp(logPmf)
    }
}

object GammaPoisson {

    fun posteriorShape(cell: CellCounts, prior: Prior): Double = prior.alpha0 + cell.totalCount

    fun posteriorRate(cell: CellCounts, prior: Prior): Double = prior.beta0 + cell.exposureDays

    fun posteriorMeanPerDay(cell: CellCounts, prior: Prior): Double =
        posteriorShape(cell, prior) / posteriorRate(cell, prior)

    /**
     * The chance of seeing [observed] or more episodes in a new window of [windowDays], if the
     * cell kept behaving as its own history says. Small means surprising.
     */
    fun upperTailProbability(
        cell: CellCounts,
        observed: Long,
        prior: Prior,
        windowDays: Double = 1.0,
    ): Double {

        val r = posteriorShape(cell, prior)
        val rate = posteriorRate(cell, prior)

        return NegativeBinomial.survival(observed, r, rate / (rate + windowDays))
    }
}

object RateComparison {

    /**
     * P(rate_post > kappa * rate_pre | data), exactly.
     *
     * With X ~ Gamma(a1, rate b1) and Y ~ Gamma(a0, rate b0), X > kappa * Y is the same as
     * X' / (X' + Y') > c / (1 + c) where X' and Y' are the unit-rate versions and
     * c = kappa * b1 / b0, and X' / (X' + Y') is Beta(a1, a0). So the answer is a regularised
     * incomplete beta, no sampling needed. It is used to check the Monte Carlo version below.
     */
    fun probabilityIncreaseExact(pre: CellCounts, post: CellCounts, prior: Prior, kappa: Double): Double {

        val a0 = GammaPoisson.posteriorShape(pre, prior)
        val b0 = GammaPoisson.posteriorRate(pre, prior)
        val a1 = GammaPoisson.posteriorShape(post, prior)
        val b1 = GammaPoisson.posteriorRate(post, prior)

        val c = kappa * b1 / b0

        return SpecialFunctions.regularizedBeta(1.0 / (1.0 + c), a0, a1)
    }

    /**
     * The same probability by Monte Carlo, as the design specifies. The generator is seeded so the
     * result is repeatable: detection tests built on it are deterministic, not flaky. Never seed
     * statistical code from the clock.
     */
    fun probabilityIncrease(
        pre: CellCounts,
        post: CellCounts,
        prior: Prior,
        kappa: Double = 3.0,
        samples: Int = 20_000,
        seed: Long = 42L,
    ): Double {

        val sampler = GammaSampler(Random(seed))

        val aPre = GammaPoisson.posteriorShape(pre, prior)
        val bPre = GammaPoisson.posteriorRate(pre, prior)
        val aPost = GammaPoisson.posteriorShape(post, prior)
        val bPost = GammaPoisson.posteriorRate(post, prior)

        var hits = 0
        repeat(samples) {
            val lambdaPre = sampler.sample(aPre, bPre)
            val lambdaPost = sampler.sample(aPost, bPost)
            if (lambdaPost > kappa * lambdaPre) hits++
        }

        return hits.toDouble() / samples
    }
}

/** Gamma variates by the Marsaglia and Tsang method, from a caller-supplied generator. */
class GammaSampler(private val random: Random) {

    /** A draw from Gamma(shape, rate). */
    fun sample(shape: Double, rate: Double): Double {

        require(shape > 0.0 && rate > 0.0)

        if (shape < 1.0) {
            // Gamma(a) = Gamma(a + 1) * U^(1/a)
            val u = random.nextDouble()
            return sample(shape + 1.0, rate) * u.pow(1.0 / shape)
        }

        val d = shape - 1.0 / 3.0
        val c = 1.0 / sqrt(9.0 * d)

        while (true) {

            var x: Double
            var v: Double
            do {
                x = normal()
                v = 1.0 + c * x
            } while (v <= 0.0)

            v = v * v * v
            val u = random.nextDouble()

            if (u < 1.0 - 0.0331 * x * x * x * x) return d * v / rate
            if (ln(u) < 0.5 * x * x + d * (1.0 - v + ln(v))) return d * v / rate
        }
    }

    // Marsaglia polar method.
    private fun normal(): Double {
        while (true) {
            val u = 2.0 * random.nextDouble() - 1.0
            val v = 2.0 * random.nextDouble() - 1.0
            val s = u * u + v * v
            if (s > 0.0 && s < 1.0) return u * sqrt(-2.0 * ln(s) / s)
        }
    }
}

/** Benjamini and Hochberg false discovery rate control. */
object FalseDiscoveryRate {

    /**
     * Indices of the hypotheses to reject at false discovery rate [q]: sort the p-values, find the
     * largest k with p(k) <= k * q / m, and reject everything up to it.
     */
    fun benjaminiHochberg(pValues: List<Double>, q: Double): Set<Int> {

        require(q > 0.0 && q < 1.0)

        val m = pValues.size
        if (m == 0) return emptySet()

        val order = pValues.indices.sortedBy { pValues[it] }

        var cutoff = -1
        order.forEachIndexed { rank, index ->
            if (pValues[index] <= (rank + 1) * q / m) cutoff = rank
        }

        return if (cutoff < 0) emptySet() else order.take(cutoff + 1).toSet()
    }
}

/**
 * One-sided Poisson CUSUM for a rate that has risen from [lambda0] to about [lambda1].
 * C(t) = max(0, C(t-1) + y(t) - k) with the reference value
 * k = (lambda1 - lambda0) / ln(lambda1 / lambda0), and an alarm while C(t) exceeds [threshold].
 */
class PoissonCusum(
    val lambda0: Double,
    val lambda1: Double,
    val threshold: Double,
) {

    init {
        require(lambda0 > 0.0 && lambda1 > lambda0) { "CUSUM needs 0 < lambda0 < lambda1" }
        require(threshold > 0.0)
    }

    val reference: Double = (lambda1 - lambda0) / ln(lambda1 / lambda0)

    var value: Double = 0.0
        private set

    fun update(count: Long): Double {
        value = max(0.0, value + count - reference)
        return value
    }

    val alarmed: Boolean get() = value > threshold
}
