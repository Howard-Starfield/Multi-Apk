package dev.clonelab.app.keepalive

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchdogPolicyTest {
    private val now = 1_000_000L

    @Test
    fun runningCloneNeedsNothing() {
        assertEquals(WatchdogAction.None, WatchdogPolicy.decide(now, null, running = true, canDrawOverlays = true))
        assertEquals(WatchdogAction.None, WatchdogPolicy.decide(now, null, running = true, canDrawOverlays = false))
    }

    @Test
    fun deadCloneIsRestartedWhenOverlayAllowed() {
        assertEquals(WatchdogAction.Restart, WatchdogPolicy.decide(now, null, running = false, canDrawOverlays = true))
    }

    @Test
    fun deadCloneWithoutOverlayOnlyNotifies() {
        assertEquals(WatchdogAction.Notify, WatchdogPolicy.decide(now, null, running = false, canDrawOverlays = false))
        assertEquals(WatchdogAction.Notify, WatchdogPolicy.decide(now, now - 1, running = false, canDrawOverlays = false))
    }

    @Test
    fun restartsAreRateLimitedToOncePerTwoMinutes() {
        val limit = WatchdogPolicy.MIN_RESTART_INTERVAL_MS
        assertEquals(120_000L, limit)
        assertEquals(WatchdogAction.None, WatchdogPolicy.decide(now, now - 30_000, running = false, canDrawOverlays = true))
        assertEquals(WatchdogAction.None, WatchdogPolicy.decide(now, now - (limit - 1), running = false, canDrawOverlays = true))
        assertEquals(WatchdogAction.Restart, WatchdogPolicy.decide(now, now - limit, running = false, canDrawOverlays = true))
    }
}
