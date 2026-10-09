package com.ojas.permsense.judgement

import com.ojas.permsense.findings.Finding
import com.ojas.permsense.findings.SuggestedAction
import com.ojas.permsense.ledger.LedgerRecord
import com.ojas.permsense.ledger.LedgerRecordKind
import com.ojas.permsense.model.AppInventory
import com.ojas.permsense.model.Ops
import com.ojas.permsense.model.Visibility

/** Tag carried by [AppInventory.policyTags] for apps the digital-lending pack applies to. */
const val TAG_LENDING = "lending"

private const val DAY_MS = 24L * 60L * 60L * 1000L

private fun actionFor(packageName: String, op: String?, inventory: AppInventory): SuggestedAction {
    val permission = op?.let { PermissionOps.permissionFor(it) }
    return if (permission != null && permission in inventory.grantedPermissions) {
        SuggestedAction.RevokePermission(packageName, permission)
    } else {
        SuggestedAction.OpenAppSettings(packageName)
    }
}

private fun firstGranted(inventory: AppInventory, permissions: Set<String>): String? =
    permissions.sorted().firstOrNull { it in inventory.grantedPermissions }

// ---------------------------------------------------------------------------------------------
// INV-01  Invisible access
// ---------------------------------------------------------------------------------------------

/**
 * A sensitive op was used while the app had no resumed activity, no foreground service, and the
 * screen was off or locked. Platform uids are ignored; only third-party apps are judged.
 */
class Inv01InvisibleAccess(private val config: RuleConfig) : Rule {

    override val id = config.id

    override fun evaluate(slice: LedgerSlice, inventory: AppInventory): List<Finding.PolicyViolation> {

        if (inventory.isSystemApp) return emptyList()

        return slice.accessesFor(inventory.packageName)
            .filter {
                it.recordKind == LedgerRecordKind.EPISODE.name &&
                        it.op in Ops.SENSITIVE &&
                        it.visibilityStratum == Visibility.SCREEN_OFF.name &&
                        it.isThirdPartyUid()
            }
            .groupBy { it.op }
            .toSortedMap()
            .map { (op, rows) ->
                Finding.PolicyViolation(
                    packageName = inventory.packageName,
                    ruleId = id,
                    title = "Used ${opLabel(op)} while your screen was off",
                    explanation = "This app used ${opLabel(op)} ${rows.size} time(s) while it was not on " +
                            "screen, had no foreground service, and the screen was off or locked.",
                    citation = config.citation,
                    op = op,
                    basis = Finding.Basis.LEDGER,
                    evidence = rows,
                    suggestedAction = actionFor(inventory.packageName, op, inventory),
                    severity = config.severity,
                    params = mapOf("count" to rows.size.toString()),
                )
            }
    }
}

// ---------------------------------------------------------------------------------------------
// DEBT-01  Permission debt
// ---------------------------------------------------------------------------------------------

/**
 * A runtime permission has been held for [minDays] with zero observed use. Android's own
 * auto-reset only revokes permissions for apps that go entirely unused; this catches an unused
 * permission inside an app that is otherwise in daily use.
 *
 * Fails closed: without op-level sensing ([LedgerSlice.observingSinceMs] == null) silence means
 * "could not see", not "never used", so nothing fires.
 */
class Debt01PermissionDebt(private val config: RuleConfig) : Rule {

    override val id = config.id

    private val minDays = config.long("minDays", 30L)

