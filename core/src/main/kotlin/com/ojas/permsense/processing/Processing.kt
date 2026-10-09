package com.ojas.permsense.processing

import com.ojas.permsense.model.AppInventory
import com.ojas.permsense.model.AppVersionPoint
import com.ojas.permsense.model.ContextualisedEpisode
import com.ojas.permsense.model.DeviceOffInterval
import com.ojas.permsense.model.Episode
import com.ojas.permsense.model.RawObservation
import com.ojas.permsense.model.Tier
import com.ojas.permsense.model.UidStates
import com.ojas.permsense.model.Visibility
import com.ojas.permsense.model.VisibilityInterval

import kotlin.math.abs

// ===========================================================================
// USAGE CONTEXT  (unchanged from the version that already builds)
// ===========================================================================

enum class UsageKind {
    RESUMED,
    PAUSED,
    FOREGROUND_SERVICE_START,
    FOREGROUND_SERVICE_STOP,
    SCREEN_ON,
    SCREEN_OFF,
    KEYGUARD_SHOWN,
    KEYGUARD_HIDDEN,
    DEVICE_STARTUP,
    DEVICE_SHUTDOWN
}

data class UsageEventLite(
    val kind: UsageKind,
    val packageName: String?,
    val timeMs: Long
)

data class UsageCursor(
    val visibleOpen: Map<String, Long> = emptyMap(),
    val fgsOpen: Map<String, Long> = emptyMap(),
    val screenOff: Boolean = false,
    val keyguardShown: Boolean = false,
    val deviceOffStartMs: Long? = null,
    val screenStateKnown: Boolean = false,
    val lastEventMs: Long = 0L
)

data class UsageContext(
    val visibility: List<VisibilityInterval>,
    val deviceOff: List<DeviceOffInterval>,
    val deviceStartups: List<Long>,

    val coverageStartMs: Long,
    val coverageEndMs: Long,
    val coverageKnown: Boolean,

    val screenStateKnown: Boolean,
    val eventsRead: Int,

    val nextCursor: UsageCursor
) {

    fun hasCoverage(
        timeMs: Long
    ): Boolean {

        return coverageKnown &&
                timeMs in coverageStartMs..coverageEndMs
    }

    fun screenOffOrLocked(
        timeMs: Long
    ): Boolean {

        return screenStateKnown &&
                deviceOff.any {
                    timeMs in it.startMs..it.endMs
                }
    }

    fun visibilityAt(
        packageName: String,
        timeMs: Long,
        slackMs: Long = CONTEXT_SLACK_MS
    ): Visibility? {

        return visibility
            .asSequence()
            .filter {
                it.packageName == packageName
            }
            .filter {
                timeMs >= it.startMs - slackMs &&
                        timeMs <= it.endMs + slackMs
            }
            .maxWithOrNull(
                compareBy<VisibilityInterval>(
                    {
                        if (
                            it.state ==
                            Visibility.VISIBLE
                        ) {
                            1
                        } else {
                            0
                        }
                    },
                    { it.startMs }
                )
            )
            ?.state
    }

    fun stratumFor(
        packageName: String,
        timeMs: Long
    ): Visibility {

        if (!hasCoverage(timeMs)) {
            return Visibility.UNKNOWN
        }

        when (
            visibilityAt(
                packageName,
                timeMs
            )
        ) {

            Visibility.VISIBLE ->
                return Visibility.VISIBLE

            Visibility.FOREGROUND_SERVICE ->
                return Visibility.FOREGROUND_SERVICE

            else -> Unit
        }

        if (!screenStateKnown) {
            return Visibility.UNKNOWN
        }

        return if (
            screenOffOrLocked(timeMs)
        ) {
            Visibility.SCREEN_OFF
        } else {
            Visibility.BACKGROUND
        }
    }

    fun screenStateAt(
        timeMs: Long
    ): String {

        if (
            !hasCoverage(timeMs) ||
            !screenStateKnown
        ) {
            return "UNKNOWN"
        }

        return if (
            screenOffOrLocked(timeMs)
        ) {
            "SCREEN_OFF_OR_LOCKED"
        } else {
            "SCREEN_ON"
        }
    }
}

