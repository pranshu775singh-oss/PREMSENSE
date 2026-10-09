package com.ojas.permsense.processing

import com.ojas.permsense.model.RawObservation
import com.ojas.permsense.model.Tier
import com.ojas.permsense.model.UidStates
import com.ojas.permsense.model.Visibility
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The spec's context joiner combines the OS's own process state with usage events. These tests pin
 * down who wins when.
 */
class StratumResolutionTest {

    private val pkg = "com.example.app"
    private val t = 10_000_000L

    private fun obs(state: Int?, ts: Long = t) = RawObservation(
        tier = Tier.T1_PLUS,
        sourceEventId = "id-$state-$ts",
        uid = 10100,
        packageName = pkg,
        op = "android:camera",
        accessKind = "noted",
        timestampMs = ts,
        uidState = state,
        accessResult = "OBSERVED",
    )

    private fun ctx(vararg events: UsageEventLite) =
        buildUsageContext(events.toList(), 0L, t * 2)

    private fun stratum(o: RawObservation, context: UsageContext?) =
        groupIntoEpisodes(listOf(o), context).single().stratum

    private val screenOn = UsageEventLite(UsageKind.SCREEN_ON, null, 1L)
    private val screenOffBeforeT = UsageEventLite(UsageKind.SCREEN_OFF, null, t - 5 * 60_000L)

    @Test
    fun anOnScreenStateIsVisibleEvenWithoutAnyUsageContext() {
        assertEquals(Visibility.VISIBLE, stratum(obs(UidStates.TOP), null))
        assertEquals(Visibility.VISIBLE, stratum(obs(UidStates.FOREGROUND), null))
    }

    @Test
    fun aForegroundServiceStateIsAForegroundServiceStratum() {
        assertEquals(Visibility.FOREGROUND_SERVICE, stratum(obs(UidStates.FOREGROUND_SERVICE), null))
        assertEquals(Visibility.FOREGROUND_SERVICE, stratum(obs(UidStates.FOREGROUND_SERVICE_LOCATION), null))
    }

    @Test
    fun aBackgroundStateWithTheScreenOffIsScreenOff() {
        val c = ctx(screenOn, screenOffBeforeT)

        assertEquals(Visibility.SCREEN_OFF, stratum(obs(UidStates.BACKGROUND), c))
        assertEquals(Visibility.SCREEN_OFF, stratum(obs(UidStates.CACHED), c))
    }

    @Test
    fun aBackgroundStateWithTheScreenOnIsBackground() {
        val c = ctx(screenOn)

        assertEquals(Visibility.BACKGROUND, stratum(obs(UidStates.BACKGROUND), c))
    }

    @Test
    fun aBackgroundStateWithNoUsageContextIsBackgroundNotScreenOff() {
        // Nothing of the app was visible, but without usage events the screen state is unknown,
        // and "invisible" must not be upgraded to "screen off".
        assertEquals(Visibility.BACKGROUND, stratum(obs(UidStates.BACKGROUND), null))
    }

    @Test
    fun theProcessStateOutranksAUsageEventTimestampThatIsOnlyApproximatelyRight() {
        // The history rounds times down to the minute, so a join on the timestamp can say the app
        // was on screen when it was not. The OS's own state at the moment of access is exact.
        val visibleAroundT = ctx(
            screenOn,
            UsageEventLite(UsageKind.RESUMED, pkg, t - 30_000L),
            UsageEventLite(UsageKind.PAUSED, pkg, t + 30_000L),
        )

        assertEquals(Visibility.VISIBLE, stratum(obs(state = null), visibleAroundT))
        assertEquals(Visibility.BACKGROUND, stratum(obs(UidStates.BACKGROUND), visibleAroundT))
    }

    @Test
    fun anObservationWithoutAStateStillUsesUsageEventsAlone() {
        val c = ctx(screenOn, UsageEventLite(UsageKind.RESUMED, pkg, t - 1_000L))

        assertEquals(Visibility.VISIBLE, stratum(obs(null), c))
        assertEquals(Visibility.UNKNOWN, stratum(obs(null), null))
    }

    @Test
    fun aPersistentSystemProcessIsNotCalledVisible() {
        assertEquals(Visibility.BACKGROUND, stratum(obs(UidStates.PERSISTENT), ctx(screenOn)))
    }
}
