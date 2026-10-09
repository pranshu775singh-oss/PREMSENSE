package com.ojas.permsense.findings

import com.ojas.permsense.json.Json
import com.ojas.permsense.json.JsonException
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Learns, on the phone, which findings this user finds worth acting on, and uses that only to
 * order the ones that are not critical.
 *
 * A logistic regression over a handful of plain features (which rule, which op, which context,
 * how severe), trained one example at a time from what the user does: opening settings or fixing
 * is "useful", dismissing is "not useful". It is deliberately tiny and explainable.
 *
 * What it must never do is decide whether something exists. Critical (severity 3) findings are
 * always shown and always first; the ranker only reorders the rest. A model that quietly learned
 * to hide the thing that mattered would defeat the product.
 */
class TriageRanker(
    weights: Map<String, Double> = emptyMap(),
    private val learningRate: Double = 0.3,
    private val regularisation: Double = 0.002,
) {

    private val w = HashMap(weights)

    /** The chance, between 0 and 1, that the user will find this finding worth acting on. */
    fun score(finding: Finding): Double {
        var z = w.getOrDefault(BIAS, 0.0)
        for (feature in features(finding)) z += w.getOrDefault(feature, 0.0)
        return sigmoid(z)
    }

    /** Learn from one reaction. [useful] true if the user acted on it, false if they dismissed it. */
    fun learn(finding: Finding, useful: Boolean) {
        val target = if (useful) 1.0 else 0.0
        val error = target - score(finding)

        for (feature in features(finding) + BIAS) {
            val old = w.getOrDefault(feature, 0.0)
            val updated = old + learningRate * error - regularisation * old
            w[feature] = max(-MAX_WEIGHT, min(MAX_WEIGHT, updated))
        }
    }

    /**
     * Severity first, always. Within a severity, the more useful-looking come first. Equal scores
     * fall back to the order given, so the result is deterministic.
     */
    fun order(findings: List<Finding>): List<Finding> =
        findings
            .withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<Finding>> { it.value.severity }
                    .thenByDescending { if (it.value.severity >= 3) 0.0 else score(it.value) }
                    .thenBy { it.index }
            )
            .map { it.value }

    fun weights(): Map<String, Double> = w.toMap()

    fun toJson(): String = Json.write(mapOf("version" to 1, "weights" to w))

    private fun features(f: Finding): List<String> {
        val out = ArrayList<String>(6)
        out += "kind:" + when (f) {
            is Finding.PolicyViolation -> "policy"
            is Finding.BaselineDeviation -> "baseline"
            is Finding.UpdateDrift -> "drift"
            is Finding.SustainedShift -> "shift"
        }
        out += "sev:${f.severity}"
        when (f) {
            is Finding.PolicyViolation -> {
                out += "rule:${f.ruleId}"
                f.op?.let { out += "op:$it" }
            }
            is Finding.BaselineDeviation -> { out += "op:${f.op}"; out += "ctx:${f.stratum}" }
            is Finding.UpdateDrift -> { out += "op:${f.op}"; out += "ctx:${f.stratum}" }
            is Finding.SustainedShift -> { out += "op:${f.op}"; out += "ctx:${f.stratum}" }
        }
        return out
    }

    companion object {

        private const val BIAS = "bias"
        private const val MAX_WEIGHT = 6.0

        private fun sigmoid(z: Double) = 1.0 / (1.0 + exp(-z))

        /** Restores a ranker from [toJson]. Anything unreadable gives a fresh, untrained one. */
        fun fromJson(text: String?): TriageRanker {
            if (text.isNullOrBlank()) return TriageRanker()
            return try {
                val root = Json.parseObject(text)
                @Suppress("UNCHECKED_CAST")
                val raw = root["weights"] as? Map<String, Any?> ?: return TriageRanker()
                TriageRanker(raw.mapNotNull { (k, v) ->
                    val d = when (v) { is Long -> v.toDouble(); is Double -> v; else -> null }
                    d?.takeIf { it.isFinite() }?.let { k to it }
                }.toMap())
            } catch (_: JsonException) {
                TriageRanker()
            }
        }
    }
}