fun buildUsageContext(
    events: List<UsageEventLite>,
    fromMs: Long,
    toMs: Long
): UsageContext {

    return buildUsageContext(
        events = events,
        fromMs = fromMs,
        toMs = toMs,
        cursor = null
    )
}

fun buildUsageContext(
    events: List<UsageEventLite>,
    fromMs: Long,
    toMs: Long,
    cursor: UsageCursor?
): UsageContext {

    require(toMs >= fromMs)

    val sorted =
        events.sortedBy {
            it.timeMs
        }

    val visibleOpen =
        (
                cursor?.visibleOpen
                    ?: emptyMap()
                ).toMutableMap()

    val fgsOpen =
        (
                cursor?.fgsOpen
                    ?: emptyMap()
                ).toMutableMap()

    val visibility =
        mutableListOf<VisibilityInterval>()

    val deviceOff =
        mutableListOf<DeviceOffInterval>()

    val startups =
        mutableListOf<Long>()

    var screenOff =
        cursor?.screenOff
            ?: false

    var keyguardShown =
        cursor?.keyguardShown
            ?: false

    var deviceOffStart =
        cursor?.deviceOffStartMs

    var screenStateKnown =
        cursor?.screenStateKnown
            ?: false

    var lastEventMs =
        cursor?.lastEventMs
            ?: 0L

    fun closeVisible(
        packageName: String,
        endMs: Long
    ) {

        val startMs =
            visibleOpen.remove(
                packageName
            ) ?: return

        if (endMs >= startMs) {

            visibility +=
                VisibilityInterval(
                    packageName = packageName,
                    state = Visibility.VISIBLE,
                    startMs = maxOf(
                        startMs,
                        fromMs
                    ),
                    endMs = endMs
                )
        }
    }

    fun closeFgs(
        packageName: String,
        endMs: Long
    ) {

        val startMs =
            fgsOpen.remove(
                packageName
            ) ?: return

        if (endMs >= startMs) {

            visibility +=
                VisibilityInterval(
                    packageName = packageName,
                    state =
                        Visibility.FOREGROUND_SERVICE,
                    startMs = maxOf(
                        startMs,
                        fromMs
                    ),
                    endMs = endMs
                )
        }
    }

    fun closeDeviceOff(
        endMs: Long
    ) {

        val startMs =
            deviceOffStart
                ?: return

        if (
            endMs >= startMs
        ) {

            deviceOff +=
                DeviceOffInterval(
                    startMs = maxOf(
                        startMs,
                        fromMs
                    ),
                    endMs = endMs
                )
        }

        deviceOffStart = null
    }

    fun hardClose(
        endMs: Long
    ) {

        visibleOpen.keys
            .toList()
            .sorted()
            .forEach {
                closeVisible(
                    it,
                    endMs
                )
            }

        fgsOpen.keys
            .toList()
            .sorted()
            .forEach {
                closeFgs(
                    it,
                    endMs
                )
            }

        if (
            screenOff ||
            keyguardShown
        ) {
            closeDeviceOff(endMs)
        }

        screenOff = false
        keyguardShown = false
        screenStateKnown = false
    }

    for (event in sorted) {

        lastEventMs =
            maxOf(
                lastEventMs,
                event.timeMs
            )

        when (event.kind) {

            UsageKind.RESUMED -> {

                val pkg =
                    event.packageName
                        ?: continue

                visibleOpen.putIfAbsent(
                    pkg,
                    event.timeMs
                )
            }

            UsageKind.PAUSED -> {

                val pkg =
                    event.packageName
                        ?: continue

                closeVisible(
                    pkg,
                    event.timeMs
                )
            }

            UsageKind.FOREGROUND_SERVICE_START -> {

                val pkg =
                    event.packageName
                        ?: continue

                fgsOpen.putIfAbsent(
                    pkg,
                    event.timeMs
                )
            }

            UsageKind.FOREGROUND_SERVICE_STOP -> {

                val pkg =
                    event.packageName
                        ?: continue

                closeFgs(
                    pkg,
                    event.timeMs
                )
            }

            UsageKind.SCREEN_OFF -> {

                screenStateKnown = true

                if (!screenOff) {

                    screenOff = true

                    if (
                        deviceOffStart == null
                    ) {
                        deviceOffStart =
                            event.timeMs
                    }
                }
            }

            UsageKind.SCREEN_ON -> {

                screenStateKnown = true

                screenOff = false

                if (!keyguardShown) {
                    closeDeviceOff(
                        event.timeMs
                    )
                }
            }

            UsageKind.KEYGUARD_SHOWN -> {

                screenStateKnown = true

                if (!keyguardShown) {

                    keyguardShown = true

                    if (
                        deviceOffStart == null
                    ) {
                        deviceOffStart =
                            event.timeMs
                    }
                }
            }

            UsageKind.KEYGUARD_HIDDEN -> {

                screenStateKnown = true

                keyguardShown = false

                if (!screenOff) {
                    closeDeviceOff(
                        event.timeMs
                    )
                }
            }

            UsageKind.DEVICE_STARTUP -> {

                startups +=
                    event.timeMs

                hardClose(
                    event.timeMs
                )
            }

            UsageKind.DEVICE_SHUTDOWN -> {

                hardClose(
                    event.timeMs
                )
            }
        }
    }

    visibleOpen
        .toSortedMap()
        .forEach { (pkg, startMs) ->

            visibility +=
                VisibilityInterval(
                    packageName = pkg,
                    state = Visibility.VISIBLE,
                    startMs = maxOf(
                        startMs,
                        fromMs
                    ),
                    endMs = toMs
                )
        }

    fgsOpen
        .toSortedMap()
        .forEach { (pkg, startMs) ->

            visibility +=
                VisibilityInterval(
                    packageName = pkg,
                    state =
                        Visibility.FOREGROUND_SERVICE,
                    startMs = maxOf(
                        startMs,
                        fromMs
                    ),
                    endMs = toMs
                )
        }

    if (
        screenOff ||
        keyguardShown
    ) {

        deviceOff +=
            DeviceOffInterval(
                startMs =
                    maxOf(
                        deviceOffStart
                            ?: fromMs,
                        fromMs
                    ),
                endMs = toMs
            )
    }

    val mergedVisibility =
        mergeVisibilityIntervals(
            visibility
                .filter {
                    it.endMs >=
                            it.startMs
                }
        )

    val mergedDeviceOff =
        mergeDeviceOffIntervals(
            deviceOff
                .filter {
                    it.endMs >=
                            it.startMs
                }
        )

    val coverageKnown =
        events.isNotEmpty() ||
                cursor != null

    val nextCursor =
        UsageCursor(
            visibleOpen =
                visibleOpen.toMap(),

            fgsOpen =
                fgsOpen.toMap(),

            screenOff =
                screenOff,

            keyguardShown =
                keyguardShown,

            deviceOffStartMs =
                deviceOffStart,

            screenStateKnown =
                screenStateKnown,

            lastEventMs =
                lastEventMs
        )

    return UsageContext(
        visibility =
            mergedVisibility,

        deviceOff =
            mergedDeviceOff,

        deviceStartups =
            startups.sorted(),

        coverageStartMs =
            fromMs,

        coverageEndMs =
            toMs,

        coverageKnown =
            coverageKnown,

        screenStateKnown =
            screenStateKnown,

        eventsRead =
            events.size,

        nextCursor =
            nextCursor
    )
}

