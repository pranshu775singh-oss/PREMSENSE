package com.ojas.permsense.judgement

import com.ojas.permsense.findings.Finding
import com.ojas.permsense.findings.FindingRanking
import com.ojas.permsense.judgement.Fx.BASE
import com.ojas.permsense.judgement.Fx.DAY
import com.ojas.permsense.judgement.Fx.HOUR
import com.ojas.permsense.judgement.Fx.MIN
import com.ojas.permsense.ledger.ChainVerification
import com.ojas.permsense.model.Ops
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 24-hour demo from section 25 of the spec, replayed offline:
 *
 *   QuickCash v1: camera used while on screen during onboarding.
 *   QuickCash v2: reads contacts from a background job with the screen off.
 *
 * Expected: RBI-01, INV-01 and NEW-01 for the contacts access, nothing alarming for v1.
 */
class QuickCashDemoTest {

    private val P = "android.permission."
    private val requested = listOf(P + "READ_CONTACTS", P + "CAMERA")

    private val v1 = Fx.app(version = 1, lastUpdate = BASE, requested = requested, granted = listOf(P + "CAMERA"))
    private val v2 = Fx.app(version = 2, lastUpdate = BASE + DAY, requested = requested, granted = requested)

    private val onboardingStart = BASE + 10 * MIN
    private val contactsAt = BASE + DAY + 3 * HOUR

    private val usage = Fx.usage(
        Fx.screenOn(BASE + 1),
        Fx.resumed(onboardingStart - MIN),
        Fx.paused(onboardingStart + 5 * MIN),
        Fx.screenOff(BASE + DAY + HOUR),
        to = BASE + 3 * DAY,
    )

    private val cameraV1 = Fx.obs("cam-1", onboardingStart, Ops.CAMERA)
    private val contactsV2 = Fx.obs("con-1", contactsAt, Ops.CONTACTS)

    private fun audit(
        device: Fx.Device,
        current: com.ojas.permsense.model.AppInventory,
        now: Long,
    ) = com.ojas.permsense.judgement.Auditor().audit(device.rows, listOf(current), now, observingSinceMs = BASE)

    @Test
    fun step1_t0_inventory_warns_before_the_app_has_done_anything() {
        val device = Fx.Device()
        device.ingest(emptyList(), usage, listOf(v1), nowMs = BASE + 5 * MIN)

        val findings = audit(device, v1, BASE + 5 * MIN)

        val rbi = findings.filterIsInstance<Finding.PolicyViolation>().single()
        assertEquals("RBI-01", rbi.ruleId)
        assertEquals(Finding.Basis.INVENTORY, rbi.basis)
        assertTrue(rbi.evidence.isEmpty())
    }

    @Test
    fun step2_v1_onboarding_camera_use_is_clean() {
        val device = Fx.Device()
        device.ingest(listOf(cameraV1), usage, listOf(v1), nowMs = BASE + HOUR)

        val findings = audit(device, v1, BASE + HOUR).filterIsInstance<Finding.PolicyViolation>()

        assertEquals(listOf("RBI-01"), findings.map { it.ruleId })
        assertTrue("no INV-01 / RBI-05 / NEW-01 for on-screen onboarding", findings.none { it.evidence.isNotEmpty() })
    }

    @Test
    fun step3_and_4_v2_background_contacts_read_raises_three_linked_findings() {
        val device = Fx.Device()
        device.ingest(listOf(cameraV1, contactsV2), usage, listOf(v1, v2), nowMs = BASE + 2 * DAY)

        val findings = audit(device, v2, BASE + 2 * DAY).filterIsInstance<Finding.PolicyViolation>()
        val byRule = findings.associateBy { it.ruleId }

        assertEquals(setOf("RBI-01", "INV-01", "NEW-01"), byRule.keys)

        val rbi = byRule.getValue("RBI-01")
        assertEquals(Finding.Basis.LEDGER, rbi.basis)
        assertEquals(contactsAt, rbi.evidence.single().timestampMs)

        val inv = byRule.getValue("INV-01")
        assertEquals("SCREEN_OFF", inv.evidence.single().visibilityStratum)
        assertEquals(Ops.CONTACTS, inv.op)

        val new = byRule.getValue("NEW-01")
        assertEquals(listOf(1L, 2L), new.evidence.map { it.versionCode })

        // All three point at the same contacts access, so the UI can present them as one story.
        val contactsRow = inv.evidence.single()
        assertEquals(contactsRow, rbi.evidence.single())
        assertEquals(contactsRow, new.evidence.last())
    }

    @Test
    fun the_ledger_behind_the_demo_is_a_valid_hash_chain() {
        val device = Fx.Device()
        device.ingest(listOf(cameraV1, contactsV2), usage, listOf(v1, v2), nowMs = BASE + 2 * DAY)

        assertEquals(2, device.rows.size)
        assertEquals(ChainVerification.Valid, device.ledger.verify())
    }

    @Test
    fun replaying_the_same_batch_twice_leaves_the_ledger_byte_identical() {
        val device = Fx.Device()
        device.ingest(listOf(cameraV1, contactsV2), usage, listOf(v1, v2), nowMs = BASE + 2 * DAY)
        val before = device.rows.map { it.hash }

        device.ingest(listOf(cameraV1, contactsV2), usage, listOf(v1, v2), nowMs = BASE + 2 * DAY + MIN)

        assertEquals(before, device.rows.map { it.hash })
    }

    @Test
    fun input_order_does_not_change_the_ledger() {
        val a = Fx.Device()
        a.ingest(listOf(cameraV1, contactsV2), usage, listOf(v1, v2), nowMs = BASE + 2 * DAY)

        val b = Fx.Device()
        b.ingest(listOf(contactsV2, cameraV1), usage, listOf(v2, v1), nowMs = BASE + 2 * DAY)

        assertEquals(a.rows.map { it.hash }, b.rows.map { it.hash })
    }

    @Test
    fun findings_rank_critical_first_and_never_drop_a_critical_one_to_the_budget() {
        val device = Fx.Device()
        device.ingest(listOf(cameraV1, contactsV2), usage, listOf(v1, v2), nowMs = BASE + 2 * DAY)

        val ranked = audit(device, v2, BASE + 2 * DAY)
        assertTrue(ranked.zipWithNext().all { (x, y) -> x.severity >= y.severity })

        val budgeted = FindingRanking.applyBudget(ranked, maxNonCritical = 0)
        assertEquals(ranked.count { it.severity >= 3 }, budgeted.size)
        assertTrue(budgeted.all { it.severity >= 3 })
    }

    @Test
    fun duplicate_findings_collapse_to_the_strongest_one() {
        val device = Fx.Device()
        device.ingest(listOf(contactsV2), usage, listOf(v1, v2), nowMs = BASE + 2 * DAY)
        val findings = Auditor().audit(device.rows, listOf(v2), BASE + 2 * DAY, BASE)

        val slice = LedgerSlice(device.rows, BASE + 2 * DAY, BASE)
        val twice = PolicyEngine.default().evaluateAll(slice, listOf(v2)) + PolicyEngine.default().evaluateAll(slice, listOf(v2))

        assertEquals(findings.size, FindingRanking.rank(twice).size)
    }
}
