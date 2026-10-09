package com.ojas.permsense.remediation

import com.ojas.permsense.ledger.ChainVerification
import com.ojas.permsense.ledger.LedgerRecordKind
import com.ojas.permsense.ledger.LedgerWriter
import com.ojas.permsense.model.Ops
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemediationTest {

    private val self = "com.ojas.permsense"
    private val victim = "com.example.lender"
    private val contacts = "android.permission.READ_CONTACTS"

    /** A phone's worth of state, with switches for the ways the real thing misbehaves. */
    private class FakePhone : PrivilegedChannel {

        val granted = HashMap<Pair<String, String>, Boolean>()
        val modes = HashMap<Pair<String, String>, Int>()

        var executed = 0
        var silentlyIgnore = false
        var refuse = false
        var failToRead = false
        var onExecute: () -> Unit = {}

        override fun readState(request: RemediationRequest): AppState {
            if (failToRead) throw RemediationException("the OS did not answer")
            val key = request.packageName to request.target
            return when (request.kind) {
                RemediationKind.SET_OP_MODE -> AppState(opMode = modes[key] ?: AppOpModes.DEFAULT)
                else -> AppState(permissionGranted = granted[key] ?: false)
            }
        }

        override fun execute(request: RemediationRequest) {
            executed++
            onExecute()
            if (refuse) throw RemediationException("the OS refused")
            if (silentlyIgnore) return
            val key = request.packageName to request.target
            when (request.kind) {
                RemediationKind.REVOKE_PERMISSION -> granted[key] = false
                RemediationKind.GRANT_PERMISSION -> granted[key] = true
                RemediationKind.SET_OP_MODE -> modes[key] = request.mode!!
            }
        }
    }

    private fun setup(): Triple<FakePhone, LedgerWriter, RemediationExecutor> {
        val phone = FakePhone().also { it.granted[victim to contacts] = true }
        val ledger = LedgerWriter()
        var tick = 1_000L
        val executor = RemediationExecutor(
            channel = phone,
            ledger = ledger,
            selfPackage = self,
            isRemediable = { it != "android" && !it.startsWith("com.android.") },
            clock = { tick++ },
        )
        return Triple(phone, ledger, executor)
    }

    private val revoke = RemediationRequest(RemediationKind.REVOKE_PERMISSION, victim, contacts)

    // ------------------------------------------------------------------ the happy path

    @Test
    fun revokingAPermissionChangesItAndReadsItBack() {
        val (phone, _, executor) = setup()

        val outcome = executor.execute(revoke, uid = 10200)

        assertTrue(outcome is RemediationOutcome.Done)
        outcome as RemediationOutcome.Done
        assertEquals(true, outcome.prior.permissionGranted)
        assertEquals(false, outcome.now.permissionGranted)
        assertEquals(false, phone.granted[victim to contacts])
    }

    @Test
    fun theChangeIsRecordedInTheLedgerAsIntentThenResult() {
        val (_, ledger, executor) = setup()

        executor.execute(revoke, uid = 10200)

        val rows = ledger.all().filter { it.recordKind == LedgerRecordKind.REMEDIATION.name }
        assertEquals(2, rows.size)
        assertEquals("PENDING", rows[0].accessResult)
        assertTrue(rows[0].attributionTag!!.contains("phase=INTENT"))
        assertTrue(rows[0].attributionTag!!.contains("prior=GRANTED"))
        assertEquals("SUCCESS", rows[1].accessResult)
        assertTrue(rows[1].attributionTag!!.contains("phase=RESULT"))
        assertTrue(rows[1].attributionTag!!.contains("new=REVOKED"))
        assertEquals(victim, rows[1].packageName)
        assertEquals(contacts, rows[1].op)
        assertTrue(ledger.verify() is ChainVerification.Valid)
    }

    @Test
    fun theUndoRecordIsWrittenBeforeAnythingIsChanged() {
        val (phone, ledger, executor) = setup()
        var rowsWhenTheChangeWasMade = -1
        phone.onExecute = { rowsWhenTheChangeWasMade = ledger.all().count { it.recordKind == LedgerRecordKind.REMEDIATION.name } }

        executor.execute(revoke, uid = 10200)

        // If the process died right here, the ledger would already say what was about to happen.
        assertEquals(1, rowsWhenTheChangeWasMade)
    }

    // ------------------------------------------------------------------ verify, don't trust

    @Test
    fun aChangeTheOsSilentlyIgnoredIsNotReportedAsSuccess() {
        val (phone, ledger, executor) = setup()
        phone.silentlyIgnore = true

        val outcome = executor.execute(revoke, uid = 10200)

        assertTrue(outcome is RemediationOutcome.NotApplied)
        assertEquals(true, (outcome as RemediationOutcome.NotApplied).observed.permissionGranted)
        assertEquals("NOT_APPLIED", ledger.all().last().accessResult)
        assertTrue(executor.undoLog().isEmpty())
    }

    @Test
    fun aRefusalIsAFailureAndIsRecorded() {
        val (phone, ledger, executor) = setup()
        phone.refuse = true

        val outcome = executor.execute(revoke, uid = 10200)

        assertTrue(outcome is RemediationOutcome.Failed)
        assertEquals("FAILED", ledger.all().last().accessResult)
        assertTrue(executor.undoLog().isEmpty())
    }

    @Test
    fun ifTheStateCannotBeReadNothingIsChanged() {
        val (phone, ledger, executor) = setup()
        phone.failToRead = true

        val outcome = executor.execute(revoke, uid = 10200)

        assertTrue(outcome is RemediationOutcome.Failed)
        assertEquals(0, phone.executed)
        assertEquals(0, ledger.all().size)
    }

    @Test
    fun asking_forWhatIsAlreadyTrueChangesNothing() {
        val (phone, ledger, executor) = setup()
        phone.granted[victim to contacts] = false

        val outcome = executor.execute(revoke, uid = 10200)

        assertTrue(outcome is RemediationOutcome.AlreadyInPlace)
        assertEquals(0, phone.executed)
        assertEquals(0, ledger.all().size)
    }

    // ------------------------------------------------------------------ the allow-list

    @Test
    fun aRequestOutsideTheAllowListNeverReachesThePhone() {
        val (phone, ledger, executor) = setup()
        val outside = listOf(
            RemediationRequest(RemediationKind.REVOKE_PERMISSION, victim, "android.permission.INTERNET"),
            RemediationRequest(RemediationKind.REVOKE_PERMISSION, victim, "android.permission.DUMP"),
            RemediationRequest(RemediationKind.REVOKE_PERMISSION, victim, "; rm -rf /"),
            RemediationRequest(RemediationKind.REVOKE_PERMISSION, "com.android.settings", contacts),
            RemediationRequest(RemediationKind.REVOKE_PERMISSION, self, contacts),
            RemediationRequest(RemediationKind.REVOKE_PERMISSION, "not a package", contacts),
            RemediationRequest(RemediationKind.REVOKE_PERMISSION, victim, contacts, userId = 10),
            RemediationRequest(RemediationKind.SET_OP_MODE, victim, "android:wake_lock", mode = AppOpModes.IGNORED),
            RemediationRequest(RemediationKind.SET_OP_MODE, victim, Ops.CAMERA, mode = AppOpModes.ERRORED),
            RemediationRequest(RemediationKind.SET_OP_MODE, victim, Ops.CONTACTS, mode = AppOpModes.FOREGROUND),
            RemediationRequest(RemediationKind.SET_OP_MODE, victim, Ops.CAMERA, mode = null),
            RemediationRequest(RemediationKind.SET_OP_MODE, victim, Ops.CAMERA, mode = 99),
            RemediationRequest(RemediationKind.REVOKE_PERMISSION, victim, contacts, mode = 1),
        )

        for (request in outside) {
            val outcome = executor.execute(request, uid = 10200)
            assertTrue("$request -> $outcome", outcome is RemediationOutcome.Rejected)
        }

        assertEquals(0, phone.executed)
        assertEquals(0, ledger.all().size)
    }

    @Test
    fun theAllowListAcceptsTheShapesItIsMeantFor() {
        val ok = { _: String -> true }

        for (permission in RemediationPolicy.PERMISSIONS) {
            assertEquals(
                Validation.Ok,
                RemediationPolicy.validate(RemediationRequest(RemediationKind.REVOKE_PERMISSION, victim, permission), self, ok),
            )
        }
        for (op in RemediationPolicy.FOREGROUND_CAPABLE_OPS) {
            assertEquals(
                Validation.Ok,
                RemediationPolicy.validate(RemediationRequest(RemediationKind.SET_OP_MODE, victim, op, AppOpModes.FOREGROUND), self, ok),
            )
        }
        assertEquals(
            Validation.Ok,
            RemediationPolicy.validate(RemediationRequest(RemediationKind.SET_OP_MODE, victim, Ops.CONTACTS, AppOpModes.IGNORED), self, ok),
        )
    }

    @Test
    fun theRevocablePermissionsAreExactlyTheRuntimePermissionsPermSenseKnowsAbout() {
        assertTrue("android.permission.READ_CONTACTS" in RemediationPolicy.PERMISSIONS)
        assertTrue("android.permission.CAMERA" in RemediationPolicy.PERMISSIONS)
        assertFalse("android.permission.INTERNET" in RemediationPolicy.PERMISSIONS)
        assertFalse("android.permission.GET_APP_OPS_STATS" in RemediationPolicy.PERMISSIONS)
    }

    @Test
    fun veryLongNamesAreRejectedWithoutBeingEchoedInFull() {
        val long = "x".repeat(5_000)
        val v = RemediationPolicy.validate(
            RemediationRequest(RemediationKind.REVOKE_PERMISSION, victim, long), self, { true },
        ) as Validation.Rejected

        assertTrue(v.reason.length < 400)
    }

    // ------------------------------------------------------------------ op modes

    @Test
    fun anOpCanBeRestrictedToForegroundUseAndReadBack() {
        val (phone, _, executor) = setup()
        val request = RemediationRequest(RemediationKind.SET_OP_MODE, victim, Ops.FINE_LOC, AppOpModes.FOREGROUND)

        val outcome = executor.execute(request, uid = 10200)

        assertTrue(outcome is RemediationOutcome.Done)
        assertEquals(AppOpModes.FOREGROUND, phone.modes[victim to Ops.FINE_LOC])
    }

    // ------------------------------------------------------------------ undo

    @Test
    fun aRevokeCanBeUndoneOnce() {
        val (phone, ledger, executor) = setup()
        executor.execute(revoke, uid = 10200)

        val entry = executor.undoLog().single()
        assertEquals(RemediationKind.REVOKE_PERMISSION, entry.kind)
        assertEquals("GRANTED", entry.priorState)

        val outcome = executor.undo(entry)

        assertTrue(outcome is RemediationOutcome.Done)
        assertEquals(true, phone.granted[victim to contacts])
        assertTrue(executor.undoLog().isEmpty())
        assertTrue(ledger.verify() is ChainVerification.Valid)

        // And not twice.
        assertTrue(executor.undo(entry) is RemediationOutcome.Rejected)
    }

    @Test
    fun theUndoIsItselfInTheLedgerAndPointsAtWhatItReverses() {
        val (_, ledger, executor) = setup()
        executor.execute(revoke, uid = 10200)
        val entry = executor.undoLog().single()

        executor.undo(entry)

        val undoRows = ledger.all().filter { Tag.parse(it.attributionTag)["undoes"] != null }
        assertEquals(2, undoRows.size)
        assertTrue(undoRows.all { Tag.parse(it.attributionTag)["undoes"] == entry.resultRowHash })
        assertEquals(RemediationKind.GRANT_PERMISSION.name, undoRows.last().accessKind)
    }

    @Test
    fun aModeChangeIsUndoneToTheModeItReplaced() {
        val (phone, _, executor) = setup()
        phone.modes[victim to Ops.CAMERA] = AppOpModes.ALLOWED

        executor.execute(RemediationRequest(RemediationKind.SET_OP_MODE, victim, Ops.CAMERA, AppOpModes.IGNORED), 10200)
        assertEquals(AppOpModes.IGNORED, phone.modes[victim to Ops.CAMERA])

        executor.undo(executor.undoLog().single())

        assertEquals(AppOpModes.ALLOWED, phone.modes[victim to Ops.CAMERA])
    }

    @Test
    fun aFailedUndoLeavesTheOriginalChangeUndoable() {
        val (phone, _, executor) = setup()
        executor.execute(revoke, uid = 10200)
        val entry = executor.undoLog().single()

        phone.refuse = true
        assertTrue(executor.undo(entry) is RemediationOutcome.Failed)
        phone.refuse = false

        assertEquals(listOf(entry), executor.undoLog())
        assertTrue(executor.undo(entry) is RemediationOutcome.Done)
    }

    @Test
    fun onlyConfirmedChangesAppearInTheUndoLog() {
        val (phone, _, executor) = setup()
        phone.silentlyIgnore = true
        executor.execute(revoke, uid = 10200)
        phone.silentlyIgnore = false
        phone.refuse = true
        executor.execute(revoke, uid = 10200)

        assertTrue(executor.undoLog().isEmpty())
    }

    @Test
    fun theUndoLogSurvivesARestartBecauseItLivesInTheLedger() {
        val storage = com.ojas.permsense.ledger.InMemoryLedgerStorage()
        val phone = FakePhone().also { it.granted[victim to contacts] = true }
        val first = RemediationExecutor(phone, LedgerWriter(storage), self, { true })
        first.execute(revoke, uid = 10200)

        val second = RemediationExecutor(phone, LedgerWriter(storage), self, { true })

        assertNotNull(second.undoLog().singleOrNull())
    }

    @Test
    fun anUndoWithNothingToRestoreIsRefused() {
        val grant = UndoEntry("h", 1, victim, 1, RemediationKind.GRANT_PERMISSION, contacts, "REVOKED", "GRANTED")
        val revokeOfNonGranted = UndoEntry("h", 1, victim, 1, RemediationKind.REVOKE_PERMISSION, contacts, "UNKNOWN", "REVOKED")

        assertNull(grant.inverse())
        assertNull(revokeOfNonGranted.inverse())
    }

    // ------------------------------------------------------------------ who may call

    @Test
    fun aCallFromAnotherAppIsRefused() {
        // A deliberately hostile app with its own uid tries to reach the privileged process.
        assertFalse(CallerPolicy.isAllowed(callingUid = 10555, serviceUid = 2000, callerPackages = listOf("com.evil.app"), selfPackage = self))
        assertFalse(CallerPolicy.isAllowed(10555, 2000, listOf("com.evil.app", "com.evil.helper"), self))
    }

    @Test
    fun aCallerWhoseOwnerCannotBeFoundIsRefused() {
        assertFalse(CallerPolicy.isAllowed(10555, 2000, null, self))
        assertFalse(CallerPolicy.isAllowed(10555, 2000, emptyList(), self))
    }

    @Test
    fun permSenseItselfIsAllowed() {
        assertTrue(CallerPolicy.isAllowed(10321, 2000, listOf(self), self))
    }

    @Test
    fun theServiceProcessAndRootAreAllowed() {
        assertTrue(CallerPolicy.isAllowed(2000, 2000, null, self))
        assertTrue(CallerPolicy.isAllowed(0, 2000, null, self))
    }

    @Test
    fun aLookAlikePackageNameIsNotPermSense() {
        assertFalse(CallerPolicy.isAllowed(10555, 2000, listOf("com.ojas.permsense.evil", "com.ojas.permsens"), self))
    }
}