private fun mergeVisibilityIntervals(
    intervals: List<VisibilityInterval>
): List<VisibilityInterval> {

    if (intervals.isEmpty()) {
        return emptyList()
    }

    val result =
        mutableListOf<VisibilityInterval>()

    val sorted =
        intervals.sortedWith(
            compareBy(
                { it.packageName },
                { it.state.ordinal },
                { it.startMs },
                { it.endMs }
            )
        )

    for (current in sorted) {

        val previous =
            result.lastOrNull()

        if (
            previous != null &&
            previous.packageName ==
            current.packageName &&
            previous.state ==
            current.state &&
            current.startMs -
            previous.endMs <=
            CONTEXT_MERGE_SLACK_MS
        ) {

            result[result.lastIndex] =
                previous.copy(
                    endMs =
                        maxOf(
                            previous.endMs,
                            current.endMs
                        )
                )

        } else {

            result += current
        }
    }

    return result
}

private fun mergeDeviceOffIntervals(
    intervals: List<DeviceOffInterval>
): List<DeviceOffInterval> {

    if (intervals.isEmpty()) {
        return emptyList()
    }

    val result =
        mutableListOf<DeviceOffInterval>()

    val sorted =
        intervals.sortedBy {
            it.startMs
        }

    for (current in sorted) {

        val previous =
            result.lastOrNull()

        if (
            previous != null &&
            current.startMs -
            previous.endMs <=
            CONTEXT_MERGE_SLACK_MS
        ) {

            result[result.lastIndex] =
                previous.copy(
                    endMs =
                        maxOf(
                            previous.endMs,
                            current.endMs
                        )
                )

        } else {

            result += current
        }
    }

    return result
}

