package com.ojas.permsense.findings

import com.ojas.permsense.ledger.LedgerRecord

/**
 * Something worth telling the user about, whichever judgement layer produced it.
 *
 * The UI and the alert budget only ever see [Finding]; they never need to know whether a rule,
 * the self-baseline or the drift engine raised it.
 */
sealed class Finding {

    abstract val packageName: String

    /** 3 = act now, 2 = look at this, 1 = informational. */
    abstract val severity: Int

    /** Ledger rows that prove the finding. Empty when the basis is inventory only. */
    abstract val evidence: List<LedgerRecord>

    abstract val suggestedAction: SuggestedAction

    /** Stable identity used for de-duplication and for linking findings to one episode. */
    abstract val dedupKey: String

    /** Newest evidence timestamp, or null for inventory-only findings. */
    val latestEvidenceMs: Long?
        get() = evidence.maxOfOrNull { it.timestampMs }

    /** A declarative rule matched. Correct from the first episode, needs no history. */
    data class PolicyViolation(
        override val packageName: String,
        val ruleId: String,
        val title: String,
        val explanation: String,
        val citation: String,
        val op: String?,
        val basis: Basis,
        override val evidence: List<LedgerRecord>,
        override val suggestedAction: SuggestedAction,
        override val severity: Int = 3,
        /**
         * The facts the text is built from (counts, versions, which powers), so a finding can be
         * worded in any language without parsing the English in [explanation].
         */
        val params: Map<String, String> = emptyMap(),
    ) : Finding() {
        override val dedupKey: String
            get() = "policy|$ruleId|$packageName|${op.orEmpty()}"
    }

    /** Layer 2: a day's count is surprising against this app's own history. */
    data class BaselineDeviation(
        override val packageName: String,
        val op: String,
        val stratum: String,
        val pValue: Double,
        /** Episodes seen on the day, and the app's own typical rate per day before it. */
        val observed: Long,
        val expectedPerDay: Double,
        val dayStartMs: Long,
        override val evidence: List<LedgerRecord>,
        override val suggestedAction: SuggestedAction = SuggestedAction.OpenAppSettings(packageName),
        override val severity: Int = 2,
    ) : Finding() {
        override val dedupKey: String
            get() = "baseline|$packageName|$op|$stratum"
    }

    /** Layer 3: behaviour changed after a version update. */
    data class UpdateDrift(
        override val packageName: String,
        val op: String,
        val stratum: String,
        val fromVersion: Long,
        val toVersion: Long,
        /** P(rate after the update is at least [factor] times the rate before it). */
        val probability: Double,
        val factor: Double,
        val ratePerDayBefore: Double,
        val ratePerDayAfter: Double,
        override val evidence: List<LedgerRecord>,
        override val suggestedAction: SuggestedAction = SuggestedAction.OpenAppSettings(packageName),
        override val severity: Int = 2,
    ) : Finding() {
        override val dedupKey: String
            get() = "drift|$packageName|$op|$stratum|$fromVersion|$toVersion"
    }

    /** Layer 3, not tied to an update: the rate has stayed raised for days (a CUSUM alarm). */
    data class SustainedShift(
        override val packageName: String,
        val op: String,
        val stratum: String,
        val sinceMs: Long,
        val daysRaised: Int,
        val ratePerDayBefore: Double,
        val ratePerDayRecent: Double,
        override val evidence: List<LedgerRecord>,
        override val suggestedAction: SuggestedAction = SuggestedAction.OpenAppSettings(packageName),
        override val severity: Int = 2,
    ) : Finding() {
        override val dedupKey: String
            get() = "shift|$packageName|$op|$stratum"
    }

    /** What a finding was derived from. Inventory-only findings need no recorded access. */
    enum class Basis {
        /** Derived from what the app requests or holds. Nothing was accessed yet. */
        INVENTORY,

        /** Derived from recorded accesses in the ledger. */
        LEDGER,
    }
}

/**
 * What the user can do about a finding. Remediation executes these only after confirmation, and
 * only through the allow-list. Without Shizuku the UI deep-links to Settings instead.
 */
sealed interface SuggestedAction {

    data class RevokePermission(
        val packageName: String,
        val permission: String,
    ) : SuggestedAction

    data class SetOpMode(
        val packageName: String,
        val op: String,
        val mode: OpMode,
    ) : SuggestedAction

    data class OpenAppSettings(
        val packageName: String,
    ) : SuggestedAction

    data object None : SuggestedAction
}

enum class OpMode {
    IGNORE,
    FOREGROUND_ONLY,
}

object FindingRanking {

    /**
     * Collapse duplicates (same [Finding.dedupKey]) keeping the highest severity and newest
     * evidence, then order by severity, then recency, then a fixed tiebreak so the result is
     * deterministic. Never drops a severity-3 finding.
     */
    fun rank(findings: List<Finding>): List<Finding> {
        return findings
            .groupBy { it.dedupKey }
            .values
            .map { group ->
                group.maxWith(
                    compareBy<Finding>(
                        { it.severity },
                        { it.latestEvidenceMs ?: Long.MIN_VALUE },
                    ),
                )
            }
            .sortedWith(
                compareByDescending<Finding> { it.severity }
                    .thenByDescending { it.latestEvidenceMs ?: Long.MIN_VALUE }
                    .thenBy { it.dedupKey },
            )
    }

    /**
     * Alert budget: every severity-3 finding is always surfaced; lower severities are capped at
     * [maxNonCritical] per digest, in rank order.
     */
    fun applyBudget(ranked: List<Finding>, maxNonCritical: Int): List<Finding> {
        require(maxNonCritical >= 0)
        val critical = ranked.filter { it.severity >= 3 }
        val rest = ranked.filter { it.severity < 3 }.take(maxNonCritical)
        return (critical + rest).sortedWith(
            compareByDescending<Finding> { it.severity }
                .thenByDescending { it.latestEvidenceMs ?: Long.MIN_VALUE }
                .thenBy { it.dedupKey },
        )
    }
}
