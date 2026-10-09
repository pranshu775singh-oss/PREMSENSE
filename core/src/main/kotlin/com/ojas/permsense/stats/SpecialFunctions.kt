package com.ojas.permsense.stats

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.sin

/**
 * The few special functions the statistical layer needs, written out so there is no runtime
 * dependency. Apache Commons Math is used only as a test oracle (see the tests).
 */
object SpecialFunctions {

    private const val LANCZOS_G = 7.0

    private val LANCZOS = doubleArrayOf(
        0.99999999999980993,
        676.5203681218851,
        -1259.1392167224028,
        771.32342877765313,
        -176.61502916214059,
        12.507343278686905,
        -0.13857109526572012,
        9.9843695780195716e-6,
        1.5056327351493116e-7,
    )

    /** ln Γ(x) for x > 0, by the Lanczos approximation (about 15 significant digits). */
    fun logGamma(x: Double): Double {

        require(x > 0.0) { "logGamma needs x > 0, got $x" }

        if (x < 0.5) {
            // Reflection: Γ(x) Γ(1 - x) = π / sin(πx)
            return ln(PI / abs(sin(PI * x))) - logGamma(1.0 - x)
        }

        val z = x - 1.0
        var a = LANCZOS[0]
        val t = z + LANCZOS_G + 0.5
        for (i in 1 until LANCZOS.size) {
            a += LANCZOS[i] / (z + i)
        }

        return 0.5 * ln(2.0 * PI) + (z + 0.5) * ln(t) - t + ln(a)
    }

    /**
     * The regularised incomplete beta function I_x(a, b), for a, b > 0 and 0 <= x <= 1.
     * Continued fraction evaluated with the modified Lentz method.
     */
    fun regularizedBeta(x: Double, a: Double, b: Double): Double {

        require(a > 0.0 && b > 0.0) { "regularizedBeta needs a, b > 0" }

        if (x <= 0.0) return 0.0
        if (x >= 1.0) return 1.0

        val logFront = logGamma(a + b) - logGamma(a) - logGamma(b) + a * ln(x) + b * ln1p(-x)
        val front = exp(logFront)

        // The fraction converges quickly for x below the mean of the distribution; otherwise use
        // the symmetry I_x(a, b) = 1 - I_(1-x)(b, a).
        return if (x < (a + 1.0) / (a + b + 2.0)) {
            front * betaFraction(x, a, b) / a
        } else {
            1.0 - front * betaFraction(1.0 - x, b, a) / b
        }
    }

    private fun betaFraction(x: Double, a: Double, b: Double): Double {

        val tiny = 1e-300
        val eps = 1e-15

        val qab = a + b
        val qap = a + 1.0
        val qam = a - 1.0

        var c = 1.0
        var d = 1.0 - qab * x / qap
        if (abs(d) < tiny) d = tiny
        d = 1.0 / d
        var h = d

        for (m in 1..500) {

            val m2 = 2 * m

            var aa = m * (b - m) * x / ((qam + m2) * (a + m2))
            d = 1.0 + aa * d
            if (abs(d) < tiny) d = tiny
            c = 1.0 + aa / c
            if (abs(c) < tiny) c = tiny
            d = 1.0 / d
            h *= d * c

            aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2))
            d = 1.0 + aa * d
            if (abs(d) < tiny) d = tiny
            c = 1.0 + aa / c
            if (abs(c) < tiny) c = tiny
            d = 1.0 / d
            val delta = d * c
            h *= delta

            if (abs(delta - 1.0) < eps) return h
        }

        return h
    }
}
