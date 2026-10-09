package com.ojas.permsense.history

import com.ojas.permsense.ledger.sha256Hex
import com.ojas.permsense.model.OpFlags
import com.ojas.permsense.model.RawObservation
import com.ojas.permsense.model.Tier
import com.ojas.permsense.model.UidStates

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

sealed interface HistoryParseResult {

    /**
     * @param accessLines every `Access [..]` line seen inside the section
     * @param unparsedLines access lines that could not be turned into an observation
     */
    data class Parsed(
        val observations: List<RawObservation>,
        val accessLines: Int,
        val unparsedLines: Int,
    ) : HistoryParseResult

    /** The text is not a format this parser knows. No observation is produced, ever. */
    data class Unrecognised(val reason: String) : HistoryParseResult
}

/**
 * Reads the discrete-access section of `dumpsys appops`, the OS's own per-access timeline for
 * camera, microphone and location (kept for about 7 days on Android 13 and later).
 *
 * The format is not a stable API, so this parser fails closed: if the section is missing, or too
 * many access lines do not match, it returns [HistoryParseResult.Unrecognised] and no records.
 * A wrong parse that yields plausible-looking history is worse than no history, for the same
 * reason an empty getHistoricalOps reply is dangerous: it looks like data.
 *
 * Shape of the section, as printed by Android 16 (indentation is not relied on):
 *
 *     Discrete accesses:
 *       Uid: 10144
 *         Package: com.example.app
 *           CAMERA
 *             Attribution: null
 *               Access [top-s] at 2026-10-03 12:08:00.000
 *               Access [top-s] at 2026-10-03 12:08:00.000 for 60000 milliseconds
 *               Access [top-tpd] at 2026-10-07 20:48:00.000 attribution flags=12 with chainId=323
 *
 * `[top-s]` is the app's process state and how the access was made (self, proxied, ...). The
 * state is the UID-state signal the context joiner needs: it was true at the moment of access,
 * unlike a timestamp joined against usage events, and it survives the dump's one-minute rounding.
 */
object AppOpsHistoryParser {

    /** Share of access lines that may fail to parse before the whole dump is rejected. */
    const val MAX_UNPARSED_FRACTION = 0.02

