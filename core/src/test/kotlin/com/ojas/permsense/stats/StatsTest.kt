package com.ojas.permsense.stats

import org.apache.commons.math3.distribution.GammaDistribution
import org.apache.commons.math3.distribution.PascalDistribution
import org.apache.commons.math3.special.Beta
import org.apache.commons.math3.special.Gamma
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The statistical layer is hand-written, so every piece is checked against Apache Commons Math
 * (used here only as an oracle, never at runtime) or against an exact identity.
 */
class StatsTest {

    private fun assertClose(expected: Double, actual: Double, relative: Double = 1e-9, absolute: Double = 1e-300) {
        val tolerance = max(abs(expected) * relative, absolute)
        assertTrue("expected $expected but was $actual (tolerance $tolerance)", abs(expected - actual) <= tolerance)
    }

    // ------------------------------------------------------------------ special functions

    @Test
    fun logGammaMatchesTheReferenceImplementation() {
        val points = listOf(0.01, 0.1, 0.5, 0.9, 1.0, 1.5, 2.0, 2.5, 3.7, 10.0, 25.5, 100.0, 1000.0, 12345.6)
        for (x in points) {
            assertClose(Gamma.logGamma(x), SpecialFunctions.logGamma(x), relative = 1e-12, absolute = 1e-12)
        }
    }

    @Test
    fun regularizedBetaMatchesTheReferenceImplementationOnAGrid() {
        val shapes = listOf(0.3, 0.5, 1.0, 1.5, 2.0, 4.0, 11.0, 50.0, 300.0)
        val xs = listOf(1e-6, 1e-3, 0.01, 0.1, 0.3, 0.5, 0.7, 0.9, 0.99, 0.999)
        for (a in shapes) for (b in shapes) for (x in xs) {
            assertClose(Beta.regularizedBeta(x, a, b), SpecialFunctions.regularizedBeta(x, a, b), relative = 1e-8, absolute = 1e-14)
        }
    }

    @Test
    fun regularizedBetaHasTheRightEndsAndSymmetry() {
        assertEquals(0.0, SpecialFunctions.regularizedBeta(0.0, 2.0, 3.0), 0.0)
        assertEquals(1.0, SpecialFunctions.regularizedBeta(1.0, 2.0, 3.0), 0.0)
        assertClose(1.0, SpecialFunctions.regularizedBeta(0.3, 2.5, 4.0) + SpecialFunctions.regularizedBeta(0.7, 4.0, 2.5), 1e-12)
    }

    // ------------------------------------------------------------------ negative binomial

    @Test
    fun survivalMatchesTheReferencePascalDistributionForIntegerR() {
        for (r in listOf(1, 2, 3, 7, 20)) for (p in listOf(0.05, 0.3, 0.5, 0.9, 11.0 / 12.0, 0.999)) {
            val oracle = PascalDistribution(r, p)
            for (k in listOf(1L, 2L, 3L, 5L, 10L, 40L)) {
                val expected = 1.0 - oracle.cumulativeProbability((k - 1).toInt())
                // 1 - cdf loses precision in the far tail, so compare absolutely there.
                assertClose(expected, NegativeBinomial.survival(k, r.toDouble(), p), relative = 1e-7, absolute = 1e-12)
            }
        }
    }

    @Test
    fun survivalMatchesSummingThePmfForNonIntegerR() {
        for (r in listOf(0.5, 1.5, 2.7, 6.25)) for (p in listOf(0.2, 0.6, 11.0 / 12.0)) for (k in listOf(1L, 2L, 4L, 9L)) {
            var cdf = 0.0
            for (i in 0 until k) {
                val logPmf = Gamma.logGamma(i + r) - Gamma.logGamma(r) - Gamma.logGamma(i + 1.0) + r * ln(p) + i * ln(1 - p)
                cdf += exp(logPmf)
            }
            assertClose(1.0 - cdf, NegativeBinomial.survival(k, r, p), relative = 1e-7, absolute = 1e-12)
        }
    }

    @Test
    fun theWorkedExampleFromTheDesignGivesASmallUpperTail() {
        // n = 10 days, weak prior (0.5, 1), one episode in all that time, then 4 in a day.
        val tail = GammaPoisson.upperTailProbability(CellCounts(1, 10.0), 4, Prior(0.5, 1.0))

        // Posterior Gamma(1.5, 11): r = 1.5, p = 11/12.
        assertClose(NegativeBinomial.survival(4, 1.5, 11.0 / 12.0), tail, 1e-12)
        assertTrue("tail was $tail", tail < 1e-3)
        assertTrue(tail > 1e-6)
    }

    @Test
    fun survivalIsOneForZeroAndDecreasesWithK() {
        assertEquals(1.0, NegativeBinomial.survival(0, 1.5, 0.9), 0.0)
        var previous = 1.0
        for (k in 1L..30L) {
            val s = NegativeBinomial.survival(k, 1.5, 0.9)
            assertTrue(s <= previous)
            assertTrue(s >= 0.0)
            previous = s
        }
    }

