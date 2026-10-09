package com.ojas.permsense.i18n

import com.ojas.permsense.findings.Finding
import java.time.ZoneId

/**
 * One finding, answering the same six questions every time: what happened, which app (and version),
 * when, in what situation, why it was flagged, and what the person can do about it.
 *
 * Built here, not in the UI, so that "every card answers all six" is a property the unit tests can
 * check in both languages, and so the on-screen card, the screen-reader description and the
 * exported report cannot drift apart.
 */
data class EvidenceCard(
    val what: String,
    val who: String,
    val whenText: String,
    val situation: String,
    val why: String,
    /** The label of the one-tap fix, or null when the finding offers none. */
    val doWhat: String?,
    val severity: Int,
) {

    /** The whole card as one passage for a screen reader, in the order a person would ask. */
    fun spoken(lang: Lang): String {
        val c = EvidenceCards.captions(lang)
        val level = EvidenceCards.severityWord(severity, lang)
        return buildString {
            append(level).append(". ")
            append(c.what).append(": ").append(what).append(". ")
            append(c.who).append(": ").append(who).append(". ")
            append(c.whenCaption).append(": ").append(whenText).append(". ")
            append(c.situation).append(": ").append(situation).append(". ")
            append(c.why).append(": ").append(why)
            if (doWhat != null) append(". ").append(c.doWhat).append(": ").append(doWhat)
        }
    }
}

object EvidenceCards {

    data class Captions(
        val what: String,
        val who: String,
        val whenCaption: String,
        val situation: String,
        val why: String,
        val doWhat: String,
    )

    fun captions(lang: Lang): Captions =
        when (lang) {
            Lang.EN -> Captions(
                what = "What happened",
                who = "Which app",
                whenCaption = "When",
                situation = "Situation",
                why = "Why it was flagged",
                doWhat = "What you can do",
            )
            Lang.HI -> Captions(
                what = "क्या हुआ",
                who = "कौन सा ऐप",
                whenCaption = "कब",
                situation = "स्थिति",
                why = "क्यों चिह्नित किया गया",
                doWhat = "आप क्या कर सकते हैं",
            )
        }

    fun severityWord(severity: Int, lang: Lang): String =
        when (lang) {
            Lang.EN -> when {
                severity >= 3 -> "Needs attention now"
                severity == 2 -> "Worth a look"
                else -> "For information"
            }
            Lang.HI -> when {
                severity >= 3 -> "तुरंत ध्यान दें"
                severity == 2 -> "देखने लायक"
                else -> "जानकारी के लिए"
            }
        }

    fun build(
        finding: Finding,
        lang: Lang,
        appLabel: String,
        versionName: String? = null,
        versionCode: Long? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): EvidenceCard {

        val text = FindingText.card(finding, lang)

        return EvidenceCard(
            what = text.title,
            who = who(appLabel, versionName, versionCode, lang),
            whenText = whenText(finding, lang, zone),
            situation = situation(finding, lang),
            why = text.why,
            doWhat = FindingText.actionLabel(finding.suggestedAction, lang),
            severity = finding.severity,
        )
    }

    private fun who(label: String, versionName: String?, versionCode: Long?, lang: Lang): String {
        val version = when {
            versionName != null && versionCode != null -> "$versionName ($versionCode)"
            versionName != null -> versionName
            versionCode != null -> versionCode.toString()
            else -> null
        } ?: return label
        return if (lang == Lang.EN) "$label, version $version" else "$label, वर्शन $version"
    }

    private fun whenText(f: Finding, lang: Lang, zone: ZoneId): String {

        if (f is Finding.PolicyViolation && f.ruleId == "DEBT-01") {
            val days = f.params["days"] ?: "30"
            return if (lang == Lang.EN) "No use seen in $days days" else "$days दिनों में कोई उपयोग नहीं दिखा"
        }

        val newest = f.latestEvidenceMs
            ?: (f as? Finding.BaselineDeviation)?.dayStartMs
            ?: (f as? Finding.SustainedShift)?.sinceMs

        return if (newest != null) {
            FindingText.time(newest, lang, zone)
        } else {
            if (lang == Lang.EN) "Not used yet" else "अभी तक उपयोग नहीं हुआ"
        }
    }

    private fun situation(f: Finding, lang: Lang): String {

        val stratum = when (f) {
            is Finding.BaselineDeviation -> f.stratum
            is Finding.UpdateDrift -> f.stratum
            is Finding.SustainedShift -> f.stratum
            is Finding.PolicyViolation -> f.evidence.maxByOrNull { it.timestampMs }?.visibilityStratum
        }

        return when {
            stratum != null -> sentence(FindingText.context(stratum, lang))
            lang == Lang.EN -> "Based on the permissions the app holds. It has not used them yet."
            else -> "ऐप के पास मौजूद अनुमतियों के आधार पर। इसने अभी तक इनका उपयोग नहीं किया है।"
        }
    }

    /** "while the app was in the background" -> "While the app was in the background". */
    private fun sentence(phrase: String): String =
        phrase.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
