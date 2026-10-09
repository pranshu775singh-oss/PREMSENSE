package com.ojas.permsense.i18n

import com.ojas.permsense.findings.Finding
import com.ojas.permsense.findings.SuggestedAction
import com.ojas.permsense.model.Ops
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class Lang(val tag: String) {
    EN("en"),
    HI("hi");

    companion object {
        /** The language for a BCP 47 tag such as "hi-IN"; anything unknown is English. */
        fun fromTag(tag: String?): Lang =
            if (tag != null && tag.lowercase(Locale.ROOT).startsWith("hi")) HI else EN
    }
}

/**
 * Everything a person reads about a finding, in their language.
 *
 * The wording lives here, not in the rules, because the rules decide and this explains. Every
 * sentence is built from structured facts (counts, versions, an op), never from parsing English.
 * Hindi follows the same structure; it was written for this project and should be read through by
 * a native speaker before release.
 */
object FindingText {

    data class Card(
        val title: String,
        /** Why it was flagged, in one or two sentences. */
        val why: String,
    )

    // ------------------------------------------------------------------ vocabulary

    fun op(op: String, lang: Lang): String =
        when (lang) {
            Lang.EN -> when (op) {
                Ops.CONTACTS -> "contacts"
                Ops.CALL_LOG -> "call log"
                Ops.SMS -> "text messages"
                Ops.PHONE_STATE -> "phone state"
                Ops.CLIPBOARD -> "clipboard"
                Ops.CAMERA -> "camera"
                Ops.MIC -> "microphone"
                Ops.FINE_LOC -> "precise location"
                Ops.COARSE_LOC -> "approximate location"
                Ops.MEDIA_IMAGES -> "photos"
                Ops.MEDIA_VIDEO -> "videos"
                Ops.MEDIA_AUDIO -> "audio files"
                Ops.EXT_STORAGE -> "files"
                else -> op.removePrefix("android:").replace('_', ' ')
            }
            Lang.HI -> when (op) {
                Ops.CONTACTS -> "संपर्क"
                Ops.CALL_LOG -> "कॉल लॉग"
                Ops.SMS -> "टेक्स्ट संदेश"
                Ops.PHONE_STATE -> "फ़ोन की स्थिति"
                Ops.CLIPBOARD -> "क्लिपबोर्ड"
                Ops.CAMERA -> "कैमरा"
                Ops.MIC -> "माइक्रोफ़ोन"
                Ops.FINE_LOC -> "सटीक लोकेशन"
                Ops.COARSE_LOC -> "अनुमानित लोकेशन"
                Ops.MEDIA_IMAGES -> "फ़ोटो"
                Ops.MEDIA_VIDEO -> "वीडियो"
                Ops.MEDIA_AUDIO -> "ऑडियो फ़ाइलें"
                Ops.EXT_STORAGE -> "फ़ाइलें"
                else -> op.removePrefix("android:").replace('_', ' ')
            }
        }

    /** "while ..." phrase for the context an access happened in. */
    fun context(stratum: String, lang: Lang): String =
        when (lang) {
            Lang.EN -> when (stratum) {
                "VISIBLE" -> "while the app was on screen"
                "FOREGROUND_SERVICE" -> "while the app ran a visible background service"
                "BACKGROUND" -> "while the app was in the background"
                "SCREEN_OFF" -> "while your screen was off or locked"
                else -> "in a situation PermSense could not determine"
            }
            Lang.HI -> when (stratum) {
                "VISIBLE" -> "जब ऐप स्क्रीन पर खुला था"
                "FOREGROUND_SERVICE" -> "जब ऐप एक दिखने वाली बैकग्राउंड सेवा चला रहा था"
                "BACKGROUND" -> "जब ऐप बैकग्राउंड में था"
                "SCREEN_OFF" -> "जब आपकी स्क्रीन बंद या लॉक थी"
                else -> "ऐसी स्थिति में जिसे PermSense पहचान नहीं सका"
            }
        }

