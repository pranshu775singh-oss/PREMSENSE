package com.ojas.permsense.judgement

import com.ojas.permsense.findings.Finding
import com.ojas.permsense.ledger.LedgerRecord
import com.ojas.permsense.ledger.LedgerRecordKind
import com.ojas.permsense.model.AppInventory
import com.ojas.permsense.model.Ops

/**
 * A read-only window onto the ledger that the judgement engines evaluate.
 *
 * [observingSinceMs] is when op-level sensing (T1 or better) first began on this device. It is
 * null when only T0 has ever run. Rules that infer "never used" from silence (DEBT-01) refuse to
 * fire without it: silence from a source that could not see anything is not evidence.
 */
class LedgerSlice(
    val rows: List<LedgerRecord>,
    val nowMs: Long,
    val observingSinceMs: Long? = null,
) {

    private val byPackage: Map<String, List<LedgerRecord>> =
        rows
            .sortedWith(compareBy({ it.timestampMs }, { it.hash }))
            .groupBy { it.packageName }

    fun rowsFor(packageName: String): List<LedgerRecord> =
        byPackage[packageName].orEmpty()

    /** Accesses that really happened: episodes and OS-history backfill that were not denied. */
    fun accessesFor(packageName: String): List<LedgerRecord> =
        rowsFor(packageName).filter { it.isSuccessfulAccess() }

    /**
     * Ops the app has ever used, including use that retention has already folded into daily
     * aggregates. For rules that ask "has this ever been used".
     */
    fun usedOps(packageName: String): Set<String> =
        rowsFor(packageName)
            .filter { it.isSuccessfulAccess() || it.recordKind == LedgerRecordKind.AGGREGATE.name }
            .map { it.op }
            .toSet()
}

fun LedgerRecord.isSuccessfulAccess(): Boolean {

    val kind = recordKind
    if (kind != LedgerRecordKind.EPISODE.name && kind != LedgerRecordKind.BACKFILL.name) {
        return false
    }

    return when (accessResult?.trim()?.uppercase()) {
        null, "", "OBSERVED", "ALLOWED" -> true
        else -> false
    }
}

/** Apps whose uid is below the first application uid are the platform itself, not third parties. */
fun LedgerRecord.isThirdPartyUid(): Boolean = (uid % 100_000) >= 10_000

/** A pure predicate over a ledger slice plus one app's inventory. Not a scripting language. */
interface Rule {

    val id: String

    fun evaluate(slice: LedgerSlice, inventory: AppInventory): List<Finding.PolicyViolation>
}

class PolicyEngine(
    private val rules: List<Rule>,
    /** Version of the pack the rules came from, shown in reports. */
    val packVersion: String = "custom",
) {

    fun evaluateAll(
        slice: LedgerSlice,
        inventory: List<AppInventory>,
    ): List<Finding.PolicyViolation> {
        return inventory
            .sortedBy { it.packageName }
            .flatMap { app -> rules.flatMap { rule -> rule.evaluate(slice, app) } }
    }

    val ruleIds: List<String>
        get() = rules.map { it.id }

    companion object {

        /** The rules shipped inside the app, loaded from the bundled pack file. */
        fun default(): PolicyEngine = fromPack(PolicyPackLoader.loadBundled())

        /** Builds the engine from a validated pack. Disabled rules are left out. */
        fun fromPack(pack: PolicyPack): PolicyEngine =
            PolicyEngine(
                pack.rules
                    .filter { it.enabled }
                    .map { config ->
                        when (config.id) {
                            "INV-01" -> Inv01InvisibleAccess(config)
                            "DEBT-01" -> Debt01PermissionDebt(config)
                            "NEW-01" -> New01NewBehaviourAfterUpdate(config)
                            "RBI-01", "RBI-02", "RBI-03", "RBI-04" -> RbiProhibitedResource(config)
                            "RBI-05" -> Rbi05OutsideOnboarding(config)
                            "SPY-01" -> Spy01HighPowerGrants(config)
                            else -> throw PolicyPackException("No implementation for rule ${config.id}")
                        }
                    },
                packVersion = pack.packVersion,
            )
    }
}

/** Maps Android runtime permissions to the AppOps ops that record their use. */
object PermissionOps {

    private const val P = "android.permission."

    val opsByPermission: Map<String, Set<String>> =
        mapOf(
            "${P}READ_CONTACTS" to setOf(Ops.CONTACTS),
            "${P}READ_CALL_LOG" to setOf(Ops.CALL_LOG),
            "${P}READ_SMS" to setOf(Ops.SMS),
            "${P}RECEIVE_SMS" to setOf(Ops.SMS),
            "${P}READ_PHONE_STATE" to setOf(Ops.PHONE_STATE),
            "${P}CAMERA" to setOf(Ops.CAMERA),
            "${P}RECORD_AUDIO" to setOf(Ops.MIC),
            "${P}ACCESS_FINE_LOCATION" to setOf(Ops.FINE_LOC),
            "${P}ACCESS_COARSE_LOCATION" to setOf(Ops.COARSE_LOC),
            "${P}READ_MEDIA_IMAGES" to setOf(Ops.MEDIA_IMAGES),
            "${P}READ_MEDIA_VIDEO" to setOf(Ops.MEDIA_VIDEO),
            "${P}READ_MEDIA_AUDIO" to setOf(Ops.MEDIA_AUDIO),
            "${P}READ_EXTERNAL_STORAGE" to setOf(Ops.EXT_STORAGE),
        )

    /** The one permission that revokes access for an op, or null when no single one does. */
    fun permissionFor(op: String): String? =
        opsByPermission.entries
            .firstOrNull { (_, ops) -> op in ops }
            ?.key

    fun opsFor(permission: String): Set<String> =
        opsByPermission[permission].orEmpty()
}
