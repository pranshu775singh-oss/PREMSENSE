package com.ojas.permsense.judgement

import com.ojas.permsense.judgement.Fx.BASE
import com.ojas.permsense.judgement.Fx.DAY
import com.ojas.permsense.model.Ops
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The rule pack is data. These tests treat it that way: load it, break it, change it. */
class PolicyPackTest {

    private val bundled = PolicyPackLoader.loadBundled()

    private fun rule(
        id: String = "INV-01",
        severity: Any = 3,
        citation: String = "a basis",
        extra: String = "",
    ) = """{"id":"$id","enabled":true,"severity":$severity,"citation":"$citation","params":{$extra}}"""

    private fun pack(
        rules: String = rule(),
        schema: Int = 1,
        version: String = "1.0",
        lending: String = "",
    ) = """{"schemaVersion":$schema,"pack":{"id":"t","version":"$version"},"lendingApps":[$lending],"rules":[$rules]}"""

    private fun rejects(text: String, mention: String? = null) {
        try {
            PolicyPackLoader.parse(text)
            fail("accepted: $text")
        } catch (e: PolicyPackException) {
            if (mention != null) assertTrue("${e.message}", e.message!!.contains(mention, ignoreCase = true))
        }
    }

    // ------------------------------------------------------------------ the bundled pack

    @Test
    fun theBundledPackLoadsAndCarriesEveryRuleTheSpecNames() {
        assertEquals(PolicyPackLoader.KNOWN_RULES, bundled.rules.map { it.id })
        assertEquals(1, bundled.schemaVersion)
        assertTrue(bundled.warnings.isEmpty())
    }

    @Test
    fun everyRuleInTheBundledPackHasACitation() {
        for (rule in bundled.rules) {
            assertTrue("${rule.id} has no citation", rule.citation.length > 40)
        }
    }

    @Test
    fun theRbiClauseReferencesAreFlaggedAsAwaitingReviewUntilSomeoneChecksThem() {
        // Honest labelling: no clause numbers were invented. When a reviewer confirms them and
        // removes the marker, this list becomes empty and the report stops carrying the caveat.
        assertEquals(listOf("RBI-01", "RBI-02", "RBI-03", "RBI-04", "RBI-05"), bundled.rulesAwaitingReview())
    }

    @Test
    fun theBundledPackNamesTheTeamsOwnTestAppAsLending() {
        assertEquals(setOf(LendingRegistry.PROBE_QUICKCASH), bundled.lendingApps)
        assertEquals(
            setOf("com.ojas.quickcash", "com.mine.loan"),
            LendingRegistry.packages(setOf("com.mine.loan"), bundled),
        )
    }

    @Test
    fun theDefaultEngineIsBuiltFromTheBundledPack() {
        assertEquals(bundled.packVersion, PolicyEngine.default().packVersion)
        assertEquals(bundled.rules.map { it.id }, PolicyEngine.default().ruleIds)
    }

    // ------------------------------------------------------------------ validation

    @Test
    fun aMinimalValidPackLoads() {
        val p = PolicyPackLoader.parse(pack())

        assertEquals("t", p.packId)
        assertEquals("1.0", p.packVersion)
        assertEquals(listOf("INV-01"), p.rules.map { it.id })
    }

    @Test
    fun rejectsAnUnsupportedSchema() = rejects(pack(schema = 2), "schema")

    @Test
    fun rejectsInvalidJson() = rejects("{not json", "JSON")

    @Test
    fun rejectsARuleWithoutACitation() = rejects(pack(rule(citation = "  ")), "citation")

    @Test
    fun rejectsASeverityOutsideOneToThree() {
        rejects(pack(rule(severity = 0)), "severity")
        rejects(pack(rule(severity = 4)), "severity")
    }

    @Test
    fun rejectsDuplicateRuleIds() = rejects(pack(rule() + "," + rule()), "twice")

    @Test
    fun rejectsABadVersionString() = rejects(pack(version = "latest"), "version")

    @Test
    fun rejectsALendingEntryThatIsNotAPackageName() {
        rejects(pack(lending = """{"package":"not a package"}"""), "package")
        rejects(pack(lending = """{"package":"single"}"""), "package")
    }

    @Test
    fun rejectsAMissingRequiredField() {
        rejects("""{"schemaVersion":1,"rules":[]}""")
        rejects("""{"schemaVersion":1,"pack":{"id":"t","version":"1"}}""")
    }

    @Test
    fun anUnknownRuleIsIgnoredWithAWarningNotAnError() {
        val p = PolicyPackLoader.parse(pack(rule() + "," + rule(id = "FUTURE-99")))

        assertEquals(listOf("INV-01"), p.rules.map { it.id })
        assertEquals(1, p.warnings.size)
        assertTrue(p.warnings.single().contains("FUTURE-99"))
    }

