package com.ojas.permsense.ledger

import com.ojas.permsense.ledger.LedgerFx.episode
import com.ojas.permsense.model.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/**
 * Regression tests for defects found while auditing the original ledger:
 *   - pruning deleted rows by timestamp, which broke the hash chain and made the app unable to
 *     reopen its own ledger;
 *   - a crash that left a half-written tail made every later write unreadable;
 *   - replaying a batch while its episodes were still open double counted them;
 *   - a collector could be told to drop events that were not yet in the ledger.
 */
class LedgerRetentionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val key = LedgerKeyManager.generateAesKey()
    private val signer = SoftwareEcSigner("retention_test")

    private fun open(file: File) = LedgerWriter(EncryptedLedgerStorage(file, key))

    private val day = Retention.DAY_MS

    // -------------------------------------------------------------------- pruning

    @Test
    fun pruningKeepsTheChainVerifiableAndTheLedgerReopenable() {
        val file = File(tmp.root, "a.enc")
        val writer = open(file)

        listOf(10_000L, 100_000L, 200_000L).forEachIndexed { i, ts ->
            writer.append(listOf(episode("e$i", ts)), nowMs = ts)
        }
        writer.flush(nowMs = 1_000_000L)
        assertEquals(3, writer.all().size)

        val result = writer.pruneBefore(150_000L, signer)

        assertEquals(2, result.prunedCount)
        assertNotNull(result.anchorCheckpoint)
        assertEquals(ChainStart.SIGNED_ANCHOR, writer.chainStart())
        assertTrue(writer.verify() is ChainVerification.Valid)

        // The failure this guards against: the first row now chains from a deleted row, and the
        // original code refused to open the ledger at all on the next start.
        val reopened = open(file)
        assertTrue(reopened.verify() is ChainVerification.Valid)
        assertEquals(ChainStart.SIGNED_ANCHOR, reopened.chainStart())
        assertEquals(writer.all().map { it.hash }, reopened.all().map { it.hash })
        assertEquals(writer.absoluteRowCount(), reopened.absoluteRowCount())
    }

    @Test
    fun theAnchorCheckpointIsSignedAndPointsAtTheLastRemovedRow() {
        val writer = LedgerWriter()
        writer.append(listOf(episode("a", 1_000L)), nowMs = 1_000L)
        writer.append(listOf(episode("b", 500_000L)), nowMs = 1_000L)
        writer.flush(nowMs = 2_000_000L)
        val removedLast = writer.all()[0]

        val anchor = writer.pruneBefore(100_000L, signer).anchorCheckpoint!!

        assertEquals(removedLast.hash, anchor.hash)
        assertEquals(1L, anchor.rowCount)
        assertTrue(writer.verifyCheckpoint(anchor, signer))
    }

    @Test
    fun pruningNeverRemovesRowsFromTheMiddleOfTheChain() {
        val writer = LedgerWriter()
        writer.append(listOf(episode("early", 10_000L)), nowMs = 10_000L)
        writer.append(listOf(episode("recent", 900_000L)), nowMs = 10_000L)
        writer.flush(nowMs = 2_000_000L)

        // A backfill arrives late for an old moment. It sits AFTER the recent row in the chain.
        writer.recordBackfill(LedgerFx.observation("late", 20_000L, tier = Tier.T1_PLUS))

        val result = writer.pruneBefore(500_000L, signer)

        // Only the leading row goes. The late row stays until everything before it is old too.
        assertEquals(1, result.prunedCount)
        val timestamps = writer.all().map { it.timestampMs }
        assertTrue(900_000L in timestamps)
        assertTrue(20_000L in timestamps)
        assertTrue(writer.verify() is ChainVerification.Valid)
    }

    @Test
    fun aggregatesPreserveTheEpisodeCountPerCellAndDay() {
        val writer = LedgerWriter()
        val offset = 0L

        // Three episodes on day 0, two on day 1, one other app on day 0.
        val events = listOf(
            "a" to (1 * 3_600_000L), "b" to (3 * 3_600_000L), "c" to (5 * 3_600_000L),
            "d" to (day + 3_600_000L), "e" to (day + 7_200_000L),
        )
        events.forEach { (id, ts) -> writer.append(listOf(episode(id, ts)), nowMs = ts) }
        writer.append(listOf(episode("z", 2 * 3_600_000L, pkg = "com.other")), nowMs = 0L)
        writer.append(listOf(episode("keep", 100 * day)), nowMs = 0L)
        writer.flush(nowMs = 200 * day)

        val result = writer.pruneBefore(50 * day, signer, zoneOffsetMs = offset)

        assertEquals(6, result.prunedCount)
        val aggregates = writer.all().filter { it.recordKind == LedgerRecordKind.AGGREGATE.name }
        assertEquals(3, aggregates.size)

        val byKey = aggregates.associate { Triple(it.packageName, it.timestampMs / day, 0) to it.observationCount }
        assertEquals(3, byKey[Triple(LedgerFx.PKG, 0L, 0)])
        assertEquals(2, byKey[Triple(LedgerFx.PKG, 1L, 0)])
        assertEquals(1, byKey[Triple("com.other", 0L, 0)])
        assertEquals(6, aggregates.sumOf { it.observationCount })
    }

    @Test
    fun pruningTwiceNeitherLosesNorInventsEpisodes() {
        val writer = LedgerWriter()
        (0 until 10).forEach { d ->
            writer.append(listOf(episode("d$d", d * day + 3_600_000L)), nowMs = 0L)
        }
        writer.flush(nowMs = 100 * day)

        writer.pruneBefore(4 * day, signer)
        writer.pruneBefore(8 * day, signer)

        val total = writer.all().sumOf {
            when (it.recordKind) {
                LedgerRecordKind.AGGREGATE.name -> it.observationCount
                else -> 1
            }
        }
        assertEquals(10, total)
        assertTrue(writer.verify() is ChainVerification.Valid)
    }

    @Test
    fun pruningEverythingWithoutASignerStillLeavesAContinuousChain() {
        val file = File(tmp.root, "all.enc")
        val writer = open(file)
        writer.append(listOf(episode("a", 1_000L)), nowMs = 1_000L)
        writer.flush(nowMs = 1_000_000L)
        val lastHash = writer.latestHash()

        // Gap rows produce no aggregate, so the ledger is left with no rows at all.
        writer.wipeAll()
        writer.recordGap(0L, 1L, "TEST")
        val result = writer.pruneBefore(10_000_000L, signer = null)

        assertEquals(1, result.prunedCount)
        assertEquals(0, writer.all().size)
        assertEquals(ChainStart.UNSIGNED_ANCHOR, writer.chainStart())

        // New rows continue from the anchor, not from genesis.
        assertTrue(writer.latestHash() != GENESIS_HASH)
        writer.append(listOf(episode("b", 20_000_000L)), nowMs = 20_000_000L)
        writer.flush(nowMs = 30_000_000L)
        assertEquals(1, writer.all().size)
        assertEquals(result.anchorCheckpoint!!.hash, writer.all().single().prevHash)

        val reopened = open(file)
        assertTrue(reopened.verify() is ChainVerification.Valid)
        assertEquals(1, reopened.all().size)
        assertFalse(lastHash.isEmpty())
    }

    @Test
    fun anUnsignedAnchorIsReportedAsUnsignedNotAsProof() {
        val writer = LedgerWriter()
        writer.append(listOf(episode("a", 1_000L)), nowMs = 1_000L)
        writer.append(listOf(episode("b", 900_000L)), nowMs = 1_000L)
        writer.flush(nowMs = 5_000_000L)

        val anchor = writer.pruneBefore(100_000L, signer = null).anchorCheckpoint!!

        assertEquals(ChainStart.UNSIGNED_ANCHOR, writer.chainStart())
        assertFalse(writer.verifyCheckpoint(anchor, signer))
    }

    @Test
    fun aTamperedFirstRowStillBreaksTheChainAfterPruning() {
        val storage = InMemoryLedgerStorage()
        val writer = LedgerWriter(storage)
        writer.append(listOf(episode("a", 1_000L)), nowMs = 1_000L)
        writer.append(listOf(episode("b", 900_000L)), nowMs = 1_000L)
        writer.flush(nowMs = 5_000_000L)
        writer.pruneBefore(100_000L, signer)

        val rows = writer.all().toMutableList()
        rows[0] = rows[0].copy(packageName = "com.tampered")

        val anchors = storage.allCheckpoints().map { it.hash }.toSet()
        assertTrue(verifyChain(rows, anchors) is ChainVerification.Broken)
    }

    @Test
    fun aFirstRowThatChainsFromNothingKnownIsRejected() {
        val writer = LedgerWriter()
        writer.append(listOf(episode("a", 1_000L)), nowMs = 1_000L)
        writer.flush(nowMs = 1_000_000L)
        val row = writer.all().single().copy(prevHash = "f".repeat(64))

        assertTrue(verifyChain(listOf(sealRecord(row))) is ChainVerification.Broken)
    }

    @Test
    fun retentionCutoffIsAlignedToTheStartOfALocalDay() {
        val offset = 5L * 3_600_000L + 30L * 60_000L   // India, UTC+05:30
        val now = 1_760_000_000_000L
        val cutoff = Retention.cutoffFor(now, offset)

        assertEquals(0L, Math.floorMod(cutoff + offset, day))
        assertTrue(cutoff <= now - Retention.RETENTION_DAYS * day)
        assertTrue(cutoff > now - (Retention.RETENTION_DAYS + 1) * day)
    }

    // -------------------------------------------------------------------- crash safety

    @Test
    fun aCrashTailIsRepairedSoLaterWritesSurviveTheNextReopen() {
        val file = File(tmp.root, "crash.enc")
        val first = open(file)
        first.append(listOf(episode("one", 1_000L)), nowMs = 1_000L)
        first.flush(nowMs = 1_000_000L)

        // The process dies half way through writing a frame.
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(raf.length())
            raf.write(byteArrayOf(0x50, 0x46, 0x52, 0x4D, 0x01, 0x00, 0x00, 0x10, 0x00, 0x00))
        }

        val second = open(file)
        assertEquals(1, second.all().size)
        second.append(listOf(episode("two", 2_000_000L)), nowMs = 2_000_000L)
        second.flush(nowMs = 3_000_000L)
        assertEquals(2, second.all().size)

        // Before the fix this reopened with only the first row: the second was hidden behind the
        // garbage, so evidence silently vanished.
        val third = open(file)
        assertEquals(2, third.all().size)
        assertTrue(third.verify() is ChainVerification.Valid)
    }

    @Test
    fun aFileThatDiedWhileBeingCreatedIsStartedAgain() {
        val file = File(tmp.root, "partial.enc")
        file.writeBytes(byteArrayOf(0x50, 0x4C, 0x45, 0x47, 0x00, 0x00))

        val writer = open(file)

        assertEquals(0, writer.all().size)
        writer.append(listOf(episode("a", 1_000L)), nowMs = 1_000L)
        writer.flush(nowMs = 1_000_000L)
        assertEquals(1, open(file).all().size)
    }

    // -------------------------------------------------------------------- duplicates and acks

    @Test
    fun replayingAnOpenEpisodeDoesNotDoubleCountIt() {
        val writer = LedgerWriter()
        val now = 10_000L   // far too early to seal: the episode is still open

        writer.append(listOf(episode(listOf("a", "b"), startMs = 1_000L)), nowMs = now)
        writer.append(listOf(episode(listOf("a", "b"), startMs = 1_000L)), nowMs = now)

        assertEquals(1, writer.pendingCount())
        writer.flush(nowMs = 1_000_000L)
        assertEquals(2, writer.all().single().observationCount)
    }

    @Test
    fun aLargerReplayReplacesTheOpenEpisodeInsteadOfAddingToIt() {
        val writer = LedgerWriter()
        val now = 10_000L

        writer.append(listOf(episode(listOf("a", "b"), startMs = 1_000L)), nowMs = now)
        // The collector was not acknowledged, so it resends a, b and delivers a new event c.
        writer.append(listOf(episode(listOf("a", "b", "c"), startMs = 1_000L)), nowMs = now)

        assertEquals(1, writer.pendingCount())
        writer.flush(nowMs = 1_000_000L)
        val row = writer.all().single()
        assertEquals(3, row.observationCount)
        assertEquals(listOf("a", "b", "c"), row.sourceEventIds)
    }

    @Test
    fun overlappingEpisodesMergeWithoutCountingTheSharedEventsTwice() {
        val writer = LedgerWriter()
        val now = 10_000L

        writer.append(listOf(episode(listOf("a", "b"), startMs = 1_000L)), nowMs = now)
        writer.append(listOf(episode(listOf("b", "c"), startMs = 1_001L)), nowMs = now)

        writer.flush(nowMs = 1_000_000L)
        val row = writer.all().single()
        assertEquals(3, row.observationCount)
        assertEquals(listOf("a", "b", "c"), row.sourceEventIds)
    }

    @Test
    fun aCollectorMayOnlyBeToldToDropEventsThatAreInTheLedger() {
        val writer = LedgerWriter()

        // Still open: not sealed, so not safe to acknowledge.
        writer.append(listOf(episode(listOf("x", "y"), startMs = 1_000L)), nowMs = 5_000L)
        assertEquals(emptySet<String>(), writer.knownAmong(listOf("x", "y", "never-seen")))

        writer.flush(nowMs = 1_000_000L)
        assertEquals(setOf("x", "y"), writer.knownAmong(listOf("x", "y", "never-seen")))
    }

    @Test
    fun anEventSuppressedAsADuplicateIsStillAcknowledgeable() {
        val writer = LedgerWriter()
        writer.append(listOf(episode(listOf("first"), startMs = 1_000L)), nowMs = 1_000L)
        writer.flush(nowMs = 1_000_000L)

        // A different id for an access that overlaps a sealed row in the same cell and context.
        writer.append(listOf(episode(listOf("echo"), startMs = 1_200L)), nowMs = 1_000_000L)

        assertEquals(1, writer.all().size)
        assertEquals(1L, writer.suppressedCount())
        // Without this the collector would resend "echo" forever and it would never be dropped.
        assertTrue(writer.isKnown("echo"))
    }

    @Test
    fun aVeryLongEpisodeKeepsABoundedIdListButEveryIdIsStillAcknowledgeable() {
        val writer = LedgerWriter()
        val ids = (0 until 300).map { "evt%04d".format(it) }

        writer.append(listOf(episode(ids, startMs = 1_000L, endMs = 600_000L)), nowMs = 1_000L)
        writer.flush(nowMs = 10_000_000L)

        val row = writer.all().single()
        assertEquals(MAX_STORED_SOURCE_IDS, row.sourceEventIds.size)
        assertTrue(row.sourceEventIds.last().startsWith("more:"))
        assertEquals(300, row.observationCount)
        assertEquals(ids.toSet(), writer.knownAmong(ids))
    }

    @Test
    fun theCappedIdListIsDeterministicSoReplayStaysByteIdentical() {
        val ids = (0 until 300).map { "evt%04d".format(it) }
        assertEquals(capSourceIds(ids), capSourceIds(ids.reversed()))
        assertEquals(capSourceIds(ids), capSourceIds(ids + ids))
    }
}
