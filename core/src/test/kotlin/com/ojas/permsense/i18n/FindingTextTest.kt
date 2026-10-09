package com.ojas.permsense.i18n

import com.ojas.permsense.findings.Finding
import com.ojas.permsense.findings.SuggestedAction
import com.ojas.permsense.judgement.PolicyPackLoader
import com.ojas.permsense.model.Ops
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class FindingTextTest {

    private fun violation(rule: String, params: Map<String, String> = emptyMap(), op: String? = Ops.CONTACTS, evidence: Int = 3) =
        Finding.PolicyViolation(
            packageName = "com.example.app", ruleId = rule, title = "english fallback title",
            explanation = "english fallback", citation = "c", op = op, basis = Finding.Basis.LEDGER,
            evidence = emptyList(), suggestedAction = SuggestedAction.None, params = params + ("count" to evidence.toString()),
        )

    private fun everyKindOfFinding(): List<Finding> = listOf(
        violation("INV-01"),
        violation("DEBT-01", mapOf("days" to "30")),
        violation("NEW-01", mapOf("fromVersion" to "1", "toVersion" to "2")),
        violation("RBI-01", mapOf("state" to "used"), op = null),
        violation("RBI-01", mapOf("state" to "granted"), op = null),
        violation("RBI-01", mapOf("state" to "requested"), op = null),
        violation("RBI-02", mapOf("state" to "used"), op = null),
        violation("RBI-03", mapOf("state" to "used"), op = null),
        violation("RBI-04", mapOf("state" to "used"), op = null),
        violation("RBI-05", mapOf("windowMinutes" to "30"), op = null),
        violation(
            "SPY-01",
            mapOf("accessibility" to "true", "backgroundLocation" to "true", "sms" to "true", "deviceAdmin" to "true", "notificationListener" to "true"),
            op = null,
        ),
        Finding.BaselineDeviation("com.a", Ops.CAMERA, "SCREEN_OFF", 0.001, 5, 0.2, 0, emptyList()),
        Finding.UpdateDrift("com.a", Ops.FINE_LOC, "BACKGROUND", 1, 2, 0.99, 3.0, 0.5, 9.0, emptyList()),
        Finding.SustainedShift("com.a", Ops.MIC, "BACKGROUND", 0, 6, 0.4, 4.2, emptyList()),
    )

    @Test
    fun everyKindOfFindingReadsInBothLanguagesWithNoPlaceholdersLeftOver() {
        for (f in everyKindOfFinding()) {
            for (lang in Lang.values()) {
                val card = FindingText.card(f, lang)
                val text = card.title + " " + card.why

                assertTrue("$f $lang empty", card.title.isNotBlank() && card.why.isNotBlank())
                assertFalse("$lang: unresolved placeholder in \"$text\"", text.contains("null") || text.contains("{") || text.contains("\$"))
            }
        }
    }

    @Test
    fun everyRuleInThePackHasRealTextNotTheEnglishFallback() {
        for (rule in PolicyPackLoader.KNOWN_RULES) {
            val f = everyKindOfFinding().filterIsInstance<Finding.PolicyViolation>().first { it.ruleId == rule }
            assertNotEquals("$rule has no template", "english fallback title", FindingText.card(f, Lang.EN).title)
            assertNotEquals("$rule has no Hindi template", "english fallback title", FindingText.card(f, Lang.HI).title)
        }
    }

    @Test
    fun hindiTextIsHindiAndEnglishTextIsNot() {
        fun devanagari(s: String) = s.count { it in 'ऀ'..'ॿ' }

        for (f in everyKindOfFinding()) {
            val en = FindingText.card(f, Lang.EN)
            val hi = FindingText.card(f, Lang.HI)

            assertEquals("English text must not contain Devanagari: ${en.title}", 0, devanagari(en.title + en.why))
            assertTrue("Hindi text must be mostly Devanagari: ${hi.title}", devanagari(hi.title + hi.why) > (hi.title + hi.why).length / 4)
        }
    }

    @Test
    fun theTextCarriesTheFactsItIsBuiltFrom() {
        val inv = FindingText.card(violation("INV-01", evidence = 7), Lang.EN)
        assertTrue(inv.why.contains("7 time"))
        assertTrue(inv.title.contains("contacts"))

        val new = FindingText.card(violation("NEW-01", mapOf("fromVersion" to "41", "toVersion" to "42")), Lang.EN)
        assertTrue(new.why.contains("41") && new.why.contains("42"))

        val debt = FindingText.card(violation("DEBT-01", mapOf("days" to "45")), Lang.EN)
        assertTrue(debt.why.contains("45"))

        val baseline = FindingText.card(Finding.BaselineDeviation("a", Ops.CAMERA, "SCREEN_OFF", 0.001, 6, 0.3, 0, emptyList()), Lang.EN)
        assertTrue(baseline.why.contains("6") && baseline.why.contains("0.3") && baseline.why.contains("screen was off"))

        val drift = FindingText.card(Finding.UpdateDrift("a", Ops.MIC, "BACKGROUND", 7, 8, 0.99, 3.0, 0.4, 12.0, emptyList()), Lang.EN)
        assertTrue(drift.why.contains("7") && drift.why.contains("8") && drift.why.contains("12"))
    }

    @Test
    fun theThreeStatesOfALendingFindingAreWordedDifferently() {
        val titles = listOf("used", "granted", "requested").map {
            FindingText.card(violation("RBI-01", mapOf("state" to it), op = null), Lang.EN).title
        }

        assertEquals(3, titles.toSet().size)
    }

    @Test
    fun spyTextNamesTheActualPowersHeld() {
        val onlyAccessibilityAndSms = violation(
            "SPY-01", mapOf("accessibility" to "true", "deviceAdmin" to "false", "backgroundLocation" to "false", "sms" to "true"), op = null,
        )

        val why = FindingText.card(onlyAccessibilityAndSms, Lang.EN).why

        assertTrue(why.contains("accessibility"))
        assertTrue(why.contains("SMS"))
        assertFalse(why.contains("device admin"))
        assertFalse(why.contains("background location"))
    }

    @Test
    fun opAndContextNamesCoverEverySensitiveOpAndEveryStratum() {
        for (op in Ops.SENSITIVE) {
            for (lang in Lang.values()) {
                val name = FindingText.op(op, lang)
                assertTrue("$op $lang", name.isNotBlank() && !name.contains("_") && !name.startsWith("android"))
            }
        }
        for (s in listOf("VISIBLE", "FOREGROUND_SERVICE", "BACKGROUND", "SCREEN_OFF", "UNKNOWN")) {
            assertNotEquals(FindingText.context(s, Lang.EN), FindingText.context("SOMETHING_ELSE", Lang.EN).takeIf { s != "UNKNOWN" })
            assertTrue(FindingText.context(s, Lang.HI).isNotBlank())
        }
    }

    @Test
    fun actionLabelsAreReadableInBothLanguages() {
        val revoke = SuggestedAction.RevokePermission("com.a", "android.permission.READ_CONTACTS")

        assertEquals("Remove contacts access", FindingText.actionLabel(revoke, Lang.EN))
        assertTrue(FindingText.actionLabel(revoke, Lang.HI)!!.contains("संपर्क"))
        assertEquals("Open app settings", FindingText.actionLabel(SuggestedAction.OpenAppSettings("com.a"), Lang.EN))
        assertNull(FindingText.actionLabel(SuggestedAction.None, Lang.EN))
    }

    @Test
    fun timesFollowTheTimeZoneGiven() {
        val ms = 1_760_000_000_000L

        val utc = FindingText.time(ms, Lang.EN, ZoneId.of("UTC"))
        val india = FindingText.time(ms, Lang.EN, ZoneId.of("Asia/Kolkata"))

        assertNotEquals(utc, india)
        assertTrue(utc.contains("2025"))
    }

    @Test
    fun languageTagsMapToTheRightLanguage() {
        assertEquals(Lang.HI, Lang.fromTag("hi"))
        assertEquals(Lang.HI, Lang.fromTag("hi-IN"))
        assertEquals(Lang.HI, Lang.fromTag("HI"))
        assertEquals(Lang.EN, Lang.fromTag("en-GB"))
        assertEquals(Lang.EN, Lang.fromTag(null))
        assertEquals(Lang.EN, Lang.fromTag("fr"))
    }
}