    @Test
    fun aSingleEventOnAQuietCellIsNotSurprisingButTwoAre() {
        // 10 silent days, weak prior: one episode is ordinary (about 4%), two are not (0.3%).
        val quiet = CellCounts(0, 10.0)
        val prior = Prior(0.5, 1.0)

        assertTrue(GammaPoisson.upperTailProbability(quiet, 1, prior) > 0.02)
        assertTrue(GammaPoisson.upperTailProbability(quiet, 2, prior) < 0.01)
    }

    @Test
    fun theFailureOfTheOriginalZScoreDoesNotAppear() {
        // With a rate near zero the original z-score exploded: one event at lambda = 0.01 scored
        // (1 - 0.01) / sqrt(0.01) = 9.9, "extreme" for an ordinary access. The predictive
        // probability stays a probability: about half a per cent after 100 silent days, which is
        // a mild surprise, and that is why the engine also demands at least two episodes in a day.
        val rare = CellCounts(0, 100.0)
        val p = GammaPoisson.upperTailProbability(rare, 1, Prior(0.5, 1.0))

        assertTrue(p in 0.0..1.0)
        assertEquals(1.0 - Math.pow(101.0 / 102.0, 0.5), p, 1e-12)
        // And the sampling distribution is finite at exactly zero history, where a z-score is undefined.
        assertTrue(GammaPoisson.upperTailProbability(CellCounts(0, 0.0), 1, Prior(0.5, 1.0)).isFinite())
    }

    // ------------------------------------------------------------------ rate comparison

    private val prior = Prior(0.5, 1.0)

    @Test
    fun theExactRateIncreaseProbabilityMatchesAnIndependentNumericalIntegral() {
        // P(X > k Y) by integrating the Gamma densities with the reference library.
        val pre = CellCounts(6, 14.0)
        val post = CellCounts(30, 3.0)
        val kappa = 3.0

        val yDist = GammaDistribution(GammaPoisson.posteriorShape(pre, prior), 1.0 / GammaPoisson.posteriorRate(pre, prior))
        val xDist = GammaDistribution(GammaPoisson.posteriorShape(post, prior), 1.0 / GammaPoisson.posteriorRate(post, prior))

        // Integrate over Y: P(X > kappa y) * f_Y(y) dy (midpoint rule on a fine grid).
        var integral = 0.0
        val upper = yDist.inverseCumulativeProbability(1 - 1e-12)
        val steps = 400_000
        val h = upper / steps
        for (i in 0 until steps) {
            val y = (i + 0.5) * h
            integral += (1.0 - xDist.cumulativeProbability(kappa * y)) * yDist.density(y) * h
        }

        assertEquals(integral, RateComparison.probabilityIncreaseExact(pre, post, prior, kappa), 1e-5)
    }

    @Test
    fun monteCarloAgreesWithTheExactAnswerWithinSamplingError() {
        val cases = listOf(
            CellCounts(6, 14.0) to CellCounts(30, 3.0),
            CellCounts(0, 10.0) to CellCounts(4, 2.0),
            CellCounts(40, 14.0) to CellCounts(45, 5.0),
            CellCounts(2, 20.0) to CellCounts(12, 1.0),
        )
        for ((pre, post) in cases) {
            val exact = RateComparison.probabilityIncreaseExact(pre, post, prior, 3.0)
            val estimate = RateComparison.probabilityIncrease(pre, post, prior, 3.0, samples = 100_000)
            val standardError = sqrt(exact * (1 - exact) / 100_000)
            assertEquals("pre=$pre post=$post", exact, estimate, 5 * standardError + 1e-4)
        }
    }

    @Test
    fun theSeededEstimateIsRepeatableAndTheSeedMatters() {
        // A case near 50 per cent, so different seeds give visibly different estimates.
        val pre = CellCounts(6, 14.0)
        val post = CellCounts(4, 3.0)

        val a = RateComparison.probabilityIncrease(pre, post, prior, seed = 42)
        val b = RateComparison.probabilityIncrease(pre, post, prior, seed = 42)
        val c = RateComparison.probabilityIncrease(pre, post, prior, seed = 43)

        assertEquals(a, b, 0.0)
        assertTrue(a != c)
    }

    @Test
    fun aRiseIsLikelyAndNoChangeIsNot() {
        val pre = CellCounts(14, 14.0)

        assertTrue(RateComparison.probabilityIncreaseExact(pre, CellCounts(60, 3.0), prior, 3.0) > 0.99)
        assertTrue(RateComparison.probabilityIncreaseExact(pre, CellCounts(3, 3.0), prior, 3.0) < 0.05)
    }

    @Test
    fun theGammaSamplerHasTheRightMeanAndVariance() {
        for ((shape, rate) in listOf(0.3 to 2.0, 1.0 to 1.0, 1.5 to 11.0, 7.5 to 0.5, 40.0 to 3.0)) {
            val sampler = GammaSampler(Random(7))
            val n = 200_000
            var sum = 0.0
            var sumSq = 0.0
            repeat(n) {
                val x = sampler.sample(shape, rate)
                sum += x
                sumSq += x * x
            }
            val mean = sum / n
            val variance = sumSq / n - mean * mean

            assertEquals("mean shape=$shape", shape / rate, mean, 0.02 * shape / rate + 1e-3)
            assertEquals("variance shape=$shape", shape / (rate * rate), variance, 0.05 * shape / (rate * rate) + 1e-3)
        }
    }

