package com.ojas.permsense.wire

import com.ojas.permsense.ledger.sha256Hex
import com.ojas.permsense.model.RawObservation
import com.ojas.permsense.model.Tier
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

class WireTest {

    private val packages = (1..120).map { "com.example.application.number$it" }
    private val ops = listOf(
        "android:read_contacts", "android:camera", "android:record_audio", "android:fine_location",
        "android:coarse_location", "android:read_call_log", "android:read_sms", "android:read_clipboard",
    )

    private fun observation(i: Int, rng: Random) = RawObservation(
        tier = Tier.values()[rng.nextInt(Tier.values().size)],
        sourceEventId = sha256Hex("event-$i".toByteArray()),
        uid = 10000 + rng.nextInt(400),
        packageName = packages[rng.nextInt(packages.size)],
        op = ops[rng.nextInt(ops.size)],
        accessKind = listOf("presence", "noted", "started", "rejected")[rng.nextInt(4)],
        timestampMs = 1_760_000_000_000L + rng.nextLong(0, 1_000_000_000L),
        durationMs = if (rng.nextBoolean()) rng.nextLong(0, 100_000L) else null,
        attributionTag = if (rng.nextInt(4) == 0) "tag${rng.nextInt(5)}" else null,
        flags = rng.nextInt(0, 32),
        opMode = if (rng.nextBoolean()) rng.nextInt(0, 5) else null,
        uidState = if (rng.nextBoolean()) listOf(100, 200, 400, 500, 600, 700)[rng.nextInt(6)] else null,
        accessResult = if (rng.nextInt(5) == 0) null else listOf("ALLOWED", "REJECTED", "OBSERVED")[rng.nextInt(3)],
    )

    private fun many(n: Int, seed: Int = 1) = Random(seed).let { rng -> (0 until n).map { observation(it, rng) } }

    // ------------------------------------------------------------------ observations

    @Test
    fun everyFieldSurvivesARoundTrip() {
        val original = many(500)

        assertEquals(original, ObservationWire.decode(ObservationWire.encode(original)))
    }

    @Test
    fun anEmptyListRoundTrips() {
        assertEquals(emptyList<RawObservation>(), ObservationWire.decode(ObservationWire.encode(emptyList())))
    }

    @Test
    fun idsThatAreNotHashesAndOddValuesRoundTrip() {
        val odd = RawObservation(
            tier = Tier.T2,
            sourceEventId = "not-a-hash",
            uid = -1,
            packageName = "pkg.with.unicode.é中",
            op = "android:x",
            accessKind = "",
            timestampMs = -5L,
            durationMs = Long.MAX_VALUE,
            attributionTag = "",
            flags = Int.MIN_VALUE,
            opMode = -1,
            uidState = Int.MAX_VALUE,
            accessResult = "",
        )

        assertEquals(listOf(odd), ObservationWire.decode(ObservationWire.encode(listOf(odd))))
    }

    @Test
    fun theEncodingIsDeterministic() {
        val data = many(300)

        assertArrayEquals(ObservationWire.encode(data), ObservationWire.encode(data))
    }

    @Test
    fun aRealisticSnapshotFitsComfortablyInsideOneBinderTransaction() {
        // The failure being fixed: several thousand AppOps entries written as UTF-16 strings
        // overflow Binder's 1 MB limit. 8,000 entries is a typical phone.
        val bytes = ObservationWire.encode(many(8_000))

        val perObservation = bytes.size / 8_000.0
        assertTrue("$perObservation bytes each", perObservation < 100)
        // And even if it did not, it is sent in chunks, so the whole payload is never one transaction.
        assertTrue(bytes.size > BlobLimits.MAX_CHUNK_BYTES)
    }

    @Test
    fun theOldParcelFormatWouldHaveBeenTooBigForTheSamePhone() {
        // What the original code wrote per observation: 5 strings as UTF-16 (the 64 char id alone
        // is 128 bytes) plus 8 ints/longs and framing. Documented here so the saving is checkable.
        val o = many(1).single()
        val parcelBytes = 4 + (4 + 2 * (o.sourceEventId.length + 1)) + 4 + (4 + 2 * (o.packageName.length + 1)) +
                (4 + 2 * (o.op.length + 1)) + (4 + 2 * (o.accessKind.length + 1)) + 8 + 4 + 8 + 4 + 4 + 4 + 4 + 4 + 4

        assertTrue("parcel ~$parcelBytes bytes", parcelBytes > 300)
        assertTrue(8_000 * parcelBytes > 1024 * 1024)
    }

    @Test
    fun truncatedDataIsAnErrorAtEveryLength() {
        val bytes = ObservationWire.encode(many(20))

        for (length in 0 until bytes.size) {
            try {
                ObservationWire.decode(bytes.copyOf(length))
                fail("a $length byte prefix of ${bytes.size} decoded")
            } catch (_: WireFormatException) {
            }
        }
    }