    private fun restricted(ruleId: String, lang: Lang): String =
        when (lang) {
            Lang.EN -> when (ruleId) {
                "RBI-01" -> "contacts"
                "RBI-02" -> "call log"
                "RBI-03" -> "files and media"
                "RBI-04" -> "phone and messages"
                else -> "restricted data"
            }
            Lang.HI -> when (ruleId) {
                "RBI-01" -> "संपर्क"
                "RBI-02" -> "कॉल लॉग"
                "RBI-03" -> "फ़ाइलें और मीडिया"
                "RBI-04" -> "फ़ोन और संदेश"
                else -> "प्रतिबंधित डेटा"
            }
        }

    // ------------------------------------------------------------------ cards

    fun card(finding: Finding, lang: Lang): Card =
        when (finding) {
            is Finding.PolicyViolation -> policy(finding, lang)
            is Finding.BaselineDeviation -> baseline(finding, lang)
            is Finding.UpdateDrift -> drift(finding, lang)
            is Finding.SustainedShift -> shift(finding, lang)
        }

    private fun policy(f: Finding.PolicyViolation, lang: Lang): Card {

        val op = f.op?.let { op(it, lang) }.orEmpty()
        val count = f.params["count"] ?: f.evidence.size.toString()
        val id = f.ruleId

        return when (lang) {

            Lang.EN -> when (id) {
                "INV-01" -> Card(
                    "Used your $op while your screen was off",
                    "$count time(s) this app used your $op while it was not on screen, had no visible " +
                            "background service, and your phone's screen was off or locked. (Rule INV-01)",
                )
                "DEBT-01" -> Card(
                    "Allowed to use your $op but never has",
                    "This app has been allowed to use your $op, but PermSense has seen no use of it in " +
                            "${f.params["days"] ?: "30"} days or more. Removing it costs you nothing. (Rule DEBT-01)",
                )
                "NEW-01" -> Card(
                    "New behaviour after an update: $op",
                    "Before version ${f.params["toVersion"]} this app never used your $op. It started right " +
                            "after it updated from version ${f.params["fromVersion"]}. (Rule NEW-01)",
                )
                "RBI-01", "RBI-02", "RBI-03", "RBI-04" -> {
                    val what = restricted(id, lang)
                    when (f.params["state"]) {
                        "used" -> Card(
                            "Lending app accessed your $what",
                            "This is tagged as a digital-lending app, and lending apps are not permitted to " +
                                    "access $what. It did so $count time(s). (Rule $id)",
                        )
                        "granted" -> Card(
                            "Lending app can access your $what",
                            "This is tagged as a digital-lending app, and lending apps are not permitted to " +
                                    "access $what, yet it has been given that access. It has not used it yet. (Rule $id)",
                        )
                        else -> Card(
                            "Lending app asks for your $what",
                            "This is tagged as a digital-lending app, and lending apps are not permitted to " +
                                    "access $what, yet it asks for that access. (Rule $id)",
                        )
                    }
                }
                "RBI-05" -> Card(
                    "Lending app kept using camera, microphone or location",
                    "Lending apps may use these once, to onboard you. This one used them $count time(s) " +
                            "after the first ${f.params["windowMinutes"] ?: "30"} minutes. (Rule RBI-05)",
                )
                "SPY-01" -> Card(
                    "Powerful access combination",
                    "This app has ${spyPowers(f, lang)} together with ${spyWhat(f, lang)}. Together these " +
                            "let an app watch your screen, messages and movements without you noticing. (Rule SPY-01)",
                )
                else -> Card(f.title, f.explanation)
            }

            Lang.HI -> when (id) {
                "INV-01" -> Card(
                    "स्क्रीन बंद होने पर आपका $op इस्तेमाल हुआ",
                    "इस ऐप ने $count बार आपका $op इस्तेमाल किया, जबकि ऐप स्क्रीन पर नहीं था, कोई दिखने वाली " +
                            "बैकग्राउंड सेवा नहीं चल रही थी और आपके फ़ोन की स्क्रीन बंद या लॉक थी। (नियम INV-01)",
                )
                "DEBT-01" -> Card(
                    "आपका $op इस्तेमाल करने की अनुमति है, पर कभी इस्तेमाल नहीं किया",
                    "इस ऐप को आपका $op इस्तेमाल करने की अनुमति मिली है, लेकिन PermSense ने " +
                            "${f.params["days"] ?: "30"} दिन या उससे ज़्यादा में इसका कोई उपयोग नहीं देखा। " +
                            "इसे हटाने से आपका कोई नुकसान नहीं होगा। (नियम DEBT-01)",
                )
                "NEW-01" -> Card(
                    "अपडेट के बाद नया व्यवहार: $op",
                    "वर्शन ${f.params["toVersion"]} से पहले इस ऐप ने आपका $op कभी इस्तेमाल नहीं किया था। " +
                            "यह वर्शन ${f.params["fromVersion"]} से अपडेट होते ही शुरू हो गया। (नियम NEW-01)",
                )
                "RBI-01", "RBI-02", "RBI-03", "RBI-04" -> {
                    val what = restricted(id, lang)
                    when (f.params["state"]) {
                        "used" -> Card(
                            "लोन ऐप ने आपके $what तक पहुँच बनाई",
                            "यह डिजिटल-लेंडिंग ऐप के रूप में चिह्नित है, और लोन ऐप को $what तक पहुँचने की " +
                                    "अनुमति नहीं है। इसने $count बार ऐसा किया। (नियम $id)",
                        )
                        "granted" -> Card(
                            "लोन ऐप आपके $what तक पहुँच सकता है",
                            "यह डिजिटल-लेंडिंग ऐप के रूप में चिह्नित है, और लोन ऐप को $what तक पहुँचने की " +
                                    "अनुमति नहीं है, फिर भी उसे यह पहुँच दी गई है। उसने अभी तक इसका उपयोग नहीं किया है। (नियम $id)",
                        )
                        else -> Card(
                            "लोन ऐप आपके $what की अनुमति माँगता है",
                            "यह डिजिटल-लेंडिंग ऐप के रूप में चिह्नित है, और लोन ऐप को $what तक पहुँचने की " +
                                    "अनुमति नहीं है, फिर भी वह यह पहुँच माँगता है। (नियम $id)",
                        )
                    }
                }
                "RBI-05" -> Card(
                    "लोन ऐप कैमरा, माइक्रोफ़ोन या लोकेशन का इस्तेमाल करता रहा",
                    "लोन ऐप इनका उपयोग केवल एक बार, आपके ऑनबोर्डिंग के लिए, कर सकते हैं। इस ऐप ने पहले " +
                            "${f.params["windowMinutes"] ?: "30"} मिनट के बाद इन्हें $count बार इस्तेमाल किया। (नियम RBI-05)",
                )
                "SPY-01" -> Card(
                    "ख़तरनाक पहुँच का मेल",
                    "इस ऐप के पास ${spyPowers(f, lang)} और ${spyWhat(f, lang)} दोनों हैं। साथ मिलकर ये किसी ऐप को " +
                            "आपकी जानकारी के बिना आपकी स्क्रीन, संदेश और आवाजाही पर नज़र रखने देते हैं। (नियम SPY-01)",
                )
                else -> Card(f.title, f.explanation)
            }
        }
    }

