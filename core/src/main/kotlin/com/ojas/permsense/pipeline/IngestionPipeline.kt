package com.ojas.permsense.pipeline

import com.ojas.permsense.ledger.LedgerRecord
import com.ojas.permsense.ledger.LedgerWriter
import com.ojas.permsense.ledger.Retention
import com.ojas.permsense.model.AppInventory
import com.ojas.permsense.model.AppVersionPoint
import com.ojas.permsense.model.ContextualisedEpisode
import com.ojas.permsense.model.RawObservation
import com.ojas.permsense.processing.UsageContext
import com.ojas.permsense.processing.appVersionAt
import com.ojas.permsense.processing.buildVersionHistory
import com.ojas.permsense.processing.groupIntoEpisodes
import com.ojas.permsense.processing.joinContexts

/**
 * Normaliser -> context joiner -> ledger, as one deterministic step.
 *
 * Pure Kotlin with no Android types, so every stage can be replayed offline against recorded probe
 * runs. Feeding the same batch twice leaves the ledger unchanged: the ledger refuses episodes that
 * only re-describe sealed rows.
 */
class IngestionPipeline(
    private val ledger: LedgerWriter,
) {

    data class Result(
        val observationsIn: Int,
        /** Observations older than the retention horizon, not admitted. */
        val observationsDropped: Int,
        val episodes: Int,
        val sealed: List<LedgerRecord>,
        /** Episodes still inside the 60 s merge window, not yet written. */
        val pending: Int,
    )

    /**
     * @param context visibility from T0 usage events. Null when Usage Access is missing: every
     * episode is then stored with stratum UNKNOWN rather than a guessed one.
     */
    fun ingest(
        observations: List<RawObservation>,
        context: UsageContext?,
        inventory: List<AppInventory>,
        nowMs: Long,
        knownVersions: Map<String, List<AppVersionPoint>> = emptyMap(),
        zoneOffsetMs: Long = 0L,
    ): Result {

        val versionHistory = mergeVersionHistory(buildVersionHistory(inventory), knownVersions)

        // Anything older than the retention horizon would be pruned straight away and folded into
        // an aggregate again on every scan, inflating the counts. Never admit it in the first place.
        val horizonMs = Retention.cutoffFor(nowMs, zoneOffsetMs)
        val admitted = observations.filter { it.timestampMs >= horizonMs }

        val episodes = groupIntoEpisodes(admitted, context)

        val contextualised: List<ContextualisedEpisode> =
            if (context != null) {
                joinContexts(episodes, context, versionHistory)
            } else {
                episodes.map { episode ->
                    ContextualisedEpisode(
                        episode = episode,
                        // Still known when the observation carries the OS's own process state.
                        stratum = episode.stratum,
                        screenState = "UNKNOWN",
                        appVersionAtAccess = appVersionAt(episode.packageName, episode.startMs, versionHistory),
                    )
                }
            }

        val sealed = ledger.append(contextualised, nowMs)

        return Result(
            observationsIn = observations.size,
            observationsDropped = observations.size - admitted.size,
            episodes = episodes.size,
            sealed = sealed,
            pending = ledger.pendingCount(),
        )
    }

    /** Seal everything old enough. Call at the end of a scan so quiet episodes are not left pending. */
    fun flush(nowMs: Long): List<LedgerRecord> = ledger.flush(nowMs)
}

/** Union of two version histories per package, ordered and de-duplicated. */
fun mergeVersionHistory(
    a: Map<String, List<AppVersionPoint>>,
    b: Map<String, List<AppVersionPoint>>,
): Map<String, List<AppVersionPoint>> {

    return (a.keys + b.keys).associateWith { pkg ->
        (a[pkg].orEmpty() + b[pkg].orEmpty())
            .distinct()
            .sortedWith(compareBy({ it.effectiveFromMs }, { it.versionCode }))
    }
}