    @Test
    fun trailingBytesAreAnError() {
        val bytes = ObservationWire.encode(many(5)) + byteArrayOf(0)

        try {
            ObservationWire.decode(bytes)
            fail()
        } catch (_: WireFormatException) {
        }
    }

    @Test
    fun wrongMagicAndVersionAreRejected() {
        val bytes = ObservationWire.encode(many(5))

        val badMagic = bytes.copyOf().also { it[0] = 0 }
        val badVersion = bytes.copyOf().also { it[4] = 99 }

        for (bad in listOf(badMagic, badVersion)) {
            try {
                ObservationWire.decode(bad)
                fail()
            } catch (_: WireFormatException) {
            }
        }
    }

    @Test
    fun randomDamageNeverEscapesAsAnythingButAWireFormatException() {
        val bytes = ObservationWire.encode(many(50))
        val rng = Random(1234)

        repeat(3_000) {
            val damaged = bytes.copyOf()
            repeat(1 + rng.nextInt(4)) { damaged[rng.nextInt(damaged.size)] = rng.nextInt(256).toByte() }
            try {
                ObservationWire.decode(damaged)
            } catch (_: WireFormatException) {
                // Either outcome is fine; a crash, hang or OutOfMemoryError is not.
            }
        }
    }

    @Test
    fun anAbsurdCountIsRefusedBeforeAnythingIsAllocated() {
        val bytes = ObservationWire.encode(emptyList()).toMutableList()
        // After magic(4) + version(1) + table size(1) comes the count: make it huge.
        bytes.removeAt(bytes.size - 1)
        bytes.addAll(listOf(0xFF, 0xFF, 0xFF, 0xFF, 0x0F).map { it.toByte() })

        try {
            ObservationWire.decode(bytes.toByteArray())
            fail()
        } catch (_: WireFormatException) {
        }
    }

    // ------------------------------------------------------------------ snapshots

    @Test
    fun aSnapshotRoundTripsWithItsCounters() {
        val payload = SnapshotPayload(
            capturedAtMs = 1_760_000_123_456L,
            packageCount = 211,
            operationCount = 5_432,
            accessedOperationCount = 4_100,
            rejectedOperationCount = 77,
            observations = many(1_000),
        )

        assertEquals(payload, SnapshotWire.decode(SnapshotWire.encode(payload)))
    }

    @Test
    fun theOperationsASnapshotCouldNotReadTravelWithIt() {
        val payload = SnapshotPayload(
            capturedAtMs = 5L, packageCount = 3, operationCount = 7, accessedOperationCount = 4, rejectedOperationCount = 1,
            observations = many(5),
            skipped = listOf("android:camera: TransactionTooLargeException", "android:record_audio: DeadObjectException"),
        )

        val decoded = SnapshotWire.decode(SnapshotWire.encode(payload))

        assertEquals(payload.skipped, decoded.skipped)
        assertEquals(payload, decoded)
    }

    @Test
    fun aSnapshotThatSkippedNothingSaysSo() {
        val decoded = SnapshotWire.decode(SnapshotWire.encode(SnapshotPayload(1, 1, 1, 1, 0, many(2))))

        assertEquals(emptyList<String>(), decoded.skipped)
    }

    @Test
    fun anImplausibleSkippedCountIsRejectedNotAllocated() {
        val good = SnapshotWire.encode(SnapshotPayload(1, 1, 1, 1, 0, many(1)))
        // magic(4) + captured(8) + four counters(16) puts the skipped count at offset 28.
        val bad = good.copyOf()
        bad[28] = 0x7F; bad[29] = 0x00; bad[30] = 0x00; bad[31] = 0x00

        try {
            SnapshotWire.decode(bad)
            fail()
        } catch (_: WireFormatException) {
        }
    }

    @Test
    fun anOlderSnapshotFormatIsNotMistakenForThisOne() {
        val old = byteArrayOf(0x50, 0x53, 0x4E, 0x50) + ByteArray(40)

        try {
            SnapshotWire.decode(old)
            fail()
        } catch (_: WireFormatException) {
        }
    }

    @Test
    fun aDamagedSnapshotIsRejected() {
        val bytes = SnapshotWire.encode(SnapshotPayload(1, 1, 1, 1, 0, many(10)))

        try {
            SnapshotWire.decode(bytes.copyOf(bytes.size - 3))
            fail()
        } catch (_: WireFormatException) {
        }
    }

    // ------------------------------------------------------------------ blobs