    private fun spyPowers(f: Finding.PolicyViolation, lang: Lang): String {
        val items = buildList {
            if (f.params["accessibility"] == "true") add(if (lang == Lang.EN) "an accessibility service" else "एक एक्सेसिबिलिटी सेवा")
            if (f.params["deviceAdmin"] == "true") add(if (lang == Lang.EN) "device admin rights" else "डिवाइस एडमिन अधिकार")
            if (f.params["notificationListener"] == "true") add(if (lang == Lang.EN) "notification access" else "नोटिफ़िकेशन पढ़ने की पहुँच")
        }
        return items.joinToString(if (lang == Lang.EN) " and " else " और ")
    }

    private fun spyWhat(f: Finding.PolicyViolation, lang: Lang): String {
        val items = buildList {
            if (f.params["backgroundLocation"] == "true") add(if (lang == Lang.EN) "background location" else "बैकग्राउंड लोकेशन")
            if (f.params["sms"] == "true") add(if (lang == Lang.EN) "SMS access" else "एसएमएस की पहुँच")
        }
        return items.joinToString(if (lang == Lang.EN) " and " else " और ")
    }

    private fun baseline(f: Finding.BaselineDeviation, lang: Lang): Card {
        val op = op(f.op, lang)
        val context = context(f.stratum, lang)
        val expected = oneDecimal(f.expectedPerDay)
        return when (lang) {
            Lang.EN -> Card(
                "Unusual $op activity",
                "${f.observed} time(s) in one day $context, but this app normally does this about " +
                        "$expected time(s) a day. It is far outside its own usual pattern.",
            )
            Lang.HI -> Card(
                "$op का असामान्य उपयोग",
                "एक दिन में ${f.observed} बार $context, जबकि यह ऐप आम तौर पर लगभग $expected बार प्रतिदिन ऐसा करता है। " +
                        "यह इसके अपने सामान्य पैटर्न से बहुत अलग है।",
            )
        }
    }