// The single context slack. UsageContext.visibilityAt() uses it as its default.
private const val CONTEXT_SLACK_MS =
    1_500L

private const val CONTEXT_MERGE_SLACK_MS =
    1_500L

// ===========================================================================
// NORMALISER   (pure, deterministic, replayable)
//
//   T1 / T1+ / T2 RawObservations
//        -> exact dedup            (same tier + same sourceEventId)
//        -> cross-tier reconcile   (same physical access seen by several tiers)
//        -> episode grouping       (same uid/package/op/stratum/outcome/flags, gap <= 60 s)
//
// T0 inventory never enters this stage.
// ===========================================================================

/** Observations of the same episode key that are at most this far apart share one episode. */
const val EPISODE_GAP_MS =
    60_000L

/** Live tiers (T2/T3) vs T1 snapshot. Same OS clock, so the window is tight. */
const val RECONCILE_LIVE_VS_T1_MS =
    2_000L

/** Any pair that involves T1+. Discrete history is quantised to one minute. */
const val RECONCILE_T1_PLUS_MS =
    60_000L

/** Higher wins as the primary representation of a merged access. T3 > T2 > T1+ > T1 > T0. */
private fun tierPriority(
    tier: Tier
): Int {

    return when (tier) {
        Tier.T3 -> 5
        Tier.T2 -> 4
        Tier.T1_PLUS -> 3
        Tier.T1 -> 2
        Tier.T0 -> 1
    }
}

private fun reconcileToleranceMs(
    a: Tier,
    b: Tier
): Long {

    return if (
        a == Tier.T1_PLUS ||
        b == Tier.T1_PLUS
    ) {
        RECONCILE_T1_PLUS_MS
    } else {
        RECONCILE_LIVE_VS_T1_MS
    }
}

/**
 * Total order over every non-evidence field. Anything that sorts with this comparator is
 * independent of input order, even when two observations differ in only one field.
 */
private val RAW_ORDER: Comparator<RawObservation> =
    compareBy<RawObservation>(
        { it.timestampMs },
        { it.uid },
        { it.packageName },
        { it.op },
        { it.tier.ordinal },
        { it.sourceEventId },
        { it.accessKind },
        { it.durationMs },
        { it.attributionTag },
        { it.flags },
        { it.opMode },
        { it.uidState },
        { it.accessResult }
    )

private val EPISODE_ORDER: Comparator<Episode> =
    compareBy<Episode>(
        { it.startMs },
        { it.uid },
        { it.packageName },
        { it.op },
        { it.stratum.ordinal },
        { it.accessResults.sorted().joinToString(",") },
        { it.flags },
        { it.endMs },
        { it.sourceEventIds.joinToString(",") }
    )

/** End of the interval an observation covers. Negative durations are treated as zero. */
private fun endOf(
    observation: RawObservation
): Long {

    return observation.timestampMs +
            (observation.durationMs ?: 0L)
                .coerceAtLeast(0L)
}