    @Test
    fun aBlobIsReadBackInChunksThatReassembleExactly() {
        val data = Random(5).nextBytes(5 * 1024 * 1024 + 123)
        val store = BlobStore()
        val info = store.put(data)

        val assembler = BlobAssembler(info)
        while (!assembler.isComplete) {
            val chunk = store.read(info.id, assembler.nextOffset, BlobLimits.MAX_CHUNK_BYTES)!!
            assertTrue(chunk.size <= BlobLimits.MAX_CHUNK_BYTES)
            assembler.accept(assembler.nextOffset, chunk)
        }

        assertArrayEquals(data, assembler.result())
    }

    @Test
    fun aChunkNeverExceedsTheLimitEvenIfAskedForMore() {
        val store = BlobStore()
        val info = store.put(ByteArray(1_000_000))

        assertEquals(BlobLimits.MAX_CHUNK_BYTES, store.read(info.id, 0, Int.MAX_VALUE)!!.size)
    }

    @Test
    fun anUnknownOrReleasedBlobReadsAsNull() {
        val store = BlobStore()
        val info = store.put(ByteArray(10))

        assertNull(store.read(info.id + 1, 0, 10))
        store.release(info.id)
        assertNull(store.read(info.id, 0, 10))
    }

    @Test
    fun aBlobExpiresAfterItsTimeToLive() {
        var now = 1_000L
        val store = BlobStore(ttlMs = 100L, clock = { now })
        val info = store.put(ByteArray(10))

        now += 50
        assertNotNull(store.read(info.id, 0, 10))
        now += 100
        assertNull(store.read(info.id, 0, 10))
        assertEquals(0, store.count())
    }

    @Test
    fun theStoreKeepsAtMostAFewBlobs() {
        val store = BlobStore(maxBlobs = 3)
        val infos = (1..5).map { store.put(ByteArray(10) { _ -> it.toByte() }) }

        assertEquals(3, store.count())
        assertNull(store.read(infos[0].id, 0, 10))
        assertNotNull(store.read(infos[4].id, 0, 10))
    }

    @Test
    fun blobIdsAreDistinctPositiveAndNotSequential() {
        val store = BlobStore(maxBlobs = 1000)
        val ids = (1..500).map { store.put(ByteArray(1)).id }

        assertEquals(500, ids.toSet().size)
        assertTrue(ids.all { it > 0 })
        assertFalse(ids.zipWithNext().all { (a, b) -> b == a + 1 })
    }

    @Test
    fun readingPastTheEndGivesNothingNotAnError() {
        val store = BlobStore()
        val info = store.put(ByteArray(10))

        assertEquals(0, store.read(info.id, 10, 5)!!.size)
        assertNull(store.read(info.id, 11, 5))
        assertNull(store.read(info.id, -1, 5))
    }

    @Test
    fun theAssemblerRejectsOutOfOrderChunks() {
        val info = BlobInfo(1, 10, crc32Of(ByteArray(10)))
        val assembler = BlobAssembler(info)

        try {
            assembler.accept(5, ByteArray(5))
            fail()
        } catch (_: BlobTransferException) {
        }
    }

    @Test
    fun theAssemblerRejectsAShortTransfer() {
        val data = ByteArray(10) { it.toByte() }
        val assembler = BlobAssembler(BlobInfo(1, 10, crc32Of(data)))
        assembler.accept(0, data.copyOf(6))

        try {
            assembler.result()
            fail()
        } catch (_: BlobTransferException) {
        }
    }

    @Test
    fun theAssemblerRejectsADamagedTransfer() {
        val data = ByteArray(10) { it.toByte() }
        val assembler = BlobAssembler(BlobInfo(1, 10, crc32Of(data)))
        assembler.accept(0, data.copyOf().also { it[3] = 99 })

        try {
            assembler.result()
            fail()
        } catch (_: BlobTransferException) {
        }
    }

    @Test
    fun theAssemblerRejectsAnEmptyChunkBeforeTheEnd() {
        val assembler = BlobAssembler(BlobInfo(1, 10, 0))

        try {
            assembler.accept(0, ByteArray(0))
            fail()
        } catch (_: BlobTransferException) {
        }
    }

    @Test
    fun theAssemblerRejectsMoreDataThanAnnounced() {
        val assembler = BlobAssembler(BlobInfo(1, 4, 0))

        try {
            assembler.accept(0, ByteArray(5))
            fail()
        } catch (_: BlobTransferException) {
        }
    }

    @Test
    fun anObservationSnapshotCrossesTheTransferIntact() {
        val payload = SnapshotPayload(9, 150, 8_000, 6_000, 12, many(8_000))
        val store = BlobStore()
        val info = store.put(SnapshotWire.encode(payload))

        val assembler = BlobAssembler(info)
        while (!assembler.isComplete) {
            assembler.accept(assembler.nextOffset, store.read(info.id, assembler.nextOffset, BlobLimits.MAX_CHUNK_BYTES)!!)
        }

        assertEquals(payload, SnapshotWire.decode(assembler.result()))
    }
}
