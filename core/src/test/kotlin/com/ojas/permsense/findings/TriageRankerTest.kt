package com.ojas.permsense.findings

import com.ojas.permsense.model.Ops
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TriageRankerTest {

    private fun policy(severity: Int, rule: String = "INV-01", op: String? = Ops.CONTACTS, pkg: String = "com.a") =
        Finding.PolicyViolation(
            packageName = pkg, ruleId = rule, title = "t", explanation = "e", citation = "c", op = op,
            basis = Finding.Basis.LEDGER, evidence = emptyList(), suggestedAction = SuggestedAction.None,
            severity = severity,
        )

    private fun baseline(op: String, pkg: String = "com.a") = Finding.BaselineDeviation(
        packageName = pkg, op = op, stratum = "BACKGROUND", pValue = 0.001, observed = 5, expectedPerDay = 0.1,
        dayStartMs = 0, evidence = emptyList(),
    )

    @Test
    fun anUntrainedRankerHasNoOpinion() {
        assertEquals(0.5, TriageRanker().score(baseline(Ops.CAMERA)), 1e-12)
    }

    @Test
    fun dismissingAKindOfFindingLowersItsScoreAndActingOnOneRaisesIt() {
        val ranker = TriageRanker()
        val camera = baseline(Ops.CAMERA)

        repeat(5) { ranker.learn(camera, useful = false) }
        assertTrue(ranker.score(camera) < 0.2)

        val location = baseline(Ops.FINE_LOC)
        repeat(5) { ranker.learn(location, useful = true) }
        assertTrue(ranker.score(location) > 0.5)
    }

    @Test
    fun whatItLearnedAboutOneOpIsStrongerForThatOpThanForOthers() {
        val ranker = TriageRanker()
        repeat(6) { ranker.learn(baseline(Ops.CAMERA), useful = false) }

        assertTrue(ranker.score(baseline(Ops.CAMERA)) < ranker.score(baseline(Ops.MIC)))
    }

    @Test
    fun aCriticalFindingIsAlwaysFirstNoMatterWhatWasLearned() {
        val ranker = TriageRanker()
        val critical = policy(severity = 3, rule = "INV-01", op = Ops.CAMERA)
        // The user dismisses this very kind of finding over and over.
        repeat(200) { ranker.learn(critical, useful = false) }
        assertTrue(ranker.score(critical) < 0.01)

        val others = listOf(baseline(Ops.MIC), baseline(Ops.FINE_LOC), policy(severity = 2, rule = "NEW-01"))
        val ordered = ranker.order(others + critical)

        assertEquals(critical, ordered.first())
    }

    @Test
    fun orderingNeverDropsOrDuplicatesAnything() {
        val ranker = TriageRanker()
        val findings = (1..30).map { baseline(Ops.SENSITIVE.elementAt(it % Ops.SENSITIVE.size), pkg = "com.p$it") } +
                (1..5).map { policy(3, pkg = "com.c$it") }
        repeat(10) { ranker.learn(findings[it], it % 2 == 0) }

        val ordered = ranker.order(findings)

        assertEquals(findings.size, ordered.size)
        assertEquals(findings.toSet(), ordered.toSet())
    }

    @Test
    fun severityOutranksWhateverTheModelThinks() {
        val ranker = TriageRanker()
        val severe = baseline(Ops.CAMERA).copy(severity = 2)
        val mild = baseline(Ops.MIC).copy(severity = 1)
        repeat(50) { ranker.learn(severe, useful = false); ranker.learn(mild, useful = true) }

        assertEquals(listOf(severe, mild), ranker.order(listOf(mild, severe)))
    }

    @Test
    fun withinASeverityTheUsefulOnesComeFirst() {
        val ranker = TriageRanker()
        val liked = baseline(Ops.FINE_LOC)
        val disliked = baseline(Ops.CAMERA)
        repeat(8) { ranker.learn(liked, true); ranker.learn(disliked, false) }

        assertEquals(listOf(liked, disliked), ranker.order(listOf(disliked, liked)))
    }

    @Test
    fun equalScoresKeepTheOrderTheyWereGivenIn() {
        val ranker = TriageRanker()
        val a = baseline(Ops.CAMERA, "com.a")
        val b = baseline(Ops.CAMERA, "com.b")

        assertEquals(listOf(a, b), ranker.order(listOf(a, b)))
        assertEquals(listOf(b, a), ranker.order(listOf(b, a)))
    }

    @Test
    fun weightsStayBoundedNoMatterHowLongItLearns() {
        val ranker = TriageRanker()
        repeat(5_000) { ranker.learn(baseline(Ops.CAMERA), useful = false) }

        assertTrue(ranker.weights().values.all { it in -6.0..6.0 })
    }

    @Test
    fun whatWasLearnedSurvivesBeingSavedAndLoaded() {
        val ranker = TriageRanker()
        repeat(4) { ranker.learn(baseline(Ops.CAMERA), false); ranker.learn(baseline(Ops.MIC), true) }

        val restored = TriageRanker.fromJson(ranker.toJson())

        assertEquals(ranker.score(baseline(Ops.CAMERA)), restored.score(baseline(Ops.CAMERA)), 1e-12)
        assertEquals(ranker.weights(), restored.weights())
    }

    @Test
    fun damagedSavedStateGivesAFreshRankerNotACrash() {
        for (bad in listOf(null, "", "not json", "{}", """{"weights": 5}""", """{"weights":{"a":"x"}}""")) {
            assertEquals(0.5, TriageRanker.fromJson(bad).score(baseline(Ops.CAMERA)), 1e-12)
        }
    }

    @Test
    fun learningIsDeterministic() {
        fun trained() = TriageRanker().also { r ->
            repeat(3) { r.learn(baseline(Ops.CAMERA), false); r.learn(policy(2, "NEW-01"), true) }
        }

        assertEquals(trained().weights(), trained().weights())
    }
}