/**
 * Outcome of an access.
 *
 *  - REJECTED / IGNORED / ERRORED stay distinct and can never collapse into OBSERVED.
 *  - ALLOWED, OBSERVED and "no result" are all OBSERVED ("an access happened and was not denied").
 *    The original label survives on Episode.observations[].accessResult.
 *  - An unrecognised label is kept as-is rather than guessed into OBSERVED.
 *  - accessKind == "rejected" always wins over a contradictory accessResult.
 */
private fun accessOutcome(
    observation: RawObservation
): String {

    if (
        observation.accessKind.equals(
            "rejected",
            ignoreCase = true
        )
    ) {
        return "REJECTED"
    }

    val label =
        observation.accessResult
            ?.trim()
            ?.uppercase()

    return when (label) {
        null,
        "",
        "ALLOWED",
        "OBSERVED" -> "OBSERVED"

        else -> label
    }
}

private data class EpisodeKey(
    val uid: Int,
    val packageName: String,
    val op: String,
    val stratum: Visibility,
    val outcome: String,
    val flags: Int
)

/** JOB 1a: same tier + same sourceEventId is one observation. Evidence is unioned. */
private fun exactDedup(
    observations: List<RawObservation>
): List<RawObservation> {

    return observations
        .groupBy {
            it.tier to it.sourceEventId
        }
        .values
        .map { group ->

            group
                .sortedWith(RAW_ORDER)
                .first()
                .copy(
                    evidenceSourceIds =
                        group
                            .flatMap { it.evidenceSourceIds }
                            .toSortedSet(),

                    evidenceTiers =
                        group
                            .flatMap { it.evidenceTiers }
                            .toSortedSet()
                )
        }
}

/**
 * JOB 1b: cross-tier reconciliation inside ONE (uid, package, op) cell.
 *
 * Observations are processed highest-fidelity first. Each one either attaches to the nearest
 * already-accepted observation of a different tier (same outcome, within the pair tolerance),
 * or becomes a new accepted observation itself.
 *
 *  - An accepted observation takes at most one observation per tier: one physical access is
 *    seen once per tier, so a second T1 record cannot also be that same access.
 *  - Same-tier observations never merge here (two T2 events are two events).
 *  - Different outcomes never merge, so a rejection is never absorbed into an allowed access.
 */
private fun reconcileCell(
    cell: List<RawObservation>
): List<RawObservation> {

    val accepted =
        ArrayList<RawObservation>()

    val processingOrder =
        compareByDescending<RawObservation> {
            tierPriority(it.tier)
        }.then(RAW_ORDER)

    for (observation in cell.sortedWith(processingOrder)) {

        val outcome =
            accessOutcome(observation)

        // Nearest eligible accepted observation; ties -> higher tier, earlier time, lower id.
        val nearest =
            compareBy<Int>(
                {
                    abs(
                        accepted[it].timestampMs -
                                observation.timestampMs
                    )
                },
                { -tierPriority(accepted[it].tier) },
                { accepted[it].timestampMs },
                { accepted[it].sourceEventId }
            )

        var target = -1

        for (index in accepted.indices) {

            val candidate =
                accepted[index]

            // Cheapest test first: the time window rejects almost every candidate.
            val eligible =
                abs(
                    candidate.timestampMs -
                            observation.timestampMs
                ) <=
                        reconcileToleranceMs(
                            candidate.tier,
                            observation.tier
                        ) &&

                        candidate.evidenceTiers
                            .none { it in observation.evidenceTiers } &&

                        accessOutcome(candidate) == outcome

            if (
                eligible &&
                (
                        target < 0 ||
                                nearest.compare(index, target) < 0
                        )
            ) {
                target = index
            }
        }

        if (target < 0) {

            accepted += observation

        } else {

            val primary =
                accepted[target]

            accepted[target] =
                primary.copy(
                    evidenceSourceIds =
                        (
                                primary.evidenceSourceIds +
                                        observation.evidenceSourceIds
                                ).toSortedSet(),

                    evidenceTiers =
                        (
                                primary.evidenceTiers +
                                        observation.evidenceTiers
                                ).toSortedSet()
                )
        }
    }

    return accepted
}

