package com.ojas.permsense.history

import com.ojas.permsense.model.OpFlags
import com.ojas.permsense.model.Tier
import com.ojas.permsense.model.UidStates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

/**
 * The T1+ history parser, tested against the real `dumpsys appops` format.
 *
 * The original parser looked for lines like "ACCESS 2026-10-08 10:00:00 android:camera", a format
 * Android never prints, and its tests only fed it that same invented format. On a real dump it
 * found nothing. The fixture below is laid out exactly like a dump from a real Android 16 phone,
 * with the package names replaced.
 */
class AppOpsHistoryParserTest {

    private val utc = ZoneId.of("UTC")

    private fun fixture(): String =
        javaClass.getResourceAsStream("/appops_discrete_sample.txt")!!
            .readBytes().toString(Charsets.UTF_8)

    private fun parsed(text: String, zone: ZoneId = utc): HistoryParseResult.Parsed =
        AppOpsHistoryParser.parse(text, zone) as HistoryParseResult.Parsed

    // -------------------------------------------------------------------- the real format

    @Test
    fun readsEveryAccessLineOfARealFormatDump() {
        val result = parsed(fixture())

        assertEquals(22, result.accessLines)
        assertEquals(0, result.unparsedLines)
        assertEquals(22, result.observations.size)
        assertTrue(result.observations.all { it.tier == Tier.T1_PLUS })
    }

    @Test
    fun thePreambleBeforeTheDiscreteSectionIsIgnored() {
        val result = parsed(fixture())

        // "ActiveCallback{... from uid=1000 ...}" lives above the section and must not leak in.
        assertTrue(result.observations.none { it.packageName.contains("Callback") })
        assertTrue(result.observations.all { it.op.startsWith("android:") })
    }

    @Test
    fun carriesTheAppProcessStateOfEachAccess() {
        val byState = parsed(fixture()).observations.groupBy { it.uidState }

        assertTrue(byState.containsKey(UidStates.PERSISTENT))
        assertTrue(byState.containsKey(UidStates.TOP))
        assertTrue(byState.containsKey(UidStates.FOREGROUND))
        assertTrue(byState.containsKey(UidStates.FOREGROUND_SERVICE))
        assertTrue(byState.containsKey(UidStates.BACKGROUND))
        assertTrue(byState.containsKey(UidStates.CACHED))

        val chat = parsed(fixture()).observations.filter { it.packageName == "com.example.chat" }
        assertTrue(chat.all { it.uidState == UidStates.TOP })

        val background = parsed(fixture()).observations
            .filter { it.packageName == "com.example.services" && it.uidState == UidStates.BACKGROUND }
        assertEquals(2, background.size)
    }

    @Test
    fun mapsEveryProcessStateToken() {
        assertEquals(UidStates.PERSISTENT, UidStates.fromToken("pers"))
        assertEquals(UidStates.TOP, UidStates.fromToken("top"))
        assertEquals(UidStates.FOREGROUND_SERVICE_LOCATION, UidStates.fromToken("fgsvcl"))
        assertEquals(UidStates.FOREGROUND_SERVICE, UidStates.fromToken("fgsvc"))
        assertEquals(UidStates.FOREGROUND, UidStates.fromToken("fg"))
        assertEquals(UidStates.BACKGROUND, UidStates.fromToken("bg"))
        assertEquals(UidStates.CACHED, UidStates.fromToken("cch"))
        assertNull(UidStates.fromToken("nonsense"))
    }

    @Test
    fun mapsAccessFlagsIncludingProxiedAccesses() {
        val language = parsed(fixture()).observations.filter { it.packageName == "com.example.language" }

        assertTrue(language.isNotEmpty())
        assertTrue(language.all { it.flags == OpFlags.TRUSTED_PROXIED })

        val own = parsed(fixture()).observations.filter { it.packageName == "com.example.chat" }
        assertTrue(own.all { it.flags == OpFlags.SELF })
    }

    @Test
    fun readsDurationsAndTellsThemApartFromInstantAccesses() {
        val chat = parsed(fixture()).observations
            .filter { it.packageName == "com.example.chat" }
            .sortedBy { it.timestampMs }

        // The OS lists the start of a camera session and its finished duration as two lines.
        assertEquals(listOf(null, 60_000L, null, 60_000L), chat.map { it.durationMs })
        assertEquals(listOf("noted", "finished", "noted", "finished"), chat.map { it.accessKind })
    }

    @Test
    fun readsTheAttributionTagAndTreatsTheWordNullAsNoTag() {
        val services = parsed(fixture()).observations.filter { it.packageName == "com.example.services" }

        val tagged = services.filter { it.attributionTag == "com.example.services.thunderbird" }
        val untagged = services.filter { it.attributionTag == null }

        assertEquals(5, tagged.size)
        assertTrue(untagged.size >= 2)
        assertTrue(parsed(fixture()).observations.none { it.attributionTag == "null" })
    }