    override fun evaluate(slice: LedgerSlice, inventory: AppInventory): List<Finding.PolicyViolation> {

        if (inventory.isSystemApp) return emptyList()

        val since = slice.observingSinceMs ?: return emptyList()
        if (slice.nowMs - since < minDays * DAY_MS) return emptyList()

        // Use before retention collapsed the raw rows still counts as use.
        val usedOps = slice.usedOps(inventory.packageName)

        return inventory.grantedPermissions
            .sorted()
            .mapNotNull { permission ->
                val ops = PermissionOps.opsFor(permission)
                if (ops.isEmpty() || ops.any { it in usedOps }) null else permission to ops.first()
            }
            .distinctBy { it.second }
            .map { (permission, op) ->
                Finding.PolicyViolation(
                    packageName = inventory.packageName,
                    ruleId = id,
                    title = "Holds ${opLabel(op)} permission it has not used",
                    explanation = "This app has been allowed ${opLabel(op)} but PermSense has seen no use " +
                            "of it in $minDays days or more. Removing it costs you nothing.",
                    citation = config.citation,
                    op = op,
                    basis = Finding.Basis.LEDGER,
                    evidence = emptyList(),
                    suggestedAction = SuggestedAction.RevokePermission(inventory.packageName, permission),
                    severity = config.severity,
                    params = mapOf("days" to minDays.toString()),
                )
            }
    }
}

// ---------------------------------------------------------------------------------------------
// NEW-01  New behaviour after an update
// ---------------------------------------------------------------------------------------------

/**
 * An op appears for the app for the first time ever, strictly after a version-code change that
 * is recorded in the ledger itself. Needs no baseline statistics.
 */
class New01NewBehaviourAfterUpdate(private val config: RuleConfig) : Rule {

    override val id = config.id

