package com.ojas.permsense.remediation

import com.ojas.permsense.judgement.PermissionOps
import com.ojas.permsense.ledger.LedgerRecord
import com.ojas.permsense.ledger.LedgerRecordKind
import com.ojas.permsense.ledger.LedgerWriter
import com.ojas.permsense.model.Ops

/** AppOpsManager.MODE_*: what the OS does when an app asks to use an op. */
object AppOpModes {
    const val ALLOWED = 0
    const val IGNORED = 1
    const val ERRORED = 2
    const val DEFAULT = 3
    const val FOREGROUND = 4

    fun name(mode: Int?): String =
        when (mode) {
            null -> "UNKNOWN"
            ALLOWED -> "ALLOWED"
            IGNORED -> "IGNORED"
            ERRORED -> "ERRORED"
            DEFAULT -> "DEFAULT"
            FOREGROUND -> "FOREGROUND"
            else -> "MODE_$mode"
        }

    fun fromName(name: String): Int? =
        when (name) {
            "ALLOWED" -> ALLOWED
            "IGNORED" -> IGNORED
            "ERRORED" -> ERRORED
            "DEFAULT" -> DEFAULT
            "FOREGROUND" -> FOREGROUND
            else -> null
        }
}

enum class RemediationKind {
    REVOKE_PERMISSION,
    GRANT_PERMISSION,
    SET_OP_MODE,
}

/**
 * The only two shapes of change PermSense will ever ask the privileged process to make, plus the
 * grant that undoes a revoke. There is no way to express anything else.
 */
data class RemediationRequest(
    val kind: RemediationKind,
    val packageName: String,
    /** The permission name for a revoke or grant, or the op name for a mode change. */
    val target: String,
    /** The mode to set, for [RemediationKind.SET_OP_MODE]. */
    val mode: Int? = null,
    val userId: Int = 0,
)

/** What the OS says right now about one app and one permission or op. Null means not asked. */
data class AppState(
    val permissionGranted: Boolean? = null,
    val opMode: Int? = null,
) {
    fun describe(kind: RemediationKind): String =
        when (kind) {
            RemediationKind.SET_OP_MODE -> AppOpModes.name(opMode)
            else -> when (permissionGranted) {
                true -> "GRANTED"
                false -> "REVOKED"
                null -> "UNKNOWN"
            }
        }
}

sealed interface Validation {
    data object Ok : Validation
    data class Rejected(val reason: String) : Validation
}

/**
 * The allow-list. Applied by the app before it asks, and again by the privileged process before
 * it acts: the process that runs as shell must not trust whoever is on the other end of the call
 * to have checked.
 */
object RemediationPolicy {

