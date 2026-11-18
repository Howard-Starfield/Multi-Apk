package dev.clonelab.app.keepalive

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchdogPolicyTest {

    @Test
    fun runningCloneNeedsNothing() {
        assertEquals(WatchdogAction.None, WatchdogPolicy.decide(running = true, manuallyStopped = false, seenRunning = true))
    }

    @Test
    fun stoppedCloneIsReportedNotRestarted() {
        assertEquals(WatchdogAction.Notify, WatchdogPolicy.decide(running = false, manuallyStopped = false, seenRunning = true))
    }

    @Test
    fun manuallyStoppedCloneIsNotNotified() {
        assertEquals(WatchdogAction.None, WatchdogPolicy.decide(running = false, manuallyStopped = true, seenRunning = true))
    }

    @Test
    fun cloneNeverSeenRunningIsLeftAlone() {
        assertEquals(WatchdogAction.None, WatchdogPolicy.decide(running = false, manuallyStopped = false, seenRunning = false))
    }
}
