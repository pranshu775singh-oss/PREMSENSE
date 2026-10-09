package com.ojas.permsense.ledger

import com.ojas.permsense.model.ContextualisedEpisode
import com.ojas.permsense.model.Episode
import com.ojas.permsense.model.RawObservation
import com.ojas.permsense.model.Tier
import com.ojas.permsense.model.Visibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import javax.crypto.spec.SecretKeySpec

class LedgerPersistenceAndSecurityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testKey = LedgerKeyManager.generateAesKey()

    private fun sampleEpisode(
        id: String,
        pkg: String = "com.sample.finance",
        op: String = "android:read_contacts",
        startMs: Long = 100_000L,
        stratum: Visibility = Visibility.BACKGROUND
    ): ContextualisedEpisode {
        val raw = RawObservation(
            tier = Tier.T2,
            sourceEventId = id,
            uid = 10123,
            packageName = pkg,
            op = op,
            accessKind = "noted",
            timestampMs = startMs,
            flags = 0,
            accessResult = "OBSERVED"
        )
        val ep = Episode(
            uid = 10123,
            packageName = pkg,
            op = op,
            stratum = stratum,
            startMs = startMs,
            endMs = startMs + 500L,
            observationCount = 1,
            tiers = setOf(Tier.T2),
            sourceEventIds = listOf(id),
            accessKinds = setOf("noted"),
            durationMs = 500L,
            attributionTags = emptySet(),
            flags = 0,
            accessResults = setOf("OBSERVED"),
            observations = listOf(raw)
        )
        return ContextualisedEpisode(
            episode = ep,
            stratum = stratum,
            screenState = "SCREEN_OFF",
            appVersionAtAccess = 101L
        )
    }

    // -------------------------------------------------------------------------
    // L1: Persistence Across Restarts
    // -------------------------------------------------------------------------

    @Test
    fun l1_persistence_recordsAndLatestHashSurviveRestart() {
        val dbFile = File(tempFolder.root, "ledger_l1.enc")
        val storage1 = EncryptedLedgerStorage(dbFile, testKey)
        val writer1 = LedgerWriter(storage = storage1)

        val ep1 = sampleEpisode("evt-1", startMs = 1_000L)
        val ep2 = sampleEpisode("evt-2", startMs = 100_000L)

        writer1.append(listOf(ep1), nowMs = 1_000L)
        writer1.append(listOf(ep2), nowMs = 100_000L)
        writer1.flush(nowMs = 200_000L)

        val sealed1 = writer1.all()
        assertEquals(2, sealed1.size)
        val hashBeforeRestart = writer1.latestHash()
        assertNotEquals(GENESIS_HASH, hashBeforeRestart)

        // Simulate process termination and restart with a brand new writer instance
        val storage2 = EncryptedLedgerStorage(dbFile, testKey)
        val writer2 = LedgerWriter(storage = storage2)

        val sealed2 = writer2.all()
        assertEquals(2, sealed2.size)
        assertEquals(hashBeforeRestart, writer2.latestHash())
        assertEquals(sealed1[0].hash, sealed2[0].hash)
        assertEquals(sealed1[1].hash, sealed2[1].hash)

        assertTrue(writer2.verify() is ChainVerification.Valid)
    }

    // -------------------------------------------------------------------------
    // L2: Encryption at Rest & Key Invalidation
    // -------------------------------------------------------------------------

    @Test
    fun l2_encryptionAtRest_zeroPlaintextLeakageOnDisk() {
        val dbFile = File(tempFolder.root, "ledger_l2.enc")
        val storage = EncryptedLedgerStorage(dbFile, testKey)
        val writer = LedgerWriter(storage = storage)

        val secretPkg = "com.topsecret.bankingapp"
        val secretOp = "android:access_coarse_location"
        val ep = sampleEpisode("sec-1", pkg = secretPkg, op = secretOp, startMs = 50_000L)

        writer.append(listOf(ep), nowMs = 50_000L)
        writer.flush(nowMs = 200_000L)

        val fileBytes = dbFile.readBytes()
        val fileContentString = String(fileBytes, Charsets.ISO_8859_1)

        assertFalse("Package name leaked as plaintext in storage file", fileContentString.contains(secretPkg))
        assertFalse("Op name leaked as plaintext in storage file", fileContentString.contains(secretOp))
        assertFalse("Access result leaked as plaintext", fileContentString.contains("OBSERVED"))
        assertFalse("Screen state leaked as plaintext", fileContentString.contains("SCREEN_OFF"))
    }

    @Test
    fun l2_encryption_rejectsWrongOrInvalidatedKey() {
        val dbFile = File(tempFolder.root, "ledger_l2_wrong_key.enc")
        val storage = EncryptedLedgerStorage(dbFile, testKey)
        val writer = LedgerWriter(storage = storage)

        writer.append(listOf(sampleEpisode("ep-1")), nowMs = 1_000L)
        writer.flush(nowMs = 200_000L)

        val wrongKey = LedgerKeyManager.generateAesKey()

        try {
            EncryptedLedgerStorage(dbFile, wrongKey)
            fail("Expected KeyInvalidatedException when opening with wrong encryption key")
        } catch (e: LedgerStorageException.KeyInvalidatedException) {
            assertTrue(e.message?.contains("key") == true)
        }
    }

    // -------------------------------------------------------------------------
    // Chain Continuity Across Sessions
    // -------------------------------------------------------------------------

    @Test
    fun chainContinuity_appendsSeamlesslyAfterReopen() {
        val dbFile = File(tempFolder.root, "ledger_continuity.enc")
        val storage1 = EncryptedLedgerStorage(dbFile, testKey)
        val writer1 = LedgerWriter(storage = storage1)

        writer1.append(listOf(sampleEpisode("e1", startMs = 1_000L)), nowMs = 1_000L)
        writer1.flush(nowMs = 100_000L)
        val firstHash = writer1.latestHash()

        // Reopen in session 2
        val storage2 = EncryptedLedgerStorage(dbFile, testKey)
        val writer2 = LedgerWriter(storage = storage2)

        writer2.append(listOf(sampleEpisode("e2", startMs = 120_000L)), nowMs = 120_000L)
        writer2.flush(nowMs = 250_000L)

        val allRows = writer2.all()
        assertEquals(2, allRows.size)
        assertEquals(GENESIS_HASH, allRows[0].prevHash)
        assertEquals(firstHash, allRows[1].prevHash)
        assertEquals(allRows[0].hash, allRows[1].prevHash)

        assertTrue(writer2.verify() is ChainVerification.Valid)
    }

    // -------------------------------------------------------------------------
    // L3: Signed Hash Checkpoints
    // -------------------------------------------------------------------------

    @Test
    fun l3_checkpoints_createAndVerifyValidSignature() {
        val writer = LedgerWriter()
        val signer = SoftwareEcSigner("test_signer_alias")

        writer.append(listOf(sampleEpisode("cp-1", startMs = 5_000L)), nowMs = 5_000L)
        writer.flush(nowMs = 100_000L)

        val checkpoint = writer.createCheckpoint(signer, timestampMs = 150_000L)
        assertNotNull(checkpoint)
        assertEquals(writer.latestHash(), checkpoint.hash)
        assertEquals(1, checkpoint.rowCount)

        // Verify valid checkpoint
        assertTrue(writer.verifyCheckpoint(checkpoint, signer))
    }

    @Test
    fun l3_checkpoints_rejectsTamperedHashOrSignature() {
        val writer = LedgerWriter()
        val signer = SoftwareEcSigner("test_signer_alias")

        writer.append(listOf(sampleEpisode("cp-2", startMs = 5_000L)), nowMs = 5_000L)
        writer.flush(nowMs = 100_000L)

        val checkpoint = writer.createCheckpoint(signer, timestampMs = 150_000L)

        // Tamper with hash
        val tamperedHashCheckpoint = checkpoint.copy(hash = "000000000000000000000000000000000000000000000000000000000000ffff")
        assertFalse(writer.verifyCheckpoint(tamperedHashCheckpoint, signer))

        // Tamper with signature
        val tamperedSig = checkpoint.signatureBytes.clone().also { it[0] = (it[0] + 1).toByte() }
        val tamperedSigCheckpoint = checkpoint.copy(signatureBytes = tamperedSig)
        assertFalse(writer.verifyCheckpoint(tamperedSigCheckpoint, signer))

        // Tamper with rowCount
        val tamperedCountCheckpoint = checkpoint.copy(rowCount = 999L)
        assertFalse(writer.verifyCheckpoint(tamperedCountCheckpoint, signer))
    }

    // -------------------------------------------------------------------------
    // L4: Complete Record Types (EPISODE, GAP, BACKFILL, REMEDIATION)
    // -------------------------------------------------------------------------

    @Test
    fun l4_recordTypes_allParticipateInSingleVerifiableChain() {
        val writer = LedgerWriter()

        // 1. Contextualised Episode
        writer.append(listOf(sampleEpisode("ep-type", startMs = 1_000L)), nowMs = 1_000L)
        writer.flush(nowMs = 100_000L)

        // 2. Collector Outage / Gap
        val gapRecord = writer.recordGap(
            startMs = 110_000L,
            endMs = 140_000L,
            reason = "SHIZUKU_DISCONNECTED",
            sourceTier = "T2"
        )
        assertEquals(LedgerRecordKind.GAP.name, gapRecord.recordKind)
        assertEquals("system:collector_gap", gapRecord.op)

        // 3. Backfill from T1+ dumpsys
        val backfillObs = RawObservation(
            tier = Tier.T1_PLUS,
            sourceEventId = "backfill-dump-1",
            uid = 10200,
            packageName = "com.historical.app",
            op = "android:camera",
            accessKind = "started",
            timestampMs = 50_000L,
            durationMs = 2_000L
        )
        val backfillRecord = writer.recordBackfill(backfillObs, versionCode = 200L)
        assertEquals(LedgerRecordKind.BACKFILL.name, backfillRecord.recordKind)
        assertEquals("com.historical.app", backfillRecord.packageName)

        // 4. Remediation Audit Record
        val remediationRecord = writer.recordRemediation(
            targetPackage = "com.untrusted.lending",
            uid = 10300,
            opOrPermission = "android:read_contacts",
            action = "REVOKE_PERMISSION",
            priorState = "GRANTED",
            newState = "REVOKED",
            success = true,
            timestampMs = 160_000L
        )
        assertEquals(LedgerRecordKind.REMEDIATION.name, remediationRecord.recordKind)
        assertEquals("REVOKE_PERMISSION", remediationRecord.accessKind)
        assertEquals("SUCCESS", remediationRecord.accessResult)

        val rows = writer.all()
        assertEquals(4, rows.size)

        // Verify chain integrity across all 4 record types
        val verification = writer.verify()
        assertTrue("Multi-type record chain is broken: $verification", verification is ChainVerification.Valid)

        // Verify that hash links linearly: row[i].prevHash == row[i-1].hash
        for (i in 1 until rows.size) {
            assertEquals(rows[i - 1].hash, rows[i].prevHash)
        }
    }

    // -------------------------------------------------------------------------
    // L5: Lifecycle, Failure Recovery, Retention & Wipe
    // -------------------------------------------------------------------------

    @Test
    fun l5_startupVerification_detectsCorruptedChainAtStartup() {
        val dbFile = File(tempFolder.root, "ledger_corrupt.enc")
        val storage = EncryptedLedgerStorage(dbFile, testKey)
        val writer = LedgerWriter(storage = storage)

        writer.append(listOf(sampleEpisode("ok-1", startMs = 1_000L)), nowMs = 1_000L)
        writer.flush(nowMs = 100_000L)

        // Now modify the file content in storage directly to create a broken record
        val rawRows = storage.allRows().toMutableList()
        val badRow = rawRows[0].copy(packageName = "com.tampered.pkg")
        // Wipe and insert tampered row
        storage.wipeAll()
        storage.insertRecord(badRow)

        // When a new LedgerWriter starts up, it must detect the corruption
        try {
            LedgerWriter(storage = storage, autoVerifyOnStartup = true)
            fail("Expected CorruptedStorageException on corrupted startup chain")
        } catch (e: LedgerStorageException.CorruptedStorageException) {
            assertTrue(e.message?.contains("broken") == true)
        }
    }

    @Test
    fun l5_failureRecovery_incompleteTrailingWriteHandledCleanly() {
        val dbFile = File(tempFolder.root, "ledger_crash.enc")
        val storage1 = EncryptedLedgerStorage(dbFile, testKey)
        val writer1 = LedgerWriter(storage = storage1)

        writer1.append(listOf(sampleEpisode("safe-1", startMs = 1_000L)), nowMs = 1_000L)
        writer1.flush(nowMs = 100_000L)

        val validCount = writer1.all().size
        val validHash = writer1.latestHash()

        // Simulate crash during partial write: append trailing garbage bytes (partial frame)
        RandomAccessFile(dbFile, "rw").use { raf ->
            raf.seek(raf.length())
            raf.write(byteArrayOf(0x50, 0x46, 0x52, 0x4D, 0x01, 0x00)) // incomplete frame header
        }

        // Reopening storage should gracefully ignore truncated trailing frame and preserve valid chain
        val storage2 = EncryptedLedgerStorage(dbFile, testKey)
        val writer2 = LedgerWriter(storage = storage2)

        assertEquals(validCount, writer2.all().size)
        assertEquals(validHash, writer2.latestHash())
        assertTrue(writer2.verify() is ChainVerification.Valid)
    }

    @Test
    fun l5_retentionAndWipe_pruningAndFullWipe() {
        val dbFile = File(tempFolder.root, "ledger_retention.enc")
        val storage = EncryptedLedgerStorage(dbFile, testKey)
        val writer = LedgerWriter(storage = storage)
        val signer = SoftwareEcSigner("retention_signer")

        // Append 3 records at 10s, 100s, 200s (separated by > 60s so not merged into 1 episode)
        writer.append(listOf(sampleEpisode("r1", startMs = 10_000L)), nowMs = 10_000L)
        writer.append(listOf(sampleEpisode("r2", startMs = 100_000L)), nowMs = 10_000L)
        writer.append(listOf(sampleEpisode("r3", startMs = 200_000L)), nowMs = 10_000L)
        writer.flush(nowMs = 300_000L)

        assertEquals(3, writer.all().size)

        // Prune before 150s: r1 and r2 are removed and collapsed into one daily aggregate
        // (both fall on the same day, same cell). r3 stays as a raw row.
        val pruneResult = writer.pruneBefore(cutoffTimestampMs = 150_000L, signer = signer)
        assertEquals(2, pruneResult.prunedCount)
        assertEquals(1, pruneResult.aggregatesWritten)
        assertEquals(2L, pruneResult.remainingCount)
        assertNotNull(pruneResult.anchorCheckpoint)

        assertEquals(2, writer.all().size)
        assertTrue(writer.all().any { it.timestampMs == 200_000L })
        val aggregate = writer.all().single { it.recordKind == LedgerRecordKind.AGGREGATE.name }
        assertEquals(2, aggregate.observationCount)

        // The chain must still verify after pruning, and after the app restarts.
        assertTrue(writer.verify() is ChainVerification.Valid)
        val reopened = LedgerWriter(storage = EncryptedLedgerStorage(dbFile, testKey))
        assertEquals(writer.all().map { it.hash }, reopened.all().map { it.hash })
        assertTrue(reopened.verify() is ChainVerification.Valid)

        // Full Wipe
        writer.wipeAll()
        assertEquals(0, writer.all().size)
        assertEquals(GENESIS_HASH, writer.latestHash())
    }
}