private fun reconcileCrossTier(
    observations: List<RawObservation>
): List<RawObservation> {

    return exactDedup(observations)
        .groupBy {
            Triple(
                it.uid,
                it.packageName,
                it.op
            )
        }
        .values
        .flatMap {
            reconcileCell(it)
        }
        .sortedWith(RAW_ORDER)
}

/**
 * Where the app stood when the access happened.
 *
 * The OS records the app's process state with some accesses (the T1+ history does). That state is
 * exact for the moment of the access, while a timestamp joined against usage events is only as
 * good as the clocks and, for the history, its one-minute rounding. So when an observation carries
 * a state it decides whether the app was on screen or running a visible service. Usage events
 * still decide whether the screen was off, because process state says nothing about that.
 *
 * Without a state, usage events decide alone. Missing coverage is UNKNOWN, never "screen off".
 */
internal fun resolveStratum(
    observation: RawObservation,
    context: UsageContext?
): Visibility {

    val state =
        observation.uidState
            ?: return context?.stratumFor(
                observation.packageName,
                observation.timestampMs
            ) ?: Visibility.UNKNOWN

    return when {

        UidStates.isOnScreen(state) ->
            Visibility.VISIBLE

        UidStates.isForegroundService(state) ->
            Visibility.FOREGROUND_SERVICE

        // Persistent, background or cached: nothing of the app is visible.
        context != null &&
                context.hasCoverage(observation.timestampMs) &&
                context.screenOffOrLocked(observation.timestampMs) ->
            Visibility.SCREEN_OFF

        else ->
            Visibility.BACKGROUND
    }
}

/** Episode grouping without usage context: every stratum is UNKNOWN until joinContext(). */
fun groupIntoEpisodes(
    observations: List<RawObservation>
): List<Episode> {

    return groupIntoEpisodes(
        observations = observations,
        context = null
    )
}

/**
 * JOB 2: reconcile, resolve each observation's stratum from [context], then merge
 * observations of the same (uid, package, op, stratum, outcome, flags) whose gap is
 * <= EPISODE_GAP_MS. The gap is measured from the END of the episode so far
 * (timestamp + duration), so a long access is never split by its own duration.
 */
fun groupIntoEpisodes(
    observations: List<RawObservation>,
    context: UsageContext?
): List<Episode> {

    val grouped =
        reconcileCrossTier(observations)
            .groupBy { observation ->

                EpisodeKey(
                    uid =
                        observation.uid,

                    packageName =
                        observation.packageName,

                    op =
                        observation.op,

                    stratum =
                        resolveStratum(
                            observation,
                            context
                        ),

                    outcome =
                        accessOutcome(observation),

                    flags =
                        observation.flags
                )
            }

    val episodes =
        ArrayList<Episode>()

    for ((key, group) in grouped) {

        var current =
            ArrayList<RawObservation>()

        var currentEndMs =
            Long.MIN_VALUE

        for (observation in group.sortedWith(RAW_ORDER)) {

            if (
                current.isNotEmpty() &&
                observation.timestampMs - currentEndMs >
                EPISODE_GAP_MS
            ) {

                episodes +=
                    buildEpisode(
                        key,
                        current
                    )

                current =
                    ArrayList()

                currentEndMs =
                    Long.MIN_VALUE
            }

            current += observation

            currentEndMs =
                maxOf(
                    currentEndMs,
                    endOf(observation)
                )
        }

        if (current.isNotEmpty()) {

            episodes +=
                buildEpisode(
                    key,
                    current
                )
        }
    }

    return episodes.sortedWith(EPISODE_ORDER)
}