    @Test
    fun mapsOpNamesToThePublicOpStringsUsedEverywhereElse() {
        val ops = parsed(fixture()).observations.map { it.op }.toSet()

        assertEquals(
            setOf(
                "android:fine_location", "android:coarse_location", "android:camera",
                "android:record_audio", "android:phone_call_microphone",
            ),
            ops,
        )
    }

    @Test
    fun readsUidsAndPackagesFromTheirOwnLines() {
        val chat = parsed(fixture()).observations.first { it.packageName == "com.example.chat" }
        assertEquals(10144, chat.uid)

        // Two packages can share a uid (here the platform uid 1000).
        val platform = parsed(fixture()).observations.filter { it.uid == 1000 }
        assertEquals(setOf("com.example.platform", "android"), platform.map { it.packageName }.toSet())
    }

    // -------------------------------------------------------------------- time

    @Test
    fun timestampsAreReadInTheDevicesTimeZone() {
        val utcTs = parsed(fixture(), ZoneId.of("UTC")).observations.minOf { it.timestampMs }
        val indiaTs = parsed(fixture(), ZoneId.of("Asia/Kolkata")).observations.minOf { it.timestampMs }

        // The same wall-clock text is 5h30m earlier on the clock in India.
        assertEquals(5L * 3_600_000L + 30L * 60_000L, utcTs - indiaTs)
    }

    @Test
    fun observationsComeBackInTimeOrder() {
        val times = parsed(fixture()).observations.map { it.timestampMs }
        assertEquals(times.sorted(), times)
    }

    // -------------------------------------------------------------------- ids and idempotence

    @Test
    fun identicalLinesAreDistinctAccessesWithDistinctStableIds() {
        val dup = parsed(fixture()).observations.filter { it.packageName == "com.example.duplicates" }

        assertEquals(3, dup.size)
        assertEquals(3, dup.map { it.sourceEventId }.toSet().size)
        assertTrue(dup.all { it.sourceEventId.length == 64 })
    }

    @Test
    fun parsingTheSameDumpTwiceGivesTheSameIds() {
        val a = parsed(fixture()).observations.map { it.sourceEventId }
        val b = parsed(fixture()).observations.map { it.sourceEventId }
        assertEquals(a, b)
    }

    @Test
    fun aLaterDumpThatDropsOldLinesKeepsTheIdsOfTheLinesItStillHas() {
        val full = parsed(fixture()).observations.associateBy { it.sourceEventId }
        val trimmed = fixture().lines().filterNot { it.contains("2026-10-01") }.joinToString("\n")

        val later = parsed(trimmed).observations

        assertTrue(later.size < full.size)
        assertTrue(later.all { it.sourceEventId in full })
    }

    // -------------------------------------------------------------------- failing closed

    @Test
    fun aDumpWithoutTheDiscreteSectionIsRejectedNotTreatedAsNoHistory() {
        val result = AppOpsHistoryParser.parse("Current AppOps Service state:\n  Uid 10123:\n", utc)

        assertTrue(result is HistoryParseResult.Unrecognised)
    }

    @Test
    fun blankTextIsRejectedNotTreatedAsNoHistory() {
        assertTrue(AppOpsHistoryParser.parse("", utc) is HistoryParseResult.Unrecognised)
        assertTrue(AppOpsHistoryParser.parse("   \n \n", utc) is HistoryParseResult.Unrecognised)
    }

    @Test
    fun theOldInventedFormatIsNotAcceptedAsHistory() {
        val invented = """
            Uid 10123
              Package com.example
                ACCESS 2026-10-08 10:00:00 android:camera
                START 2026-10-08 10:00:01 android:location
        """.trimIndent()

        assertTrue(AppOpsHistoryParser.parse(invented, utc) is HistoryParseResult.Unrecognised)
    }

    @Test
    fun anEmptyButWellFormedSectionIsGenuinelyEmpty() {
        val result = parsed("Discrete accesses: \n  Largest chain id: 1\n")

        assertEquals(0, result.observations.size)
        assertEquals(0, result.accessLines)
    }

    @Test
    fun aRenamedAccessKeywordRejectsTheDumpInsteadOfLookingLikeAnEmptyHistory() {
        // Same structure, but the access lines no longer start with "Access".
        val renamed = fixture().replace("Access [", "Event [")

        assertTrue(AppOpsHistoryParser.parse(renamed, utc) is HistoryParseResult.Unrecognised)
    }

    @Test
    fun aChangedBracketStyleRejectsTheDump() {
        val changed = fixture().replace("Access [", "Access <")

        assertTrue(AppOpsHistoryParser.parse(changed, utc) is HistoryParseResult.Unrecognised)
    }