    // ------------------------------------------------------------------ false discovery rate

    @Test
    fun benjaminiHochbergRejectsTheRightHypotheses() {
        // Textbook example: m = 10, q = 0.05.
        val p = listOf(0.001, 0.008, 0.039, 0.041, 0.042, 0.06, 0.074, 0.205, 0.212, 0.216)
        // Thresholds k*q/m: .005 .010 .015 .020 .025 .030 ... so p(2) = .008 <= .010 but p(3) = .039 > .015.
        assertEquals(setOf(0, 1), FalseDiscoveryRate.benjaminiHochberg(p, 0.05))
    }

    @Test
    fun benjaminiHochbergUsesTheLargestPassingRank() {
        // m = 5, q = 0.05, thresholds .01 .02 .03 .04 .05. Rank 1 fails its own threshold
        // (0.011 > 0.01) but rank 4 passes (0.0149 <= 0.04), so everything up to rank 4 is rejected.
        val p = listOf(0.011, 0.012, 0.013, 0.0149, 0.9)

        assertEquals(setOf(0, 1, 2, 3), FalseDiscoveryRate.benjaminiHochberg(p, 0.05))
    }

    @Test
    fun benjaminiHochbergIgnoresTheOrderOfTheInput() {
        val p = listOf(0.9, 0.0149, 0.011, 0.013, 0.012)

        assertEquals(setOf(1, 2, 3, 4), FalseDiscoveryRate.benjaminiHochberg(p, 0.05))
    }

    @Test
    fun benjaminiHochbergOnNothingRejectsNothing() {
        assertTrue(FalseDiscoveryRate.benjaminiHochberg(emptyList(), 0.05).isEmpty())
        assertTrue(FalseDiscoveryRate.benjaminiHochberg(listOf(0.5, 0.9), 0.05).isEmpty())
    }

    // ------------------------------------------------------------------ CUSUM

    private fun poisson(rng: Random, lambda: Double): Long {
        val limit = exp(-lambda)
        var k = 0L
        var p = 1.0
        do {
            k++
            p *= rng.nextDouble()
        } while (p > limit)
        return k - 1
    }

    @Test
    fun cusumReferenceValueFollowsTheFormula() {
        val cusum = PoissonCusum(lambda0 = 1.0, lambda1 = 3.0, threshold = 6.0)
        assertEquals((3.0 - 1.0) / ln(3.0), cusum.reference, 1e-12)
    }

    @Test
    fun cusumStaysAtZeroWhileCountsAreLow() {
        val cusum = PoissonCusum(1.0, 3.0, 6.0)
        repeat(20) { cusum.update(0) }
        assertEquals(0.0, cusum.value, 0.0)
        assertTrue(!cusum.alarmed)
    }

    @Test
    fun cusumRarelyAlarmsWhenNothingHasChanged() {
        val rng = Random(2026)
        for (lambda0 in listOf(0.05, 0.3, 1.0, 3.0)) {
            val lambda1 = max(3 * lambda0, lambda0 + 1.0)
            var alarmedSeries = 0
            val series = 300
            repeat(series) {
                val cusum = PoissonCusum(lambda0, lambda1, 6.0)
                var any = false
                repeat(60) { if (cusum.update(poisson(rng, lambda0)) > 6.0) any = true }
                if (any) alarmedSeries++
            }
            // Roughly one false alarm per 2,000 to 20,000 days of one cell, so a few per cent of
            // 60-day series at the very most.
            assertTrue("lambda0=$lambda0: $alarmedSeries/$series series alarmed", alarmedSeries <= series * 0.05)
        }
    }

    @Test
    fun cusumCatchesATripledRateAndFasterTheHigherTheRate() {
        val rng = Random(99)
        val meanDelay = HashMap<Double, Double>()

        for (lambda0 in listOf(0.3, 1.0, 3.0)) {
            val lambda1 = max(3 * lambda0, lambda0 + 1.0)
            val series = 200
            var caught = 0
            var delays = 0

            repeat(series) {
                val cusum = PoissonCusum(lambda0, lambda1, 6.0)
                repeat(10) { cusum.update(poisson(rng, lambda0)) }
                for (day in 1..30) {
                    if (cusum.update(poisson(rng, lambda1)) > 6.0) {
                        caught++
                        delays += day
                        break
                    }
                }
            }

            assertTrue("lambda0=$lambda0: caught $caught/$series within 30 days", caught >= series * 0.95)
            meanDelay[lambda0] = delays.toDouble() / caught
        }

        assertTrue("delays $meanDelay", meanDelay.getValue(1.0) < 8.0)
        assertTrue("delays $meanDelay", meanDelay.getValue(3.0) < 4.0)
        assertTrue("delays $meanDelay", meanDelay.getValue(3.0) < meanDelay.getValue(0.3))
    }
}
