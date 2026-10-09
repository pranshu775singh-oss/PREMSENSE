package com.ojas.permsense.i18n

import com.ojas.permsense.baseline.RowFx
import com.ojas.permsense.findings.Finding
import com.ojas.permsense.findings.SuggestedAction
import com.ojas.permsense.model.Ops
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DigestTest {

    private fun critical(pkg: String) = Finding.PolicyViolation(
        pkg, "INV-01", "t", "e", "c", Ops.CONTACTS, Finding.Basis.LEDGER, listOf(RowFx.row(RowFx.dayStart(1), pkg = pkg)),
        SuggestedAction.None, params = mapOf("count" to "1"),
    )

    private fun mild(pkg: String, op: String = Ops.CAMERA) =
        Finding.BaselineDeviation(pkg, op, "BACKGROUND", 0.001, 5, 0.2, RowFx.dayStart(1), emptyList())

    @Test
    fun everyCriticalFindingIsKeptEvenWhenThereAreManyOfThem() {
        val ranked = (1..9).map { critical("com.c$it") }

        val digest = DigestBuilder.build(ranked)

        assertEquals(9, digest.critical.size)
        assertEquals(0, digest.withheld)
    }

    @Test
    fun theRestAreCappedAtTheAlertBudgetAndTheRemainderIsCounted() {
        val ranked = (1..8).map { mild("com.m$it") }

        val digest = DigestBuilder.build(ranked)

        assertEquals(DigestBuilder.MAX_NON_CRITICAL, digest.others.size)
        assertEquals(5, digest.withheld)
        assertEquals(8, digest.others.size + digest.withheld)
    }

    @Test
    fun aCriticalFindingNeverCountsAgainstTheBudget() {
        val ranked = (1..2).map { critical("com.c$it") } + (1..5).map { mild("com.m$it") }

        val digest = DigestBuilder.build(ranked)

        assertEquals(2, digest.critical.size)
        assertEquals(3, digest.others.size)
        assertEquals(2, digest.withheld)
    }

    @Test
    fun anEmptyWeekSaysNothingNeedsAttentionAndSendsNoNotification() {
        val digest = DigestBuilder.build(emptyList())

        assertTrue(digest.isEmpty)
        assertNull(DigestText.notification(digest, Lang.EN))
        assertNull(DigestText.notification(digest, Lang.HI))
        assertEquals("Nothing needs your attention this week.", DigestText.headline(digest, Lang.EN))
    }

    @Test
    fun theHeadlineCountsWhatIsInTheDigest() {
        val digest = DigestBuilder.build(listOf(critical("a"), mild("b"), mild("c")))

        assertEquals("1 needs attention now, 2 more worth a look.", DigestText.headline(digest, Lang.EN))
        assertTrue(DigestText.headline(digest, Lang.HI).contains("1") && DigestText.headline(digest, Lang.HI).contains("2"))
    }

    @Test
    fun theHeadlineMentionsWhatWasLeftOut() {
        val digest = DigestBuilder.build((1..6).map { mild("com.m$it") })

        assertTrue(DigestText.headline(digest, Lang.EN).contains("3 lower-priority"))
    }

    /**
     * Someone else may be holding the phone. The notification must not announce that its owner is
     * checking for spying, in either language.
     */
    @Test
    fun theNotificationIsNeutralInBothLanguages() {
        val banned = listOf(
            "spy", "stalk", "surveil", "track", "abuse", "abuser", "malware", "hack", "threat", "danger",
            "lending", "loan", "illegal", "जासूस", "निगरानी", "ख़तरा", "खतरा", "लोन", "अवैध",
        )

        for (digest in listOf(
            DigestBuilder.build(listOf(critical("a"))),
            DigestBuilder.build(listOf(mild("a"))),
            DigestBuilder.build(listOf(critical("a"), mild("b"), mild("c"), mild("d"), mild("e"))),
        )) {
            for (lang in Lang.values()) {
                val n = DigestText.notification(digest, lang)
                assertNotNull(n)
                val text = (n!!.title + " " + n.body).lowercase()

                for (word in banned) assertFalse("\"$word\" in \"$text\"", text.contains(word.lowercase()))
            }
        }
    }

    @Test
    fun theBetweenDigestsAlertIsJustAsNeutral() {
        val banned = listOf("spy", "stalk", "surveil", "track", "abuse", "malware", "hack", "threat", "danger", "lending", "loan", "जासूस", "निगरानी", "लोन")

        for (lang in Lang.values()) {
            val n = DigestText.alert(2, lang)
            val text = (n.title + " " + n.body).lowercase()

            assertTrue(text.contains("2"))
            for (word in banned) assertFalse("\"$word\" in \"$text\"", text.contains(word.lowercase()))
        }
    }

    @Test
    fun theNotificationNeverNamesAnApp() {
        val n = DigestText.notification(DigestBuilder.build(listOf(critical("com.secret.lender"))), Lang.EN)!!

        assertFalse((n.title + n.body).contains("secret"))
        assertFalse((n.title + n.body).contains("lender"))
    }

    @Test
    fun theNotificationSaysHowSoonSomethingNeedsLooking() {
        val urgent = DigestText.notification(DigestBuilder.build(listOf(critical("a"))), Lang.EN)!!
        val calm = DigestText.notification(DigestBuilder.build(listOf(mild("a"))), Lang.EN)!!

        assertTrue(urgent.body.contains("soon"))
        assertTrue(calm.body.contains("when you have a moment"))
    }
}