    @Test
    fun opHeadersWithNothingUnderThemAreNotAnEmptyHistory() {
        val text = "Discrete accesses:\n  Uid: 10001\n    Package: com.example.x\n      CAMERA\n"

        assertTrue(AppOpsHistoryParser.parse(text, utc) is HistoryParseResult.Unrecognised)
    }

    @Test
    fun unknownProcessStatesAreCountedAsUnparsedAndRejectWhenTheyAreCommon() {
        val broken = fixture().replace("[top-", "[zzz-")

        val result = AppOpsHistoryParser.parse(broken, utc)

        assertTrue(result is HistoryParseResult.Unrecognised)
    }

    @Test
    fun aFewUnreadableLinesAreToleratedButReported() {
        val many = buildString {
            append("Discrete accesses:\n  Uid: 10001\n    Package: com.example.big\n      CAMERA\n        Attribution: null\n")
            repeat(200) { append("          Access [top-s] at 2026-10-03 12:%02d:00.000\n".format(it % 60)) }
            append("          Access [zzz-s] at 2026-10-03 13:00:00.000\n")
        }

        val result = parsed(many)

        assertEquals(201, result.accessLines)
        assertEquals(1, result.unparsedLines)
        assertEquals(200, result.observations.size)
    }

    @Test
    fun anAccessLineBeforeAnyUidOrPackageIsNotInventedIntoARecord() {
        val orphan = "Discrete accesses:\n          Access [top-s] at 2026-10-03 12:08:00.000\n"

        val result = AppOpsHistoryParser.parse(orphan, utc)

        assertTrue(result is HistoryParseResult.Unrecognised)
    }

    // -------------------------------------------------------------------- odds and ends

    @Test
    fun aByteOrderMarkAtTheStartIsIgnored() {
        assertEquals(22, parsed("﻿" + fixture()).observations.size)
    }

    @Test
    fun windowsLineEndingsAreHandled() {
        assertEquals(22, parsed(fixture().replace("\n", "\r\n")).observations.size)
    }

    @Test
    fun symbolicUidsAreResolved() {
        val text = "Discrete accesses:\n  Uid: u0a134\n    Package: com.example.sym\n      CAMERA\n" +
                "        Attribution: null\n          Access [top-s] at 2026-10-03 12:08:00.000\n"

        assertEquals(10134, parsed(text).observations.single().uid)
    }

    @Test
    fun discreteHistoryRetentionDependsOnTheAndroidVersion() {
        assertEquals(7L * 24 * 3_600_000L, AppOpsHistoryParser.retentionMs(34))
        assertEquals(24L * 3_600_000L, AppOpsHistoryParser.retentionMs(31))
    }

    @Test
    fun aLargeDumpParsesQuickly() {
        val text = buildString {
            append("Discrete accesses:\n")
            repeat(40) { u ->
                append("  Uid: ${10000 + u}\n    Package: com.example.app$u\n      FINE_LOCATION\n        Attribution: null\n")
                repeat(500) { i ->
                    append("          Access [bg-s] at 2026-10-0%d %02d:%02d:00.000\n".format(1 + i % 7, i % 24, i % 60))
                }
            }
        }

        val started = System.nanoTime()
        val result = parsed(text)
        val ms = (System.nanoTime() - started) / 1_000_000

        assertEquals(20_000, result.accessLines)
        assertTrue("took ${ms}ms", ms < 5_000)
    }

    // -------------------------------------------------------------------- the real device dump

    /**
     * Runs only when a real dump is present at private/appops_dump_real.txt (git-ignored: it lists
     * the owner's installed apps). On the dump this project was built from, the original parser
     * returned nothing.
     */
    @Test
    fun parsesTheRealDeviceDumpCompletely() {
        val file = listOf("../private/appops_dump_real.txt", "private/appops_dump_real.txt")
            .map { File(it) }.firstOrNull { it.exists() }
        assumeTrue("no real dump available", file != null)

        val text = file!!.readText(Charsets.UTF_8)
        val expectedLines = text.lineSequence().count { it.trimStart().startsWith("Access [") }

        val result = parsed(text, ZoneId.of("Asia/Kolkata"))

        assertTrue(expectedLines > 1_000)
        assertEquals(expectedLines, result.accessLines)
        assertEquals(0, result.unparsedLines)
        assertEquals(expectedLines, result.observations.size)

        // Every state the real dump contains must have been understood.
        assertTrue(result.observations.all { it.uidState != null })
        assertEquals(
            setOf(
                "android:fine_location", "android:coarse_location", "android:camera",
                "android:record_audio", "android:phone_call_microphone",
            ),
            result.observations.map { it.op }.toSet(),
        )
        println("real dump: ${result.observations.size} accesses from ${result.observations.map { it.packageName }.toSet().size} packages")
    }
}