private fun buildEpisode(
    key: EpisodeKey,
    observations: List<RawObservation>
): Episode {

    val ordered =
        observations.sortedWith(RAW_ORDER)

    return Episode(
        uid =
            key.uid,

        packageName =
            key.packageName,

        op =
            key.op,

        stratum =
            key.stratum,

        startMs =
            ordered.first().timestampMs,

        endMs =
            ordered.maxOf {
                endOf(it)
            },

        observationCount =
            ordered.size,

        tiers =
            ordered
                .flatMap { it.evidenceTiers }
                .toSet(),

        sourceEventIds =
            ordered
                .flatMap { it.evidenceSourceIds }
                .toSet()
                .sorted(),

        accessKinds =
            ordered
                .map { it.accessKind }
                .toSet(),

        durationMs =
            ordered
                .mapNotNull { it.durationMs }
                .maxOrNull(),

        attributionTags =
            ordered
                .mapNotNull { it.attributionTag }
                .toSet(),

        flags =
            key.flags,

        accessResults =
            ordered
                .map { accessOutcome(it) }
                .toSet(),

        observations =
            ordered
    )
}

// ===========================================================================
// CONTEXT JOINER + VERSION CONTEXT
// ===========================================================================

/**
 * Stratum rule for an episode starting at t (all of it lives in UsageContext.stratumFor):
 *   outside trusted coverage -> UNKNOWN
 *   VISIBLE interval at t    -> VISIBLE        (wins over FGS)
 *   FGS interval at t        -> FOREGROUND_SERVICE
 *   screen state unknown     -> UNKNOWN
 *   screen off / keyguard    -> SCREEN_OFF
 *   otherwise                -> BACKGROUND
 * "No interval" is never read as "screen off".
 */
fun joinContext(
    episode: Episode,
    context: UsageContext,
    versionHistory:
    Map<String, List<AppVersionPoint>>
): ContextualisedEpisode {

    val stratum =
        episode.stratum.takeUnless {
            it == Visibility.UNKNOWN
        } ?: context.stratumFor(
            episode.packageName,
            episode.startMs
        )

    return ContextualisedEpisode(
        episode =
            episode,

        stratum =
            stratum,

        screenState =
            context.screenStateAt(
                episode.startMs
            ),

        appVersionAtAccess =
            appVersionAt(
                packageName =
                    episode.packageName,

                timeMs =
                    episode.startMs,

                history =
                    versionHistory
            )
    )
}

fun joinContexts(
    episodes: List<Episode>,
    context: UsageContext,
    versionHistory:
    Map<String, List<AppVersionPoint>>
): List<ContextualisedEpisode> {

    return episodes
        .sortedWith(EPISODE_ORDER)
        .map {
            joinContext(
                episode =
                    it,

                context =
                    context,

                versionHistory =
                    versionHistory
            )
        }
}

/**
 * One AppVersionPoint per (package, versionCode, lastUpdateTimeMs) found in [inventory].
 * Pass several inventory snapshots over time to get real history; a single snapshot only
 * knows the version that is installed now.
 */
fun buildVersionHistory(
    inventory: List<AppInventory>
): Map<String, List<AppVersionPoint>> {

    return inventory
        .filter {
            it.lastUpdateTimeMs > 0L
        }
        .groupBy {
            it.packageName
        }
        .mapValues { (_, apps) ->
            apps
                .map {
                    AppVersionPoint(
                        versionCode =
                            it.versionCode,

                        effectiveFromMs =
                            it.lastUpdateTimeMs
                    )
                }
                .distinct()
                .sortedWith(
                    compareBy(
                        { it.effectiveFromMs },
                        { it.versionCode }
                    )
                )
        }
}

/**
 * Version in force at [timeMs]: the point with the latest effectiveFromMs <= timeMs.
 * Returns -1 when the time precedes every known point (unknown, never guessed).
 */
fun appVersionAt(
    packageName: String,
    timeMs: Long,
    history:
    Map<String, List<AppVersionPoint>>
): Long {

    return history[packageName]
        .orEmpty()
        .filter {
            it.effectiveFromMs <= timeMs
        }
        .maxWithOrNull(
            compareBy(
                { it.effectiveFromMs },
                { it.versionCode }
            )
        )
        ?.versionCode
        ?: -1L
}