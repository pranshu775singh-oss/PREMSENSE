package com.ojas.permsense.judgement

import com.ojas.permsense.findings.Finding
import com.ojas.permsense.findings.SuggestedAction
import com.ojas.permsense.model.AppInventory
import com.ojas.permsense.model.Ops
import com.ojas.permsense.model.RawObservation
import com.ojas.permsense.judgement.Fx.BASE
import com.ojas.permsense.judgement.Fx.DAY
import com.ojas.permsense.judgement.Fx.HOUR
import com.ojas.permsense.judgement.Fx.MIN
import com.ojas.permsense.judgement.Fx.PKG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyEngineTest {

    private val P = "android.permission."
    private val NOW = BASE + 40 * DAY

    private fun run(
        observations: List<RawObservation>,
        inventory: List<AppInventory> = listOf(Fx.app()),
        context: com.ojas.permsense.processing.UsageContext? = Fx.usage(Fx.screenAlwaysOn()),
        history: List<AppInventory> = inventory,
        observingSince: Long? = null,
        now: Long = NOW,
    ): List<Finding.PolicyViolation> {
        val device = Fx.Device()
        device.ingest(observations, context, history, nowMs = now)
        val slice = LedgerSlice(device.rows, now, observingSince)
        return PolicyEngine.default().evaluateAll(slice, inventory)
    }

    private fun List<Finding.PolicyViolation>.ids() = map { it.ruleId }.toSet()

    // ---------------------------------------------------------------- INV-01

    private val screenOffContext get() = Fx.usage(Fx.screenOn(BASE + 1), Fx.screenOff(BASE + HOUR))

    @Test
    fun inv01_fires_for_sensitive_access_with_screen_off() {
        val f = run(
            listOf(Fx.obs("a", BASE + 2 * HOUR, Ops.CONTACTS)),
            inventory = listOf(Fx.app(tags = emptySet())),
            context = screenOffContext,
        )
        assertEquals(setOf("INV-01"), f.ids())
        val inv = f.single()
        assertEquals(3, inv.severity)
        assertEquals(Finding.Basis.LEDGER, inv.basis)
        assertEquals(1, inv.evidence.size)
        assertTrue(inv.suggestedAction is SuggestedAction.RevokePermission)
    }

    @Test
    fun inv01_does_not_fire_when_the_app_is_on_screen() {
        val ctx = Fx.usage(
            Fx.screenOn(BASE + 1),
            Fx.resumed(BASE + 2 * HOUR - MIN),
            Fx.paused(BASE + 2 * HOUR + MIN),
        )
        val f = run(
            listOf(Fx.obs("a", BASE + 2 * HOUR, Ops.CONTACTS)),
            inventory = listOf(Fx.app(tags = emptySet())),
            context = ctx,
        )
        assertTrue(f.toString(), f.isEmpty())
    }

    @Test
    fun inv01_does_not_fire_without_usage_context_because_unknown_is_not_screen_off() {
        val f = run(
            listOf(Fx.obs("a", BASE + 2 * HOUR, Ops.CONTACTS)),
            inventory = listOf(Fx.app(tags = emptySet())),
            context = null,
        )
        assertTrue(f.toString(), f.isEmpty())
    }

    @Test
    fun inv01_ignores_system_apps_and_platform_uids() {
        val systemApp = run(
            listOf(Fx.obs("a", BASE + 2 * HOUR, Ops.CONTACTS)),
            inventory = listOf(Fx.app(tags = emptySet(), system = true)),
            context = screenOffContext,
        )
        assertTrue(systemApp.isEmpty())

        val platformUid = run(
            listOf(Fx.obs("a", BASE + 2 * HOUR, Ops.CONTACTS, uid = 1000)),
            inventory = listOf(Fx.app(tags = emptySet())),
            context = screenOffContext,
        )
        assertTrue(platformUid.isEmpty())
    }

    @Test
    fun inv01_ignores_non_sensitive_ops() {
        val f = run(
            listOf(Fx.obs("a", BASE + 2 * HOUR, "android:wake_lock")),
            inventory = listOf(Fx.app(tags = emptySet())),
            context = screenOffContext,
        )
        assertTrue(f.isEmpty())
    }

    // ---------------------------------------------------------------- DEBT-01

    @Test
    fun debt01_fires_for_granted_but_never_used_permission_after_30_days() {
        val f = run(
            observations = listOf(Fx.obs("a", BASE + DAY, Ops.CAMERA)),
            inventory = listOf(Fx.app(tags = emptySet())),
            observingSince = BASE,
        )
        val debt = f.filter { it.ruleId == "DEBT-01" }
        assertEquals(1, debt.size)
        assertEquals(Ops.CONTACTS, debt.single().op)
        assertEquals(1, debt.single().severity)
        assertEquals(
            SuggestedAction.RevokePermission(PKG, P + "READ_CONTACTS"),
            debt.single().suggestedAction,
        )
    }

    @Test
    fun debt01_fails_closed_without_op_level_sensing() {
        val f = run(emptyList(), inventory = listOf(Fx.app(tags = emptySet())))
        assertTrue(f.isEmpty())
    }

    @Test
    fun debt01_waits_until_the_observation_window_is_long_enough() {
        val f = run(
            emptyList(),
            inventory = listOf(Fx.app(tags = emptySet())),
            observingSince = NOW - 10 * DAY,
        )
        assertTrue(f.isEmpty())
    }

    // ---------------------------------------------------------------- NEW-01

    private val v1 = Fx.app(version = 1, lastUpdate = BASE, tags = emptySet())
    private val v2 = Fx.app(version = 2, lastUpdate = BASE + DAY, tags = emptySet())

    @Test
    fun new01_fires_when_an_op_first_appears_after_a_recorded_update() {
        val f = run(
            observations = listOf(
                Fx.obs("cam", BASE + HOUR, Ops.CAMERA),
                Fx.obs("con", BASE + DAY + HOUR, Ops.CONTACTS),
            ),
            inventory = listOf(v2),
            history = listOf(v1, v2),
        )
        val n = f.single { it.ruleId == "NEW-01" }
        assertEquals(Ops.CONTACTS, n.op)
        assertEquals(2, n.evidence.size)
        assertEquals(1L, n.evidence[0].versionCode)
        assertEquals(2L, n.evidence[1].versionCode)
    }

    @Test
    fun new01_does_not_fire_when_the_op_was_already_used_before_the_update() {
        val f = run(
            observations = listOf(
                Fx.obs("c1", BASE + HOUR, Ops.CONTACTS),
                Fx.obs("c2", BASE + DAY + HOUR, Ops.CONTACTS),
            ),
            inventory = listOf(v2),
            history = listOf(v1, v2),
        )
        assertNull(f.firstOrNull { it.ruleId == "NEW-01" })
    }

    @Test
    fun new01_does_not_fire_without_a_recorded_version_change() {
        val f = run(
            observations = listOf(
                Fx.obs("cam", BASE + HOUR, Ops.CAMERA),
                Fx.obs("con", BASE + 2 * HOUR, Ops.CONTACTS),
            ),
            inventory = listOf(v1),
        )
        assertNull(f.firstOrNull { it.ruleId == "NEW-01" })
    }

    // ---------------------------------------------------------------- RBI

    @Test
    fun rbi01_is_a_preemptive_warning_when_a_lending_app_merely_requests_contacts() {
        val app = Fx.app(requested = listOf(P + "READ_CONTACTS"), granted = emptyList())
        val f = run(emptyList(), inventory = listOf(app), observingSince = null)
        val r = f.single { it.ruleId == "RBI-01" }
        assertEquals(Finding.Basis.INVENTORY, r.basis)
        assertEquals(2, r.severity)
        assertTrue(r.evidence.isEmpty())
    }

    @Test
    fun rbi01_preemptive_warning_is_critical_when_the_permission_is_granted() {
        val f = run(emptyList(), inventory = listOf(Fx.app()), observingSince = null)
        val r = f.single { it.ruleId == "RBI-01" }
        assertEquals(3, r.severity)
        assertEquals(SuggestedAction.RevokePermission(PKG, P + "READ_CONTACTS"), r.suggestedAction)
    }

    @Test
    fun rbi01_uses_recorded_access_as_evidence_when_it_exists() {
        val f = run(
            listOf(Fx.obs("a", BASE + HOUR, Ops.CONTACTS)),
            inventory = listOf(Fx.app()),
        )
        val r = f.single { it.ruleId == "RBI-01" }
        assertEquals(Finding.Basis.LEDGER, r.basis)
        assertEquals(3, r.severity)
        assertEquals(1, r.evidence.size)
    }

    @Test
    fun rbi_pack_ignores_apps_that_are_not_tagged_lending() {
        val f = run(
            listOf(Fx.obs("a", BASE + HOUR, Ops.CONTACTS)),
            inventory = listOf(Fx.app(tags = emptySet())),
        )
        assertTrue(f.none { it.ruleId.startsWith("RBI") })
    }

    @Test
    fun rbi02_rbi03_rbi04_cover_call_log_media_and_telephony() {
        val f = run(
            listOf(
                Fx.obs("cl", BASE + HOUR, Ops.CALL_LOG),
                Fx.obs("im", BASE + 2 * HOUR, Ops.MEDIA_IMAGES),
                Fx.obs("sm", BASE + 3 * HOUR, Ops.SMS),
            ),
            inventory = listOf(Fx.app()),
        )
        assertTrue(f.ids().containsAll(setOf("RBI-02", "RBI-03", "RBI-04")))
    }

    @Test
    fun rbi05_flags_camera_use_long_after_the_onboarding_window() {
        val f = run(
            listOf(
                Fx.obs("a", BASE + HOUR, Ops.CAMERA),
                Fx.obs("b", BASE + HOUR + 10 * MIN, Ops.CAMERA),
                Fx.obs("c", BASE + 5 * HOUR, Ops.CAMERA),
            ),
            inventory = listOf(Fx.app()),
        )
        val r = f.single { it.ruleId == "RBI-05" }
        assertEquals(1, r.evidence.size)
        assertEquals(BASE + 5 * HOUR, r.evidence.single().timestampMs)
    }

    @Test
    fun rbi05_allows_a_single_onboarding_window() {
        val f = run(
            listOf(
                Fx.obs("a", BASE + HOUR, Ops.CAMERA),
                Fx.obs("b", BASE + HOUR + 20 * MIN, Ops.MIC),
            ),
            inventory = listOf(Fx.app()),
        )
        assertNull(f.firstOrNull { it.ruleId == "RBI-05" })
    }

    // ---------------------------------------------------------------- SPY-01

    @Test
    fun spy01_fires_for_accessibility_plus_background_location() {
        val app = Fx.app(
            tags = emptySet(),
            requested = listOf(P + "ACCESS_BACKGROUND_LOCATION"),
            accessibility = true,
        )
        val f = run(emptyList(), inventory = listOf(app), observingSince = null)
        assertEquals(setOf("SPY-01"), f.ids())
        assertEquals(Finding.Basis.INVENTORY, f.single().basis)
    }

    @Test
    fun spy01_fires_for_notification_listener_plus_sms() {
        val app = Fx.app(tags = emptySet(), requested = listOf(P + "READ_SMS"), listener = true)
        val f = run(emptyList(), inventory = listOf(app), observingSince = null)
        assertEquals(setOf("SPY-01"), f.ids())
    }

    @Test
    fun spy01_needs_both_halves_of_the_combination() {
        val onlyPower = Fx.app(tags = emptySet(), requested = emptyList(), accessibility = true)
        val onlyGrant = Fx.app(tags = emptySet(), requested = listOf(P + "READ_SMS"))
        val system = Fx.app(tags = emptySet(), requested = listOf(P + "READ_SMS"), accessibility = true, system = true)
        for (app in listOf(onlyPower, onlyGrant, system)) {
            val f = run(emptyList(), inventory = listOf(app), observingSince = null)
            assertTrue(app.toString(), f.none { it.ruleId == "SPY-01" })
        }
    }

    // ---------------------------------------------------------------- engine

    @Test
    fun the_default_pack_contains_every_rule_in_the_spec() {
        assertEquals(
            listOf("INV-01", "DEBT-01", "NEW-01", "RBI-01", "RBI-02", "RBI-03", "RBI-04", "RBI-05", "SPY-01"),
            PolicyEngine.default().ruleIds,
        )
    }

    @Test
    fun evaluation_is_deterministic_regardless_of_inventory_order() {
        val a = Fx.app(pkg = "com.a", tags = setOf(TAG_LENDING))
        val b = Fx.app(pkg = "com.b", tags = setOf(TAG_LENDING))
        val slice = LedgerSlice(emptyList(), NOW, null)
        val engine = PolicyEngine.default()
        assertEquals(engine.evaluateAll(slice, listOf(a, b)), engine.evaluateAll(slice, listOf(b, a)))
    }
}
