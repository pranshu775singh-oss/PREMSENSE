package com.ojas.permsense.ledger

import com.ojas.permsense.model.ContextualisedEpisode
import com.ojas.permsense.model.RawObservation
import com.ojas.permsense.model.Tier
import com.ojas.permsense.model.Visibility
import com.ojas.permsense.processing.UsageContext
import com.ojas.permsense.processing.UsageEventLite
import com.ojas.permsense.processing.UsageKind
import com.ojas.permsense.processing.buildUsageContext
import com.ojas.permsense.processing.groupIntoEpisodes
import com.ojas.permsense.processing.joinContexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

class LedgerCoreTest {

    companion object {
        const val PKG = "com.example"
        const val UID = 10123
        const val OP = "android:read_contacts"
        const val FAR = 10_000_000L
    }

    @get:Rule
    val reporter = object : TestWatcher() {
        override fun succeeded(description: Description) {
            println("PASS [LEDGER] ${description.methodName}")
        }

        override fun failed(e: Throwable, description: Description) {
            System.err.println(
                "FAIL [LEDGER] ${description.methodName}: " +
                        (e.message ?: e.javaClass.simpleName)
            )
        }
    }

    private fun obs(
        id: String,
        timestampMs: Long,
        op: String = OP,
        flags: Int = 0,
        tier: Tier = Tier.T2,
    ) = RawObservation(
        tier = tier,
        sourceEventId = id,
        uid = UID,
        packageName = PKG,
        op = op,
        accessKind = "noted",
        timestampMs = timestampMs,
        flags = flags,
        accessResult = "OBSERVED",
    )

    private fun context(
        screenOff: Boolean = false,
    ): UsageContext {
        val events = mutableListOf(
            UsageEventLite(
                kind = UsageKind.SCREEN_ON,
                packageName = null,
                timeMs = 1L,
            ),
        )

        if (screenOff) {
            events += UsageEventLite(
                kind = UsageKind.SCREEN_OFF,
                packageName = null,
                timeMs = 10_000L,
            )
        }

        return buildUsageContext(
            events = events,
            fromMs = 0L,
            toMs = FAR,
        )
    }

    private fun joined(
        observation: RawObservation,
        ctx: UsageContext = context(),
    ): ContextualisedEpisode =
        joinContexts(
            episodes = groupIntoEpisodes(
                observations = listOf(observation),
                context = ctx,
            ),
            context = ctx,
            versionHistory = emptyMap(),
        ).single()

    private fun record(
        timestampMs: Long,
        previousHash: String = GENESIS_HASH,
        stratum: String = "BACKGROUND",
        flags: Int = 0,
        sourceIds: List<String> = listOf("event"),
        observationCount: Int = 1,
    ): LedgerRecord =
        LedgerRecord(
            timestampMs = timestampMs,
            uid = UID,
            packageName = PKG,
            versionCode = 1L,
            op = OP,
            accessKind = "noted",
            accessResult = "OBSERVED",
            durationMs = null,
            attributionTag = null,
            flags = flags,
            screenState = "SCREEN_ON",
            visibilityStratum = stratum,
            sourceTier = "T2",
            observationCount = observationCount,
            sourceEventIds = sourceIds,
            prevHash = previousHash,
        )

    @Test
    fun hashChain_sealAndVerify() {
        var previous = GENESIS_HASH

        val rows = (0 until 5).map { index ->
            sealRecord(
                record(
                    timestampMs = index * 1_000L,
                    previousHash = previous,
                    sourceIds = listOf("chain-$index"),
                )
            ).also {
                previous = it.hash
            }
        }

        assertTrue(
            "The newly sealed chain is invalid: ${verifyChain(rows)}",
            verifyChain(rows) is ChainVerification.Valid,
        )
    }

    @Test
    fun hashChain_detectsTamperingAtModifiedRow() {
        var previous = GENESIS_HASH

        val rows = (0 until 5).map { index ->
            sealRecord(
                record(
                    timestampMs = index * 1_000L,
                    previousHash = previous,
                    sourceIds = listOf("chain-$index"),
                )
            ).also {
                previous = it.hash
            }
        }

        val tampered = rows.toMutableList().also {
            it[2] = it[2].copy(packageName = "com.attacker")
        }

        val result = verifyChain(tampered)

        assertTrue(
            "Tampering was not detected at index 2: $result",
            result is ChainVerification.Broken &&
                    result.atIndex == 2,
        )
    }