    private val PACKAGE = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$""")

    const val MAX_NAME_LENGTH = 200

    /** Ops that support "foreground only": the app keeps access while it is on screen. */
    val FOREGROUND_CAPABLE_OPS: Set<String> = setOf(Ops.CAMERA, Ops.MIC, Ops.FINE_LOC, Ops.COARSE_LOC)

    /** The runtime permissions a revoke or grant may name. */
    val PERMISSIONS: Set<String> = PermissionOps.opsByPermission.keys

    fun validate(
        request: RemediationRequest,
        selfPackage: String,
        isRemediable: (String) -> Boolean,
    ): Validation {

        val pkg = request.packageName

        if (pkg.length > MAX_NAME_LENGTH || !PACKAGE.matches(pkg)) {
            return Validation.Rejected("\"$pkg\" is not a package name")
        }
        if (pkg == selfPackage) {
            return Validation.Rejected("PermSense does not change its own permissions this way")
        }
        if (request.userId != 0) {
            return Validation.Rejected("Only the primary user's apps can be changed")
        }
        if (!isRemediable(pkg)) {
            return Validation.Rejected("$pkg is a system app and cannot be changed from here")
        }

        return when (request.kind) {

            RemediationKind.REVOKE_PERMISSION, RemediationKind.GRANT_PERMISSION -> {
                if (request.target !in PERMISSIONS) {
                    Validation.Rejected("\"${request.target.take(MAX_NAME_LENGTH)}\" is not a permission PermSense may change")
                } else if (request.mode != null) {
                    Validation.Rejected("A permission change takes no mode")
                } else {
                    Validation.Ok
                }
            }

            RemediationKind.SET_OP_MODE -> {
                val mode = request.mode
                when {
                    request.target !in Ops.SENSITIVE ->
                        Validation.Rejected("\"${request.target.take(MAX_NAME_LENGTH)}\" is not an op PermSense may change")
                    mode == null ->
                        Validation.Rejected("A mode change needs a mode")
                    mode == AppOpModes.FOREGROUND && request.target !in FOREGROUND_CAPABLE_OPS ->
                        Validation.Rejected("${request.target} has no foreground-only mode")
                    // ERRORED makes apps throw; ALLOWED is only a way to undo, never a goal.
                    mode !in setOf(AppOpModes.IGNORED, AppOpModes.FOREGROUND, AppOpModes.ALLOWED, AppOpModes.DEFAULT) ->
                        Validation.Rejected("Mode ${AppOpModes.name(mode)} is not permitted")
                    else -> Validation.Ok
                }
            }
        }
    }
}

/**
 * Who may talk to the privileged process. Anything that reaches its Binder at all is talking to a
 * shell-level identity, so the caller is checked on every transaction, not once at connect time.
 */
object CallerPolicy {

    private const val ROOT_UID = 0

    /**
     * @param callingUid the uid Binder reports for the caller
     * @param serviceUid the uid the privileged process itself runs as
     * @param callerPackages the packages that own [callingUid]; null if they could not be found
     * @param selfPackage PermSense's own package name
     */
    fun isAllowed(
        callingUid: Int,
        serviceUid: Int,
        callerPackages: Collection<String>?,
        selfPackage: String,
    ): Boolean {

        if (callingUid == serviceUid || callingUid == ROOT_UID) return true

        // Fails closed: if the owner of the uid cannot be established, the call is refused.
        return callerPackages?.contains(selfPackage) == true
    }
}

/** The OS-facing half, implemented over Shizuku on a device and by a fake in tests. */
interface PrivilegedChannel {

    @Throws(RemediationException::class)
    fun readState(request: RemediationRequest): AppState

    @Throws(RemediationException::class)
    fun execute(request: RemediationRequest)
}

class RemediationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** One change that was made and can be taken back. */
data class UndoEntry(
    val resultRowHash: String,
    val timestampMs: Long,
    val packageName: String,
    val uid: Int,
    val kind: RemediationKind,
    val target: String,
    /** State before the change, as written in the ledger. */
    val priorState: String,
    val newState: String,
) {

    /** The request that restores the earlier state, or null when this change cannot be undone. */
    fun inverse(): RemediationRequest? =
        when (kind) {
            RemediationKind.REVOKE_PERMISSION ->
                if (priorState == "GRANTED") RemediationRequest(RemediationKind.GRANT_PERMISSION, packageName, target) else null

            RemediationKind.SET_OP_MODE ->
                AppOpModes.fromName(priorState)?.let {
                    RemediationRequest(RemediationKind.SET_OP_MODE, packageName, target, mode = it)
                }

            RemediationKind.GRANT_PERMISSION -> null
        }
}

sealed interface RemediationOutcome {

    /** The change was made and the OS now reports it. */
    data class Done(val prior: AppState, val now: AppState) : RemediationOutcome

    /** The app was already in the requested state, so nothing was changed. */
    data class AlreadyInPlace(val state: AppState) : RemediationOutcome

    /** The request was outside the allow-list. Nothing was read or changed. */
    data class Rejected(val reason: String) : RemediationOutcome

    /** The OS could not be asked, or refused. */
    data class Failed(val reason: String, val prior: AppState?) : RemediationOutcome

    /**
     * The call returned normally but the OS still reports the old state. The same shape as the
     * silent empty reply that makes getHistoricalOps dangerous: success must be read back, never
     * assumed from a return value.
     */
    data class NotApplied(val prior: AppState, val observed: AppState) : RemediationOutcome
}

/**
 * Changes an app's permissions, safely:
 *  1. only allow-listed requests are accepted;
 *  2. the prior state is written to the ledger BEFORE anything is changed, so the undo record
 *     exists even if the change then crashes the process;
 *  3. the result is read back from the OS, not trusted from the call.
 * Every step lands in the same hash-chained ledger as the observations, so a fix is evidence too.
 */
class RemediationExecutor(
    private val channel: PrivilegedChannel,
    private val ledger: LedgerWriter,
    private val selfPackage: String,
    private val isRemediable: (String) -> Boolean,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    fun execute(request: RemediationRequest, uid: Int): RemediationOutcome = run(request, uid, undoes = null)

    /** Changes that succeeded and have not been taken back, newest first. */
    fun undoLog(): List<UndoEntry> {

        val rows = ledger.all().filter { it.recordKind == LedgerRecordKind.REMEDIATION.name }

        // A change counts as taken back only when the undo itself was confirmed. A failed attempt
        // to undo leaves the original change still undoable.
        val undone = rows
            .filter { Tag.parse(it.attributionTag)["phase"] == "RESULT" && it.accessResult == "SUCCESS" }
            .mapNotNull { Tag.parse(it.attributionTag)["undoes"] }
            .toSet()

        return rows
            .filter { Tag.parse(it.attributionTag)["phase"] == "RESULT" && it.accessResult == "SUCCESS" }
            .filter { Tag.parse(it.attributionTag)["undoes"] == null }
            .filter { it.hash !in undone }
            .mapNotNull { toEntry(it) }
            .filter { it.inverse() != null }
            .sortedByDescending { it.timestampMs }
    }

    fun undo(entry: UndoEntry): RemediationOutcome {
        val inverse = entry.inverse() ?: return RemediationOutcome.Rejected("This change cannot be undone")
        if (entry !in undoLog()) return RemediationOutcome.Rejected("This change was already undone")
        return run(inverse, entry.uid, undoes = entry.resultRowHash)
    }

    private fun run(request: RemediationRequest, uid: Int, undoes: String?): RemediationOutcome {

        when (val v = RemediationPolicy.validate(request, selfPackage, isRemediable)) {
            is Validation.Rejected -> return RemediationOutcome.Rejected(v.reason)
            Validation.Ok -> Unit
        }

        val prior = try {
            channel.readState(request)
        } catch (e: RemediationException) {
            return RemediationOutcome.Failed("Could not read the current state: ${e.message}", null)
        }

        if (satisfied(request, prior)) {
            return RemediationOutcome.AlreadyInPlace(prior)
        }

        val action = request.kind.name
        val priorText = prior.describe(request.kind)

        // Written first. If anything below dies, the ledger still says what was about to happen.
        record(request, uid, action, "INTENT", priorText, "PENDING", "PENDING", undoes)

        try {
            channel.execute(request)
        } catch (e: RemediationException) {
            record(request, uid, action, "RESULT", priorText, priorText, "FAILED", undoes)
            return RemediationOutcome.Failed(e.message ?: "The change was refused", prior)
        }

        val now = try {
            channel.readState(request)
        } catch (e: RemediationException) {
            record(request, uid, action, "RESULT", priorText, "UNKNOWN", "FAILED", undoes)
            return RemediationOutcome.Failed("The change could not be confirmed: ${e.message}", prior)
        }

        return if (satisfied(request, now)) {
            record(request, uid, action, "RESULT", priorText, now.describe(request.kind), "SUCCESS", undoes)
            RemediationOutcome.Done(prior, now)
        } else {
            record(request, uid, action, "RESULT", priorText, now.describe(request.kind), "NOT_APPLIED", undoes)
            RemediationOutcome.NotApplied(prior, now)
        }
    }

    private fun satisfied(request: RemediationRequest, state: AppState): Boolean =
        when (request.kind) {
            RemediationKind.REVOKE_PERMISSION -> state.permissionGranted == false
            RemediationKind.GRANT_PERMISSION -> state.permissionGranted == true
            RemediationKind.SET_OP_MODE -> state.opMode == request.mode
        }

    private fun record(
        request: RemediationRequest,
        uid: Int,
        action: String,
        phase: String,
        prior: String,
        now: String,
        result: String,
        undoes: String?,
    ) {
        ledger.recordRemediation(
            targetPackage = request.packageName,
            uid = uid,
            opOrPermission = request.target,
            action = action,
            priorState = prior,
            newState = now,
            success = result == "SUCCESS",
            timestampMs = clock(),
            phase = phase,
            result = result,
            undoes = undoes,
        )
    }

    private fun toEntry(row: LedgerRecord): UndoEntry? {
        val tag = Tag.parse(row.attributionTag)
        val kind = RemediationKind.values().firstOrNull { it.name == row.accessKind } ?: return null
        return UndoEntry(
            resultRowHash = row.hash,
            timestampMs = row.timestampMs,
            packageName = row.packageName,
            uid = row.uid,
            kind = kind,
            target = row.op,
            priorState = tag["prior"] ?: return null,
            newState = tag["new"] ?: return null,
        )
    }
}

/** The ledger stores a remediation's details in the attribution field as `key=value;key=value`. */
object Tag {

    fun parse(text: String?): Map<String, String> =
        text.orEmpty()
            .split(';')
            .filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

    fun build(vararg pairs: Pair<String, String?>): String =
        pairs.filter { it.second != null }.joinToString(";") { "${it.first}=${it.second}" }
}