    private fun drift(f: Finding.UpdateDrift, lang: Lang): Card {
        val op = op(f.op, lang)
        val before = oneDecimal(f.ratePerDayBefore)
        val after = oneDecimal(f.ratePerDayAfter)
        val context = context(f.stratum, lang)
        return when (lang) {
            Lang.EN -> Card(
                "More $op use after an update",
                "After version ${f.fromVersion} became ${f.toVersion}, this app's use of your $op $context " +
                        "rose from about $before to about $after times a day.",
            )
            Lang.HI -> Card(
                "अपडेट के बाद $op का उपयोग बढ़ा",
                "वर्शन ${f.fromVersion} से ${f.toVersion} होने के बाद, इस ऐप का आपका $op $context इस्तेमाल करना " +
                        "लगभग $before से बढ़कर लगभग $after बार प्रतिदिन हो गया।",
            )
        }
    }

    private fun shift(f: Finding.SustainedShift, lang: Lang): Card {
        val op = op(f.op, lang)
        val context = context(f.stratum, lang)
        val before = oneDecimal(f.ratePerDayBefore)
        val recent = oneDecimal(f.ratePerDayRecent)
        return when (lang) {
            Lang.EN -> Card(
                "$op use has stayed high",
                "For ${f.daysRaised} day(s) this app has used your $op $context about $recent times a day, " +
                        "up from about $before.",
            )
            Lang.HI -> Card(
                "$op का उपयोग लगातार ज़्यादा बना हुआ है",
                "${f.daysRaised} दिन से यह ऐप आपका $op $context लगभग $recent बार प्रतिदिन इस्तेमाल कर रहा है, " +
                        "जो पहले लगभग $before बार था।",
            )
        }
    }

    // ------------------------------------------------------------------ actions

    fun actionLabel(action: SuggestedAction, lang: Lang): String? =
        when (action) {
            is SuggestedAction.RevokePermission -> {
                val name = com.ojas.permsense.judgement.PermissionOps.opsFor(action.permission).firstOrNull()
                    ?.let { op(it, lang) } ?: action.permission.substringAfterLast('.')
                if (lang == Lang.EN) "Remove $name access" else "$name की पहुँच हटाएँ"
            }
            is SuggestedAction.SetOpMode ->
                if (lang == Lang.EN) "Allow only while the app is open" else "केवल तब अनुमति दें जब ऐप खुला हो"
            is SuggestedAction.OpenAppSettings ->
                if (lang == Lang.EN) "Open app settings" else "ऐप सेटिंग खोलें"
            SuggestedAction.None -> null
        }

    // ------------------------------------------------------------------ times and numbers

    /** A short local date and time, in the language's own conventions. */
    fun time(ms: Long, lang: Lang, zone: ZoneId = ZoneId.systemDefault()): String {
        val locale = if (lang == Lang.HI) Locale("hi", "IN") else Locale.ENGLISH
        return DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", locale).withZone(zone).format(Instant.ofEpochMilli(ms))
    }

    private fun oneDecimal(value: Double): String =
        if (value >= 10) "%.0f".format(Locale.ROOT, value) else "%.1f".format(Locale.ROOT, value)
}
