package com.ojas.permsense.ledger

import com.ojas.permsense.model.ContextualisedEpisode
import com.ojas.permsense.model.Episode
import com.ojas.permsense.model.RawObservation
import com.ojas.permsense.model.Tier
import com.ojas.permsense.model.Visibility

/** Hand-built episodes for tests that exercise the ledger itself rather than the normaliser. */
object LedgerFx {

    const val UID = 10123
    const val PKG = "com.sample.app"
    const val OP = "android:read_contacts"

    fun observation(
        id: String,
        ts: Long,
        pkg: String = PKG,
        op: String = OP,
        uid: Int = UID,
        tier: Tier = Tier.T2,
    ) = RawObservation(
        tier = tier,
        sourceEventId = id,
        uid = uid,
        packageName = pkg,
        op = op,
        accessKind = "noted",
        timestampMs = ts,
        accessResult = "OBSERVED",
    )

    /** One episode covering the given observation ids. */
    fun episode(
        ids: List<String>,
        startMs: Long,
        endMs: Long = startMs + 500L,
        pkg: String = PKG,
        op: String = OP,
        stratum: Visibility = Visibility.BACKGROUND,
        version: Long = 101L,
    ): ContextualisedEpisode {
        val observations = ids.mapIndexed { i, id -> observation(id, startMs + i, pkg, op) }
        val ep = Episode(
            uid = UID,
            packageName = pkg,
            op = op,
            stratum = stratum,
            startMs = startMs,
            endMs = endMs,
            observationCount = observations.size,
            tiers = setOf(Tier.T2),
            sourceEventIds = ids.sorted(),
            accessKinds = setOf("noted"),
            durationMs = null,
            attributionTags = emptySet(),
            flags = 0,
            accessResults = setOf("OBSERVED"),
            observations = observations,
        )
        return ContextualisedEpisode(ep, stratum, "SCREEN_ON", version)
    }

    fun episode(id: String, startMs: Long, pkg: String = PKG, op: String = OP) =
        episode(listOf(id), startMs, pkg = pkg, op = op)
}