    private val SECTION = Regex("""^Discrete accesses:?\s*$""")
    private val UID = Regex("""^Uid:\s*(\S+)\s*$""")
    private val PACKAGE = Regex("""^Package:\s*(\S+)\s*$""")
    private val ATTRIBUTION = Regex("""^Attribution:\s*(.*?)\s*$""")
    private val OP = Regex("""^[A-Z][A-Z0-9_]+$""")
    private val ACCESS = Regex(
        """^Access \[([a-z]+)-([a-z]+)] at (\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(?:\.\d{1,3})?)""" +
                """(?: for (\d+) milliseconds)?""" +
                """(?:\s+attribution flags=(-?\d+) with chainId=(-?\d+))?\s*$"""
    )
    private val APP_UID = Regex("""^u(\d+)a(\d+)$""")

    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]")

    fun parse(text: String, zone: ZoneId = ZoneId.systemDefault()): HistoryParseResult {

        // A leading byte-order mark is common in files saved on Windows.
        val lines = text.removePrefix("﻿").lineSequence().map { it.trim() }.iterator()

        var inSection = false
        var sawSection = false

        var uid = -1
        var pkg: String? = null
        var op: String? = null
        var attribution: String? = null

        val observations = ArrayList<RawObservation>()
        var accessLines = 0
        var unparsed = 0

        // Lines inside the section that match nothing we know. A few headers are expected; a
        // section full of them means the format moved on.
        var unknownLines = 0
        var opHeaders = 0

        // Identical lines in one cell are distinct accesses within the same minute. Number them so
        // each keeps its own stable id and a repeat of the same dump produces the same ids.
        val seen = HashMap<String, Int>()

        while (lines.hasNext()) {

            val line = lines.next()
            if (line.isEmpty()) continue

            if (SECTION.matches(line)) {
                inSection = true
                sawSection = true
                continue
            }
            if (!inSection) continue

            val uidMatch = UID.matchEntire(line)
            if (uidMatch != null) {
                uid = parseUid(uidMatch.groupValues[1]) ?: -1
                pkg = null
                op = null
                attribution = null
                continue
            }

            val packageMatch = PACKAGE.matchEntire(line)
            if (packageMatch != null) {
                pkg = packageMatch.groupValues[1]
                op = null
                attribution = null
                continue
            }

            val attributionMatch = ATTRIBUTION.matchEntire(line)
            if (attributionMatch != null) {
                attribution = attributionMatch.groupValues[1].takeUnless { it == "null" || it.isEmpty() }
                continue
            }

            if (OP.matches(line)) {
                op = line
                attribution = null
                opHeaders++
                continue
            }

            val access = ACCESS.matchEntire(line)
            if (access == null) {
                if (line.startsWith("Access")) {
                    accessLines++
                    unparsed++
                } else if (!line.startsWith("Largest chain id")) {
                    unknownLines++
                }
                continue
            }

            accessLines++

            val currentPkg = pkg
            val currentOp = op
            val state = UidStates.fromToken(access.groupValues[1])
            val flags = OpFlags.fromToken(access.groupValues[2])
            val timestamp = parseTime(access.groupValues[3], zone)

            if (uid < 0 || currentPkg == null || currentOp == null || state == null || flags == null || timestamp == null) {
                unparsed++
                continue
            }

            val duration = access.groupValues[4].takeIf { it.isNotEmpty() }?.toLongOrNull()
            val attributionFlags = access.groupValues[5]
            val chainId = access.groupValues[6]

            val identity = listOf(
                uid, currentPkg, currentOp, attribution.orEmpty(), timestamp, state, flags,
                duration ?: -1L, attributionFlags, chainId,
            ).joinToString("|")

            val ordinal = seen.merge(identity, 1, Int::plus)!!

            observations += RawObservation(
                tier = Tier.T1_PLUS,
                sourceEventId = sha256Hex("t1plus|$identity|$ordinal".toByteArray(Charsets.UTF_8)),
                uid = uid,
                packageName = currentPkg,
                op = "android:" + currentOp.lowercase(),
                accessKind = if (duration != null) "finished" else "noted",
                timestampMs = timestamp,
                durationMs = duration,
                attributionTag = attribution,
                flags = flags,
                opMode = null,
                uidState = state,
                accessResult = "OBSERVED",
            )
        }

        if (!sawSection) {
            return HistoryParseResult.Unrecognised("No 'Discrete accesses' section in the AppOps dump.")
        }

        if (accessLines > 0 && observations.isEmpty()) {
            return HistoryParseResult.Unrecognised(
                "The dump has $accessLines access lines but none could be read. The format may have changed."
            )
        }

        // Ops are listed with their accesses; an op header with no access under it, or a section
        // with content nobody recognises, is not an empty history.
        if (accessLines == 0 && (opHeaders > 0 || unknownLines > 0)) {
            return HistoryParseResult.Unrecognised(
                "The discrete section has content but no readable access lines. The format may have changed."
            )
        }

        val bad = unparsed + unknownLines
        val total = accessLines + unknownLines
        if (total > 0 && bad.toDouble() / total > MAX_UNPARSED_FRACTION) {
            return HistoryParseResult.Unrecognised(
                "$bad of $total lines could not be read. The format may have changed."
            )
        }

        return HistoryParseResult.Parsed(
            observations = observations.sortedWith(
                compareBy(
                    { it.timestampMs }, { it.uid }, { it.packageName }, { it.op },
                    { it.durationMs ?: -1L }, { it.sourceEventId },
                )
            ),
            accessLines = accessLines,
            unparsedLines = unparsed,
        )
    }

    /** "10123" or the symbolic "u0a123" (user 0, app id 123). */
    private fun parseUid(token: String): Int? {
        token.toIntOrNull()?.let { return it }
        val m = APP_UID.matchEntire(token) ?: return null
        return m.groupValues[1].toInt() * 100_000 + 10_000 + m.groupValues[2].toInt()
    }

    private fun parseTime(value: String, zone: ZoneId): Long? =
        try {
            LocalDateTime.parse(value, TIME).atZone(zone).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }

    /**
     * How far back the OS keeps discrete accesses: 7 days from Android 13, 24 hours before.
     * Used to know how much history a successful backfill actually covers.
     */
    fun retentionMs(sdkInt: Int): Long =
        if (sdkInt >= 33) 7L * 24L * 60L * 60L * 1000L else 24L * 60L * 60L * 1000L
}