    override fun evaluate(slice: LedgerSlice, inventory: AppInventory): List<Finding.PolicyViolation> {

        if (inventory.isSystemApp) return emptyList()

        val rows = slice.rowsFor(inventory.packageName)

        // An op that was already used long ago, and is only summarised now, is not new.
        val aggregatedOps = rows
            .filter { it.recordKind == LedgerRecordKind.AGGREGATE.name }
            .map { it.op }
            .toSet()

        val versioned = rows
            .filter { it.recordKind == LedgerRecordKind.EPISODE.name && it.versionCode > 0L }

        if (versioned.map { it.versionCode }.distinct().size < 2) return emptyList()

        val firstByOp: Map<String, LedgerRecord> =
            versioned
                .filter {
                    it.isSuccessfulAccess() && it.op in Ops.SENSITIVE && it.isThirdPartyUid() &&
                            it.op !in aggregatedOps
                }
                .groupBy { it.op }
                .mapValues { (_, list) -> list.minWith(compareBy({ it.timestampMs }, { it.hash })) }

        return firstByOp.toSortedMap().mapNotNull { (op, first) ->

            val earlierOldVersion =
                versioned
                    .filter { it.timestampMs <= first.timestampMs && it.versionCode < first.versionCode }
                    .minWithOrNull(compareBy({ it.timestampMs }, { it.hash }))
                    ?: return@mapNotNull null

            Finding.PolicyViolation(
                packageName = inventory.packageName,
                ruleId = id,
                title = "New behaviour after an update: ${opLabel(op)}",
                explanation = "Before version ${first.versionCode} this app never used ${opLabel(op)}. " +
                        "It started right after it updated from version ${earlierOldVersion.versionCode}.",
                citation = config.citation,
                op = op,
                basis = Finding.Basis.LEDGER,
                evidence = listOf(earlierOldVersion, first),
                suggestedAction = actionFor(inventory.packageName, op, inventory),
                severity = config.severity,
                params = mapOf(
                    "fromVersion" to earlierOldVersion.versionCode.toString(),
                    "toVersion" to first.versionCode.toString(),
                ),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// RBI-01..05  Digital-lending pack
// ---------------------------------------------------------------------------------------------

/** Plain nouns for the four prohibited resources, for the English fallback text. */
private val RBI_LABELS = mapOf(
    "RBI-01" to "contacts",
    "RBI-02" to "call log",
    "RBI-03" to "files and media",
    "RBI-04" to "phone and messages",
)

/**
 * Shared shape of RBI-01..04: a prohibited resource is a violation per se. Recorded use is the
 * strongest evidence. Without it, the permission being requested or held already warrants a
 * pre-emptive warning, before the app has done anything.
 */
class RbiProhibitedResource(private val config: RuleConfig) : Rule {

    override val id = config.id

    private val ops = config.strings("ops", emptySet())
    private val permissions = config.strings("permissions", emptySet())
    private val label = RBI_LABELS[config.id] ?: "restricted data"

    override fun evaluate(slice: LedgerSlice, inventory: AppInventory): List<Finding.PolicyViolation> {

        if (TAG_LENDING !in inventory.policyTags) return emptyList()

        val used =
            slice.accessesFor(inventory.packageName)
                .filter { it.op in ops && it.isThirdPartyUid() }

        if (used.isNotEmpty()) {
            val op = used.first().op
            return listOf(
                Finding.PolicyViolation(
                    packageName = inventory.packageName,
                    ruleId = id,
                    title = "Lending app accessed your $label",
                    explanation = "This is tagged as a digital-lending app. Lending apps are not " +
                            "permitted to access $label, and it did $label access ${used.size} time(s).",
                    citation = config.citation,
                    op = null,
                    basis = Finding.Basis.LEDGER,
                    evidence = used,
                    suggestedAction = actionFor(inventory.packageName, op, inventory),
                    severity = config.severity,
                    params = mapOf("count" to used.size.toString(), "state" to "used"),
                ),
            )
        }

        val requested = permissions.filter { it in inventory.requestedPermissions }
        if (requested.isEmpty()) return emptyList()

        val granted = firstGranted(inventory, permissions)

        return listOf(
            Finding.PolicyViolation(
                packageName = inventory.packageName,
                ruleId = id,
                title = if (granted != null) "Lending app holds access to your $label" else
                    "Lending app asks for access to your $label",
                explanation = "This is tagged as a digital-lending app. Lending apps are not permitted to " +
                        "access $label, yet this one " +
                        (if (granted != null) "has been granted it." else "requests it.") +
                        " It has not used it yet.",
                citation = config.citation,
                op = null,
                basis = Finding.Basis.INVENTORY,
                evidence = emptyList(),
                suggestedAction =
                    if (granted != null) SuggestedAction.RevokePermission(inventory.packageName, granted)
                    else SuggestedAction.OpenAppSettings(inventory.packageName),
                // Holding the permission is the full severity; merely asking for it is one lower.
                severity = if (granted != null) config.severity else (config.severity - 1).coerceAtLeast(1),
                params = mapOf("state" to if (granted != null) "granted" else "requested"),
            ),
        )
    }
}

/**
 * RBI-05: camera, microphone and location are allowed for a lending app only inside a single
 * onboarding/KYC window. The window opens at the first such use PermSense observed and lasts
 * `onboardingWindowMinutes`; later use is a violation.
 *
 * Assumption to review: PermSense cannot know when onboarding really happened, so the first
 * observed use stands in for it. The window is configurable in the pack.
 */
class Rbi05OutsideOnboarding(private val config: RuleConfig) : Rule {

    override val id = config.id

    private val windowMs = config.long("onboardingWindowMinutes", 30L) * 60_000L
    private val ops = config.strings("ops", Ops.INDICATOR)

    override fun evaluate(slice: LedgerSlice, inventory: AppInventory): List<Finding.PolicyViolation> {

        if (TAG_LENDING !in inventory.policyTags) return emptyList()

        val rows =
            slice.accessesFor(inventory.packageName)
                .filter { it.op in ops && it.isThirdPartyUid() }
                .sortedWith(compareBy({ it.timestampMs }, { it.hash }))

        if (rows.isEmpty()) return emptyList()

        val windowEnd = rows.first().timestampMs + windowMs
        val late = rows.filter { it.timestampMs > windowEnd }
        if (late.isEmpty()) return emptyList()

        return listOf(
            Finding.PolicyViolation(
                packageName = inventory.packageName,
                ruleId = id,
                title = "Lending app used camera, microphone or location after onboarding",
                explanation = "Lending apps may use camera, microphone and location once, to onboard you. " +
                        "This one kept using them: ${late.size} time(s) after the first-use window.",
                citation = config.citation,
                op = null,
                basis = Finding.Basis.LEDGER,
                evidence = late,
                suggestedAction = actionFor(inventory.packageName, late.first().op, inventory),
                severity = config.severity,
                params = mapOf("count" to late.size.toString(), "windowMinutes" to (windowMs / 60_000L).toString()),
            ),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// SPY-01  High-power grant combination
// ---------------------------------------------------------------------------------------------

/**
 * An app that holds a screen-reading or device-control power (accessibility service, device admin,
 * notification listener) together with background location or SMS access: the combination the
 * source material calls "the stalkerware toolkit". Inventory only; needs no recorded access.
 *
 * Overlay ("draw over other apps") is not detected yet, so apps that combine overlay alone with
 * location are not covered.
 */
class Spy01HighPowerGrants(private val config: RuleConfig) : Rule {

    override val id = config.id

    private val sensitiveGrants = config.strings("sensitiveGrants", emptySet())

    override fun evaluate(slice: LedgerSlice, inventory: AppInventory): List<Finding.PolicyViolation> {

        if (inventory.isSystemApp) return emptyList()

        val powers = buildList {
            if (inventory.isAccessibilityEnabled) add("an accessibility service")
            if (inventory.isDeviceAdmin) add("device admin")
            if (inventory.isNotificationListener) add("notification access")
        }
        if (powers.isEmpty()) return emptyList()

        val sensitive = sensitiveGrants.sorted().filter { it in inventory.grantedPermissions }
        if (sensitive.isEmpty()) return emptyList()

        val what = buildList {
            if ("android.permission.ACCESS_BACKGROUND_LOCATION" in sensitive) add("background location")
            if (sensitive.any { it.endsWith("SMS") }) add("SMS")
        }.ifEmpty { listOf("sensitive data") }

        return listOf(
            Finding.PolicyViolation(
                packageName = inventory.packageName,
                ruleId = id,
                title = "Powerful access combination",
                explanation = "This app has ${powers.joinToString(" and ")} together with ${what.joinToString(" and ")}. " +
                        "Together these let an app watch your screen, messages and movements without you noticing.",
                citation = config.citation,
                op = null,
                basis = Finding.Basis.INVENTORY,
                evidence = emptyList(),
                suggestedAction = SuggestedAction.OpenAppSettings(inventory.packageName),
                severity = config.severity,
                params = mapOf(
                    "accessibility" to inventory.isAccessibilityEnabled.toString(),
                    "deviceAdmin" to inventory.isDeviceAdmin.toString(),
                    "notificationListener" to inventory.isNotificationListener.toString(),
                    "backgroundLocation" to ("android.permission.ACCESS_BACKGROUND_LOCATION" in sensitive).toString(),
                    "sms" to sensitive.any { it.endsWith("SMS") }.toString(),
                ),
            ),
        )
    }
}

/** Plain-language name of an op, for finding text. */
fun opLabel(op: String): String =
    when (op) {
        Ops.CONTACTS -> "contacts"
        Ops.CALL_LOG -> "call log"
        Ops.SMS -> "text messages"
        Ops.PHONE_STATE -> "phone state"
        Ops.CLIPBOARD -> "clipboard"
        Ops.CAMERA -> "camera"
        Ops.MIC -> "microphone"
        Ops.FINE_LOC -> "precise location"
        Ops.COARSE_LOC -> "approximate location"
        Ops.MEDIA_IMAGES -> "photos"
        Ops.MEDIA_VIDEO -> "videos"
        Ops.MEDIA_AUDIO -> "audio files"
        Ops.EXT_STORAGE -> "files"
        else -> op.removePrefix("android:").replace('_', ' ')
    }
