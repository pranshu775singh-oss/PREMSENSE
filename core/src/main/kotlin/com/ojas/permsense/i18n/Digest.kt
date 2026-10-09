package com.ojas.permsense.i18n

import com.ojas.permsense.findings.Finding
import com.ojas.permsense.findings.FindingRanking

/**
 * The weekly summary: every critical finding, plus a few of the rest.
 *
 * Capped on purpose. A tool that raises a dozen alarms a week is switched off, and then the one
 * that mattered is missed. Critical findings are never held back; the rest are limited to the
 * alert budget and the remainder is counted, not hidden, so the person can still ask for it.
 */
data class Digest(
    val critical: List<Finding>,
    val others: List<Finding>,
    /** Non-critical findings beyond the budget. */
    val withheld: Int,
) {
    val isEmpty: Boolean get() = critical.isEmpty() && others.isEmpty()
    val total: Int get() = critical.size + others.size
}

object DigestBuilder {

    /** Spec target: a median of three or fewer non-critical findings per device per week. */
    const val MAX_NON_CRITICAL = 3

    fun build(ranked: List<Finding>, maxNonCritical: Int = MAX_NON_CRITICAL): Digest {
        val budgeted = FindingRanking.applyBudget(ranked, maxNonCritical)
        val critical = budgeted.filter { it.severity >= 3 }
        val others = budgeted.filter { it.severity < 3 }
        val withheld = ranked.count { it.severity < 3 } - others.size
        return Digest(critical, others, withheld)
    }
}

/** What the weekly notification and the digest screen say, in plain language. */
object DigestText {

    data class Notification(val title: String, val body: String)

    /**
     * The notification for a digest, or null when there is nothing to say (no notification is better
     * than a "nothing to report" one).
     *
     * Deliberately neutral. Someone else may be looking at the phone, and a notification must not
     * announce that the owner is checking for spying: it says there is an app summary, nothing more.
     */
    fun notification(digest: Digest, lang: Lang): Notification? {

        if (digest.isEmpty) return null

        return when (lang) {
            Lang.EN -> Notification(
                title = "Your weekly app summary is ready",
                body = if (digest.critical.isNotEmpty()) {
                    "${digest.total} item(s) to look at, ${digest.critical.size} of them soon."
                } else {
                    "${digest.total} item(s) to look at when you have a moment."
                },
            )
            Lang.HI -> Notification(
                title = "आपका साप्ताहिक ऐप सारांश तैयार है",
                body = if (digest.critical.isNotEmpty()) {
                    "देखने के लिए ${digest.total} बातें, जिनमें ${digest.critical.size} जल्दी देखने लायक हैं।"
                } else {
                    "जब समय मिले, देखने के लिए ${digest.total} बातें।"
                },
            )
        }
    }

    /**
     * The notification for new serious findings, between weekly digests. As neutral as the digest:
     * it names no app and no category, because it may appear while someone else is looking.
     */
    fun alert(count: Int, lang: Lang): Notification =
        when (lang) {
            Lang.EN -> Notification(
                title = "Your app summary has an update",
                body = "$count new item(s) to look at.",
            )
            Lang.HI -> Notification(
                title = "आपके ऐप सारांश में नई जानकारी है",
                body = "देखने के लिए $count नई बातें।",
            )
        }

    /** The line at the top of the digest screen. */
    fun headline(digest: Digest, lang: Lang): String {

        if (digest.isEmpty) {
            return if (lang == Lang.EN) "Nothing needs your attention this week." else "इस सप्ताह आपके ध्यान देने लायक कुछ नहीं है।"
        }

        val c = digest.critical.size
        val o = digest.others.size

        return when (lang) {
            Lang.EN -> buildString {
                if (c > 0) append("$c need${if (c == 1) "s" else ""} attention now")
                if (c > 0 && o > 0) append(", ")
                if (o > 0) append("$o ${if (c > 0) "more " else ""}worth a look")
                append(".")
                if (digest.withheld > 0) append(" ${digest.withheld} lower-priority item(s) are kept out of this summary.")
            }
            Lang.HI -> buildString {
                if (c > 0) append("$c पर तुरंत ध्यान दें")
                if (c > 0 && o > 0) append(", ")
                if (o > 0) append("$o ${if (c > 0) "और " else ""}देखने लायक")
                append("।")
                if (digest.withheld > 0) append(" ${digest.withheld} कम ज़रूरी बातें इस सारांश में शामिल नहीं हैं।")
            }
        }
    }
}
