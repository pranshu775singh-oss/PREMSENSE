package com.ojas.permsense.pipeline

/**
 * What the live collector looked like the last time we checked, kept between runs.
 */
data class CollectorState(
    /** The last moment the collector was seen running and its buffer was read. */
    val lastAliveMs: Long,
    /** Identifies one run of the collector; it changes whenever the collector restarts. */
    val sessionStartedAtMs: Long,
    /** The collector's running count of events it had to throw away because its buffer was full. */
    val droppedEvents: Long,
)

sealed interface CollectorObservation {

    /** The collector answered. [startedAtMs] is when this run of it began. */
    data class Running(val startedAtMs: Long, val droppedEvents: Long, val nowMs: Long) : CollectorObservation

    /** It could not be reached: Shizuku stopped, the phone rebooted, or it was switched off. */
    data class Unreachable(val nowMs: Long) : CollectorObservation
}

data class CollectorGap(val startMs: Long, val endMs: Long, val reason: String)

/**
 * Turns "is the live collector up?" into recorded gaps.
 *
 * The collector sees every access while it runs and nothing while it does not. If those blind
 * stretches are not recorded, a quiet stretch looks like a quiet app, and a statistic built on
 * counts would learn from silence that was really blindness. A gap is recorded for each stretch
 * the collector was provably not watching.
 */
object GapTracker {

    const val RESTARTED = "COLLECTOR_RESTARTED"
    const val OVERFLOW = "BUFFER_OVERFLOW"
    const val UNREACHABLE = "COLLECTOR_UNREACHABLE"

    /** A collector missing for less than this is just the gap between two scheduled checks. */
    const val OPEN_GAP_MIN_MS = 20L * 60L * 1000L

    data class Step(val state: CollectorState?, val gaps: List<CollectorGap>)

    /**
     * The outage in progress: the collector was seen before and cannot be reached now.
     *
     * It cannot be written to the ledger yet, because nobody knows when it will end. But the
     * statistics must not read it as a quiet stretch, or a week of Shizuku being off teaches the
     * baseline that the app does nothing, and the first ordinary day afterwards looks like an alarm.
     * So the caller adds this to the rows it analyses, without storing it.
     */
    fun openGap(
        previous: CollectorState?,
        observation: CollectorObservation,
        minMs: Long = OPEN_GAP_MIN_MS,
    ): CollectorGap? {

        if (previous == null || observation !is CollectorObservation.Unreachable) return null
        if (observation.nowMs - previous.lastAliveMs < minMs) return null

        return CollectorGap(previous.lastAliveMs, observation.nowMs, UNREACHABLE)
    }

    fun update(previous: CollectorState?, observation: CollectorObservation): Step {

        return when (observation) {

            // Not reachable now. The gap cannot be dated yet: it runs from the last time it was
            // alive to whenever it is next seen running, so nothing is recorded until then.
            is CollectorObservation.Unreachable -> Step(previous, emptyList())

            is CollectorObservation.Running -> {

                val fresh = CollectorState(observation.nowMs, observation.startedAtMs, observation.droppedEvents)

                if (previous == null) return Step(fresh, emptyList())

                val gaps = ArrayList<CollectorGap>()

                if (observation.startedAtMs != previous.sessionStartedAtMs) {
                    // Restarted since we last looked: blind from the last sighting until it began.
                    gaps += CollectorGap(
                        startMs = previous.lastAliveMs,
                        endMs = maxOf(observation.startedAtMs, previous.lastAliveMs),
                        reason = RESTARTED,
                    )
                } else if (observation.droppedEvents > previous.droppedEvents) {
                    // Same run, but its buffer overflowed and the oldest events were lost.
                    gaps += CollectorGap(previous.lastAliveMs, observation.nowMs, OVERFLOW)
                }

                Step(fresh, gaps)
            }
        }
    }
}