    @Test
    fun canonicalHash_changesWhenEvidenceFieldsChange() {
        val base = record(timestampMs = 1_000L)
        val baseHash = sealRecord(base).hash

        val stratumHash = sealRecord(
            base.copy(visibilityStratum = "SCREEN_OFF")
        ).hash

        val flagsHash = sealRecord(
            base.copy(flags = 8)
        ).hash

        val countHash = sealRecord(
            base.copy(observationCount = 2)
        ).hash

        val resultHash = sealRecord(
            base.copy(accessResult = "REJECTED")
        ).hash

        assertNotEquals(baseHash, stratumHash)
        assertNotEquals(baseHash, flagsHash)
        assertNotEquals(baseHash, countHash)
        assertNotEquals(baseHash, resultHash)
    }

    @Test
    fun contextJoin_resolvedStratumReachesLedgerRecord() {
        val screenOffContext = context(screenOff = true)

        val event = joined(
            obs(
                id = "screen-off-event",
                timestampMs = 20_000L,
            ),
            ctx = screenOffContext,
        )

        val row = event.toLedgerRecord(GENESIS_HASH)

        assertEquals(Visibility.SCREEN_OFF, event.stratum)
        assertEquals("SCREEN_OFF", row.visibilityStratum)
    }

    @Test
    fun writer_appendsAValidLinearChain() {
        val writer = LedgerWriter()
        val ctx = context()

        val first = joined(
            obs("first", 1_000L),
            ctx,
        )

        val second = joined(
            obs("second", 100_000L),
            ctx,
        )

        writer.append(listOf(first), nowMs = 1_000L)
        writer.append(listOf(second), nowMs = 100_000L)
        writer.flush(FAR)

        val rows = writer.all()

        assertEquals("Unexpected ledger row count", 2, rows.size)
        assertEquals(GENESIS_HASH, rows.first().prevHash)
        assertEquals(rows.first().hash, rows[1].prevHash)

        assertTrue(
            "Writer produced an invalid chain: ${writer.verify()}",
            writer.verify() is ChainVerification.Valid,
        )
    }

    @Test
    fun writer_exactReplayDoesNotCreateDuplicateEvidence() {
        val writer = LedgerWriter()

        val event = joined(
            obs("same-event", 1_000L)
        )

        writer.append(listOf(event), nowMs = 1_000L)
        writer.flush(FAR)

        writer.append(listOf(event), nowMs = FAR)
        writer.flush(FAR + 1L)

        assertEquals(
            "Replaying identical evidence created duplicate rows",
            1,
            writer.all().size,
        )

        assertTrue(writer.verify() is ChainVerification.Valid)
    }

    @Test
    fun writer_differentStrataRemainDistinctEvidence() {
        val writer = LedgerWriter()

        val background = joined(
            obs("background-event", 1_000L),
            context(),
        )

        val screenOff = joined(
            obs("screen-off-event", 30_000L),
            context(screenOff = true),
        )

        assertEquals(Visibility.BACKGROUND, background.stratum)
        assertEquals(Visibility.SCREEN_OFF, screenOff.stratum)

        writer.append(listOf(background), nowMs = 1_000L)
        writer.flush(FAR)

        writer.append(listOf(screenOff), nowMs = FAR)
        writer.flush(FAR + 1L)

        val rows = writer.all()

        assertEquals(
            "Evidence from different strata was incorrectly merged",
            2,
            rows.size,
        )

        assertTrue(rows.any { it.visibilityStratum == "BACKGROUND" })
        assertTrue(rows.any { it.visibilityStratum == "SCREEN_OFF" })
    }

    @Test
    fun writer_differentFlagsRemainDistinctEvidence() {
        val writer = LedgerWriter()
        val ctx = context()

        val one = joined(
            obs(
                id = "flags-one",
                timestampMs = 1_000L,
                flags = 1,
            ),
            ctx,
        )

        val eight = joined(
            obs(
                id = "flags-eight",
                timestampMs = 1_000L,
                flags = 8,
            ),
            ctx,
        )

        writer.append(listOf(one), nowMs = 1_000L)
        writer.flush(FAR)

        writer.append(listOf(eight), nowMs = FAR)
        writer.flush(FAR + 1L)

        assertEquals(
            "Evidence with different flags was incorrectly merged",
            2,
            writer.all().size,
        )

        assertTrue(writer.all().any { it.flags == 1 })
        assertTrue(writer.all().any { it.flags == 8 })
    }
}