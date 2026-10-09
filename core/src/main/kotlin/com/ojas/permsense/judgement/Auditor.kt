package com.ojas.permsense.judgement

import com.ojas.permsense.baseline.BaselineEngine
import com.ojas.permsense.baseline.BaselineInput
import com.ojas.permsense.baseline.BaselineReport
import com.ojas.permsense.baseline.DriftEngine
import com.ojas.permsense.baseline.DriftInput
import com.ojas.permsense.baseline.ShiftEngine
import com.ojas.permsense.findings.Finding
import com.ojas.permsense.findings.FindingRanking
import com.ojas.permsense.findings.SuggestedAction
import com.ojas.permsense.ledger.LedgerRecord
import com.ojas.permsense.model.AppInventory
import com.ojas.permsense.model.AppVersionPoint

/** Everything the judgement layers need, gathered once. */
class AuditInput(
    val ledgerRows: List<LedgerRecord>,
    val inventory: List<AppInventory>,
    val nowMs: Long,
    /** When op-level sensing (T1 or better) first ran; null if it never has. */
    val observingSinceMs: Long?,
    /** Local time zone offset, so "a day" means the user's day. */
    val zoneOffsetMs: Long = 0L,
    /** When counting-grade sensing (the live collector) began. */
    val countingSinceMs: Long? = null,
    /** Same for camera, microphone and location, which the OS history can seed earlier. */
    val indicatorSinceMs: Long? = countingSinceMs,
    /** Every version of each app seen, with when it took effect. */
    val versionHistory: Map<String, List<AppVersionPoint>> = emptyMap(),
)

class AuditResult(
    val findings: List<Finding>,
    val baseline: BaselineReport,
)

/**
 * Runs the three judgement layers over a ledger and returns one ranked, de-duplicated list.
 *
 *  Layer 1  policy rules: correct from the first episode, no history needed
 *  Layer 2  each app's own baseline: is today unusual for this app?
 *  Layer 3  drift: did behaviour change after an update, or shift for good?
 *
 * All three produce the same [Finding] type, so everything downstream is blind to which layer
 * spoke.
 */
class Auditor(
    private val policy: PolicyEngine = PolicyEngine.default(),
    private val baseline: BaselineEngine = BaselineEngine(),
    private val drift: DriftEngine = DriftEngine(),
    private val shift: ShiftEngine = ShiftEngine(),
) {

    /** Layer 1 only. */
    fun audit(
        ledgerRows: List<LedgerRecord>,
        inventory: List<AppInventory>,
        nowMs: Long,
        observingSinceMs: Long?,
    ): List<Finding> {

        val slice = LedgerSlice(ledgerRows, nowMs, observingSinceMs)

        return FindingRanking.rank(policy.evaluateAll(slice, inventory))
    }

    /** All three layers. */
    fun analyse(input: AuditInput): AuditResult {

        val policyFindings = policy.evaluateAll(
            LedgerSlice(input.ledgerRows, input.nowMs, input.observingSinceMs),
            input.inventory,
        )

        val baselineInput = BaselineInput(
            rows = input.ledgerRows,
            nowMs = input.nowMs,
            zoneOffsetMs = input.zoneOffsetMs,
            countingSinceMs = input.countingSinceMs,
            indicatorSinceMs = input.indicatorSinceMs,
        )

        val baselineReport = baseline.evaluate(baselineInput)

        val driftFindings = drift.evaluate(
            DriftInput(
                rows = input.ledgerRows,
                versionHistory = input.versionHistory,
                nowMs = input.nowMs,
                zoneOffsetMs = input.zoneOffsetMs,
                countingSinceMs = input.countingSinceMs,
                indicatorSinceMs = input.indicatorSinceMs,
            )
        )

        val shiftFindings = shift.evaluate(baselineInput)

        // Statistical findings only concern apps that are still installed and are not the platform.
        val byPackage = input.inventory.filter { !it.isSystemApp }.associateBy { it.packageName }

        val statistical: List<Finding> =
            (baselineReport.findings + driftFindings + shiftFindings).mapNotNull { f ->
                val app = byPackage[f.packageName] ?: return@mapNotNull null
                refineAction(f, app)
            }

        return AuditResult(
            findings = FindingRanking.rank(policyFindings + statistical),
            baseline = baselineReport,
        )
    }

    /** A statistical finding can offer a one-tap fix when the app actually holds the permission. */
    private fun refineAction(finding: Finding, app: AppInventory): Finding {

        val op = when (finding) {
            is Finding.BaselineDeviation -> finding.op
            is Finding.UpdateDrift -> finding.op
            is Finding.SustainedShift -> finding.op
            is Finding.PolicyViolation -> return finding
        }

        val permission = PermissionOps.permissionFor(op)
        val action: SuggestedAction =
            if (permission != null && permission in app.grantedPermissions) {
                SuggestedAction.RevokePermission(app.packageName, permission)
            } else {
                SuggestedAction.OpenAppSettings(app.packageName)
            }

        return when (finding) {
            is Finding.BaselineDeviation -> finding.copy(suggestedAction = action)
            is Finding.UpdateDrift -> finding.copy(suggestedAction = action)
            is Finding.SustainedShift -> finding.copy(suggestedAction = action)
            is Finding.PolicyViolation -> finding
        }
    }
}
