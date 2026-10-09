package com.ojas.permsense.i18n

import com.ojas.permsense.baseline.RowFx
import com.ojas.permsense.findings.Finding
import com.ojas.permsense.findings.SuggestedAction
import com.ojas.permsense.model.Ops
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** Acceptance S9: every card answers what, who, when, in what situation, why, and what to do. */
class EvidenceCardTest {

    private val zone = ZoneId.of("UTC")
    private val permission = "android.permission.READ_CONTACTS"

    private val evidence = listOf(
        RowFx.row(RowFx.dayStart(2) + 2 * RowFx.HOUR, stratum = RowFx.SCREEN_OFF),
        RowFx.row(RowFx.dayStart(2) + 3 * RowFx.HOUR, stratum = RowFx.SCREEN_OFF),
    )

    private fun invisible() = Finding.PolicyViolation(
        "com.example.app", "INV-01", "t", "e", "c", Ops.CONTACTS, Finding.Basis.LEDGER, evidence,
        SuggestedAction.RevokePermission("com.example.app", permission), params = mapOf("count" to "2"),
    )

    private fun inventoryOnly() = Finding.PolicyViolation(
        "com.example.app", "RBI-01", "t", "e", "c", null, Finding.Basis.INVENTORY, emptyList(),
        SuggestedAction.RevokePermission("com.example.app", permission), params = mapOf("state" to "granted"),
    )

    private fun debt() = Finding.PolicyViolation(
        "com.example.app", "DEBT-01", "t", "e", "c", Ops.CAMERA, Finding.Basis.INVENTORY, emptyList(),
        SuggestedAction.OpenAppSettings("com.example.app"), severity = 1, params = mapOf("days" to "45"),
    )

    private fun everyKind(): List<Finding> = listOf(
        invisible(),
        inventoryOnly(),
        debt(),
        Finding.BaselineDeviation("com.example.app", Ops.CAMERA, "SCREEN_OFF", 0.001, 5, 0.2, RowFx.dayStart(4), evidence),
        Finding.UpdateDrift("com.example.app", Ops.FINE_LOC, "BACKGROUND", 1, 2, 0.99, 3.0, 0.5, 9.0, evidence),
        Finding.SustainedShift("com.example.app", Ops.MIC, "BACKGROUND", RowFx.dayStart(1), 6, 0.4, 4.2, evidence),
    )

    @Test
    fun everyKindOfFindingAnswersAllSixQuestionsInBothLanguages() {
        for (f in everyKind()) {
            for (lang in Lang.values()) {
                val card = EvidenceCards.build(f, lang, "Quick Lender", "2.0", 2, zone)

                assertTrue("$f $lang what", card.what.isNotBlank())
                assertTrue("$f $lang who", card.who.contains("Quick Lender"))
                assertTrue("$f $lang when", card.whenText.isNotBlank())
                assertTrue("$f $lang situation", card.situation.isNotBlank())
                assertTrue("$f $lang why", card.why.isNotBlank())
                assertNotNull("$f $lang action", card.doWhat)
            }
        }
    }

    private fun assertNotNull(message: String, value: Any?) = assertTrue(message, value != null)

    @Test
    fun theAppAndItsVersionAreNamed() {
        val en = EvidenceCards.build(invisible(), Lang.EN, "Quick Lender", "2.0", 41, zone)
        val hi = EvidenceCards.build(invisible(), Lang.HI, "Quick Lender", "2.0", 41, zone)

        assertEquals("Quick Lender, version 2.0 (41)", en.who)
        assertEquals("Quick Lender, वर्शन 2.0 (41)", hi.who)
        assertEquals("Quick Lender", EvidenceCards.build(invisible(), Lang.EN, "Quick Lender", zone = zone).who)
        assertEquals("Quick Lender, version 41", EvidenceCards.build(invisible(), Lang.EN, "Quick Lender", null, 41, zone).who)
    }

    @Test
    fun theTimeIsTheNewestEvidenceAndTheSituationIsItsContext() {
        val card = EvidenceCards.build(invisible(), Lang.EN, "A", zone = zone)

        assertEquals(FindingText.time(evidence.last().timestampMs, Lang.EN, zone), card.whenText)
        assertEquals("While your screen was off or locked", card.situation)
    }

    @Test
    fun aFindingWithNoUseSaysSoRatherThanInventingATime() {
        val card = EvidenceCards.build(inventoryOnly(), Lang.EN, "A", zone = zone)

        assertEquals("Not used yet", card.whenText)
        assertTrue(card.situation.contains("has not used them yet"))
    }

    @Test
    fun permissionDebtSaysHowLongThereHasBeenNoUse() {
        assertEquals("No use seen in 45 days", EvidenceCards.build(debt(), Lang.EN, "A", zone = zone).whenText)
        assertTrue(EvidenceCards.build(debt(), Lang.HI, "A", zone = zone).whenText.contains("45"))
    }

    @Test
    fun aStatisticalFindingTakesItsSituationFromItsOwnStratum() {
        val drift = everyKind().filterIsInstance<Finding.UpdateDrift>().single()

        assertEquals("While the app was in the background", EvidenceCards.build(drift, Lang.EN, "A", zone = zone).situation)
    }

    @Test
    fun aFindingWithNoFixOffersNone() {
        val f = invisible().copy(suggestedAction = SuggestedAction.None)

        assertNull(EvidenceCards.build(f, Lang.EN, "A", zone = zone).doWhat)
    }

    @Test
    fun theScreenReaderPassageReadsInTheOrderAPersonWouldAsk() {
        val card = EvidenceCards.build(invisible(), Lang.EN, "Quick Lender", "2.0", 2, zone)

        val spoken = card.spoken(Lang.EN)

        val order = listOf("What happened", "Which app", "When", "Situation", "Why it was flagged", "What you can do")
            .map { spoken.indexOf("$it:") }

        assertTrue(order.toString(), order.all { it >= 0 })
        assertEquals(order.sorted(), order)
        assertTrue(spoken.startsWith("Needs attention now."))
        assertTrue(spoken.contains("Remove contacts access"))
    }

    @Test
    fun theHindiPassageUsesHindiCaptions() {
        val spoken = EvidenceCards.build(invisible(), Lang.HI, "A", zone = zone).spoken(Lang.HI)

        assertTrue(spoken.contains("क्या हुआ:"))
        assertTrue(spoken.contains("आप क्या कर सकते हैं:"))
        assertFalse(spoken.contains("What happened"))
    }

    @Test
    fun severityWordsDifferAndAreTranslated() {
        val words = (1..3).map { EvidenceCards.severityWord(it, Lang.EN) }

        assertEquals(3, words.toSet().size)
        for (s in 1..3) assertNotEquals(EvidenceCards.severityWord(s, Lang.EN), EvidenceCards.severityWord(s, Lang.HI))
    }

    @Test
    fun noCardCarriesAnUnresolvedPlaceholder() {
        for (f in everyKind()) {
            for (lang in Lang.values()) {
                val c = EvidenceCards.build(f, lang, "App", "1", 1, zone)
                val all = listOfNotNull(c.what, c.who, c.whenText, c.situation, c.why, c.doWhat).joinToString(" ")

                assertFalse("$lang: $all", all.contains("null") || all.contains("{") || all.contains("\$"))
            }
        }
    }
}
