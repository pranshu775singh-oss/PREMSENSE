package com.ojas.permsense.export

import com.ojas.permsense.findings.Finding
import com.ojas.permsense.i18n.FindingText
import com.ojas.permsense.i18n.Lang
import com.ojas.permsense.ledger.ChainStart
import com.ojas.permsense.ledger.LedgerRecord
import com.ojas.permsense.ledger.LedgerRecordKind
import com.ojas.permsense.remediation.Tag
import java.time.ZoneId

/**
 * The human-readable twin of the evidence file: what happened, when, why it was flagged, what was
 * done about it, and plainly what none of it proves. One self-contained HTML page, printable.
 */
object EvidenceReport {

    fun render(
        input: ExportInput,
        digest: String,
        lang: Lang,
        /** Display name for a package, if the phone has one. */
        appLabel: (String) -> String = { it },
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {

        val hi = lang == Lang.HI
        fun t(en: String, hindi: String) = if (hi) hindi else en
        fun time(ms: Long) = FindingText.time(ms, lang, zone)

        val rows = input.rows
        val first = rows.minOfOrNull { it.timestampMs }
        val last = rows.maxOfOrNull { it.timestampMs }

        val sb = StringBuilder()
        sb.append("<!doctype html><html lang=\"${lang.tag}\"><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        sb.append("<title>${esc(t("PermSense evidence report", "PermSense साक्ष्य रिपोर्ट"))}</title>")
        sb.append(STYLE)
        sb.append("</head><body><main>")

        sb.append("<h1>").append(esc(t("PermSense evidence report", "PermSense साक्ष्य रिपोर्ट"))).append("</h1>")
        sb.append("<p class=\"meta\">")
            .append(esc(t("Made on ", "बनाई गई: "))).append(esc(time(input.nowMs)))
            .append(" &middot; PermSense ").append(esc(input.appVersion))
            .append("</p>")

        // ---------------------------------------------------------------- limits first
        sb.append("<section class=\"limits\"><h2>")
            .append(esc(t("What this report does and does not show", "यह रिपोर्ट क्या दिखाती है और क्या नहीं")))
            .append("</h2><p>")
            .append(esc(if (hi) EvidenceExport.STATEMENT_HI else EvidenceExport.STATEMENT_EN))
            .append("</p></section>")

        // ---------------------------------------------------------------- summary
        sb.append("<section><h2>").append(esc(t("Summary", "सारांश"))).append("</h2><ul>")
        sb.append("<li>").append(esc(t("Findings: ", "निष्कर्ष: "))).append(input.findings.size).append("</li>")
        sb.append("<li>").append(esc(t("Ledger records: ", "लेज़र रिकॉर्ड: "))).append(rows.size).append("</li>")
        if (first != null && last != null) {
            sb.append("<li>").append(esc(t("Period covered: ", "अवधि: ")))
                .append(esc(time(first))).append(" – ").append(esc(time(last))).append("</li>")
        }
        sb.append("<li>").append(esc(t("Signed checkpoints: ", "हस्ताक्षरित चेकपॉइंट: ")))
            .append(input.checkpoints.count { it.signatureBytes.isNotEmpty() }).append("</li>")
        val attested = input.signer.attestationChainDer().isNotEmpty()
        sb.append("<li>").append(esc(t("Signing key proven to be in secure hardware: ", "हस्ताक्षर कुंजी सुरक्षित हार्डवेयर में होने का प्रमाण: ")))
            .append(esc(if (attested) t("a certificate chain is included", "प्रमाणपत्र श्रृंखला शामिल है") else t("no", "नहीं")))
            .append("</li>")
        if (input.chainStart != ChainStart.GENESIS) {
            sb.append("<li>").append(esc(t(
                "Records older than 90 days were summarised into daily counts to protect your privacy.",
                "आपकी निजता की रक्षा के लिए 90 दिन से पुराने रिकॉर्ड दैनिक गिनती में समेट दिए गए हैं।",
            ))).append("</li>")
        }
        sb.append("</ul></section>")

        // ---------------------------------------------------------------- findings
        sb.append("<section><h2>").append(esc(t("Findings", "निष्कर्ष"))).append("</h2>")
        if (input.findings.isEmpty()) {
            sb.append("<p>").append(esc(t("Nothing was flagged.", "कुछ भी चिह्नित नहीं हुआ।"))).append("</p>")
        }
        for (f in input.findings) {
            val card = FindingText.card(f, lang)
            sb.append("<article class=\"finding sev${f.severity}\">")
            sb.append("<h3>").append(esc(card.title)).append("</h3>")
            sb.append("<p><b>").append(esc(t("App", "ऐप"))).append(":</b> ")
                .append(esc(appLabel(f.packageName))).append(" (").append(esc(f.packageName)).append(")</p>")
            sb.append("<p><b>").append(esc(t("When", "कब"))).append(":</b> ")
                .append(esc(f.latestEvidenceMs?.let { time(it) } ?: t("Not used yet; based on what the app is allowed to do.", "अभी उपयोग नहीं हुआ; ऐप को जो अनुमति है उस पर आधारित।")))
                .append("</p>")
            sb.append("<p><b>").append(esc(t("Why it was flagged", "क्यों चिह्नित किया गया"))).append(":</b> ")
                .append(esc(card.why)).append("</p>")
            if (f is Finding.PolicyViolation) {
                sb.append("<p class=\"basis\"><b>").append(esc(t("Basis", "आधार"))).append(":</b> ")
                    .append(esc(f.citation)).append("</p>")
            }
            if (f.evidence.isNotEmpty()) {
                sb.append("<details><summary>").append(esc(t("Evidence", "साक्ष्य")))
                    .append(" (").append(f.evidence.size).append(")</summary><ul>")
                for (e in f.evidence.take(25)) {
                    sb.append("<li>").append(esc(time(e.timestampMs))).append(" &middot; ")
                        .append(esc(FindingText.op(e.op, lang))).append(" &middot; ")
                        .append(esc(FindingText.context(e.visibilityStratum, lang)))
                        .append(" &middot; <code>").append(esc(e.hash.take(16))).append("…</code></li>")
                }
                if (f.evidence.size > 25) sb.append("<li>…</li>")
                sb.append("</ul></details>")
            }
            sb.append("</article>")
        }
        sb.append("</section>")

        // ---------------------------------------------------------------- actions taken
        val actions = rows.filter {
            it.recordKind == LedgerRecordKind.REMEDIATION.name && Tag.parse(it.attributionTag)["phase"] == "RESULT"
        }
        sb.append("<section><h2>").append(esc(t("Changes made through PermSense", "PermSense से किए गए बदलाव"))).append("</h2>")
        if (actions.isEmpty()) {
            sb.append("<p>").append(esc(t("None.", "कोई नहीं।"))).append("</p>")
        } else {
            sb.append("<ul>")
            for (a in actions) sb.append("<li>").append(esc(describeAction(a, hi, ::time, appLabel))).append("</li>")
            sb.append("</ul>")
        }
        sb.append("</section>")

        // ---------------------------------------------------------------- how to check
        sb.append("<section><h2>").append(esc(t("How to check this report", "इस रिपोर्ट की जाँच कैसे करें"))).append("</h2><p>")
            .append(esc(t(
                "The machine-readable file that goes with this page can be checked by anyone with the free " +
                        "permsense-verify tool, without installing or trusting PermSense. It rehashes every record, " +
                        "checks every signature and tells you if even one character was changed.",
                "इस पृष्ठ के साथ आने वाली मशीन-पठनीय फ़ाइल की जाँच कोई भी मुफ़्त permsense-verify टूल से कर सकता है, " +
                        "PermSense को इंस्टॉल या उस पर भरोसा किए बिना। यह हर रिकॉर्ड को दोबारा हैश करता है, हर हस्ताक्षर जाँचता है " +
                        "और बताता है कि एक अक्षर भी बदला गया है या नहीं।",
            ))).append("</p><p><b>").append(esc(t("Fingerprint of the file", "फ़ाइल का फ़िंगरप्रिंट")))
            .append(":</b> <code>").append(digest).append("</code></p>")

        input.pack?.let { pack ->
            sb.append("<p>").append(esc(t("Rule pack", "नियम पैक"))).append(": ")
                .append(esc(pack.packId)).append(" ").append(esc(pack.packVersion)).append("</p>")
            val review = pack.rulesAwaitingReview()
            if (review.isNotEmpty()) {
                sb.append("<p class=\"caveat\">").append(esc(t(
                    "Caution: the regulation references for ${review.joinToString()} have not yet been confirmed by a reviewer.",
                    "सावधानी: ${review.joinToString()} के नियामक संदर्भों की अभी किसी समीक्षक ने पुष्टि नहीं की है।",
                ))).append("</p>")
            }
        }
        sb.append("</section>")

        sb.append("</main></body></html>")
        return sb.toString()
    }

    private fun describeAction(
        row: LedgerRecord,
        hi: Boolean,
        time: (Long) -> String,
        appLabel: (String) -> String,
    ): String {
        val tag = Tag.parse(row.attributionTag)
        val what = row.accessKind.lowercase().replace('_', ' ')
        val undo = if (tag["undoes"] != null) (if (hi) " (पिछला बदलाव वापस लिया गया)" else " (undoing an earlier change)") else ""
        val outcome = when (row.accessResult) {
            "SUCCESS" -> if (hi) "सफल" else "done"
            "NOT_APPLIED" -> if (hi) "लागू नहीं हुआ" else "did not take effect"
            else -> if (hi) "विफल" else "failed"
        }
        return "${time(row.timestampMs)}: $what – ${appLabel(row.packageName)} – ${row.op} " +
                "(${tag["prior"] ?: "?"} → ${tag["new"] ?: "?"}) – $outcome$undo"
    }

    private fun esc(text: String): String =
        buildString(text.length) {
            for (c in text) {
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(c)
                }
            }
        }

    private const val STYLE = "<style>" +
            "body{font:16px/1.5 system-ui,sans-serif;margin:0;background:#f6f6f4;color:#1b1b1b}" +
            "main{max-width:760px;margin:0 auto;padding:16px}" +
            "h1{font-size:1.5rem}h2{font-size:1.15rem;margin-top:1.6rem;border-bottom:1px solid #ccc;padding-bottom:4px}" +
            ".meta{color:#555}.limits{background:#fff7e0;border:1px solid #e3c86b;padding:4px 14px;border-radius:8px}" +
            ".finding{background:#fff;border-left:6px solid #888;padding:8px 14px;margin:12px 0;border-radius:6px}" +
            ".sev3{border-color:#c0392b}.sev2{border-color:#d68910}.sev1{border-color:#2e86c1}" +
            ".basis{font-size:.9rem;color:#444}.caveat{color:#8a4b00;font-weight:600}" +
            "code{background:#eee;padding:1px 4px;border-radius:3px;font-size:.85em;word-break:break-all}" +
            "@media print{body{background:#fff}}" +
            "</style>"
}