    @Test
    fun aBadParameterTypeIsReportedWhenTheRuleIsBuilt() {
        val bad = PolicyPackLoader.parse(pack(rule(id = "DEBT-01", extra = """"minDays":"thirty"""")))

        try {
            PolicyEngine.fromPack(bad)
            fail()
        } catch (_: PolicyPackException) {
        }
    }

    // ------------------------------------------------------------------ the pack drives behaviour

    private val NOW = BASE + 10 * DAY

    private fun debtFindings(pack: PolicyPack, observingForDays: Int): List<String> {
        val app = Fx.app(tags = emptySet())
        val slice = LedgerSlice(emptyList(), NOW, observingSinceMs = NOW - observingForDays * DAY)
        return PolicyEngine.fromPack(pack).evaluateAll(slice, listOf(app)).map { it.ruleId }
    }

    @Test
    fun changingAThresholdInThePackChangesWhenARuleFires() {
        val thirty = PolicyPackLoader.parse(pack(rule(id = "DEBT-01", extra = """"minDays":30""")))
        val five = PolicyPackLoader.parse(pack(rule(id = "DEBT-01", extra = """"minDays":5""")))

        assertTrue(debtFindings(thirty, observingForDays = 10).isEmpty())
        assertEquals(listOf("DEBT-01", "DEBT-01"), debtFindings(five, observingForDays = 10))
    }

    @Test
    fun aDisabledRuleDoesNotRun() {
        val disabled = pack(
            """{"id":"DEBT-01","enabled":false,"severity":1,"citation":"c","params":{"minDays":1}}"""
        )

        assertTrue(debtFindings(PolicyPackLoader.parse(disabled), observingForDays = 10).isEmpty())
        assertEquals(emptyList<String>(), PolicyEngine.fromPack(PolicyPackLoader.parse(disabled)).ruleIds)
    }

    @Test
    fun theSeverityAndCitationInThePackReachTheFinding() {
        val custom = PolicyPackLoader.parse(
            pack(rule(id = "DEBT-01", severity = 2, citation = "my own basis", extra = """"minDays":1"""))
        )
        val slice = LedgerSlice(emptyList(), NOW, observingSinceMs = NOW - 5 * DAY)

        val f = PolicyEngine.fromPack(custom).evaluateAll(slice, listOf(Fx.app(tags = emptySet())))

        assertTrue(f.isNotEmpty())
        assertTrue(f.all { it.severity == 2 && it.citation == "my own basis" })
    }

    @Test
    fun theOpsAnRbiRuleWatchesComeFromThePack() {
        // A pack that adds the clipboard to the contacts rule makes clipboard use a violation.
        val custom = PolicyPackLoader.parse(
            pack(rule(id = "RBI-01", extra = """"ops":["android:read_contacts","android:read_clipboard"],"permissions":[]"""))
        )
        val device = Fx.Device()
        device.ingest(
            listOf(Fx.obs("a", BASE + DAY, Ops.CLIPBOARD)),
            Fx.usage(Fx.screenAlwaysOn()),
            listOf(Fx.app()),
            nowMs = NOW,
        )

        val f = PolicyEngine.fromPack(custom).evaluateAll(LedgerSlice(device.rows, NOW, null), listOf(Fx.app()))

        assertEquals(listOf("RBI-01"), f.map { it.ruleId })
        assertEquals(Finding_Basis_LEDGER, f.single().basis)
    }

    @Test
    fun theOnboardingWindowComesFromThePack() {
        fun late(minutes: Int): Boolean {
            val custom = PolicyPackLoader.parse(
                pack(rule(id = "RBI-05", extra = """"onboardingWindowMinutes":$minutes,"ops":["android:camera"]"""))
            )
            val device = Fx.Device()
            device.ingest(
                listOf(Fx.obs("a", BASE + DAY, Ops.CAMERA), Fx.obs("b", BASE + DAY + 45 * 60_000L, Ops.CAMERA)),
                Fx.usage(Fx.screenAlwaysOn()),
                listOf(Fx.app()),
                nowMs = NOW,
            )
            return PolicyEngine.fromPack(custom)
                .evaluateAll(LedgerSlice(device.rows, NOW, null), listOf(Fx.app()))
                .any { it.ruleId == "RBI-05" }
        }

        assertFalse(late(minutes = 60))
        assertTrue(late(minutes = 30))
    }

    // ------------------------------------------------------------------ versions

    @Test
    fun packVersionsCompareAsNumbersNotText() {
        assertTrue(PolicyPack.compareVersions("2026.10.1", "2026.9.9") > 0)
        assertTrue(PolicyPack.compareVersions("1.10", "1.9") > 0)
        assertEquals(0, PolicyPack.compareVersions("1.0", "1.0.0"))
        assertTrue(PolicyPack.compareVersions("1.0", "1.0.1") < 0)

        val old = PolicyPackLoader.parse(pack(version = "2026.9.9"))
        val new = PolicyPackLoader.parse(pack(version = "2026.10.1"))
        assertTrue(new.isNewerThan(old))
        assertFalse(old.isNewerThan(new))
        assertFalse(old.isNewerThan(old))
    }

    private companion object {
        val Finding_Basis_LEDGER = com.ojas.permsense.findings.Finding.Basis.LEDGER
    }
}
