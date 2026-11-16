package dev.clonelab.app.keepalive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAliveControllerTest {
    @Test
    fun keepAliveCloneAlwaysKeepsTheServiceRunning() {
        assertTrue(KeepAliveController.shouldRun(keepAliveCount = 1, cloneCount = 1, switcherEnabled = false, scheduledMacroCount = 0))
        assertTrue(KeepAliveController.shouldRun(keepAliveCount = 1, cloneCount = 3, switcherEnabled = true, scheduledMacroCount = 0))
    }

    @Test
    fun switcherAloneNeedsAtLeastOneClone() {
        assertTrue(KeepAliveController.shouldRun(keepAliveCount = 0, cloneCount = 2, switcherEnabled = true, scheduledMacroCount = 0))
        assertFalse(KeepAliveController.shouldRun(keepAliveCount = 0, cloneCount = 0, switcherEnabled = true, scheduledMacroCount = 0))
    }

    @Test
    fun serviceStopsWhenNothingNeedsIt() {
        assertFalse(KeepAliveController.shouldRun(keepAliveCount = 0, cloneCount = 4, switcherEnabled = false, scheduledMacroCount = 0))
        assertFalse(KeepAliveController.shouldRun(keepAliveCount = 0, cloneCount = 0, switcherEnabled = false, scheduledMacroCount = 0))
    }

    @Test
    fun aScheduledMacroKeepsTheServiceRunning() {
        assertTrue(KeepAliveController.shouldRun(keepAliveCount = 0, cloneCount = 0, switcherEnabled = false, scheduledMacroCount = 1))
    }
}
