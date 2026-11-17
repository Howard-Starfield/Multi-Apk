package dev.clonelab.app.keepalive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAliveControllerTest {
    @Test
    fun keepAliveCloneAlwaysKeepsTheServiceRunning() {
        assertTrue(KeepAliveController.shouldRun(keepAliveCount = 1, cloneCount = 1, switcherEnabled = false, scheduledMacroCount = 0, exitedByUser = false))
        assertTrue(KeepAliveController.shouldRun(keepAliveCount = 1, cloneCount = 3, switcherEnabled = true, scheduledMacroCount = 0, exitedByUser = false))
    }

    @Test
    fun switcherAloneNeedsAtLeastOneClone() {
        assertTrue(KeepAliveController.shouldRun(keepAliveCount = 0, cloneCount = 2, switcherEnabled = true, scheduledMacroCount = 0, exitedByUser = false))
        assertFalse(KeepAliveController.shouldRun(keepAliveCount = 0, cloneCount = 0, switcherEnabled = true, scheduledMacroCount = 0, exitedByUser = false))
    }

    @Test
    fun serviceStopsWhenNothingNeedsIt() {
        assertFalse(KeepAliveController.shouldRun(keepAliveCount = 0, cloneCount = 4, switcherEnabled = false, scheduledMacroCount = 0, exitedByUser = false))
        assertFalse(KeepAliveController.shouldRun(keepAliveCount = 0, cloneCount = 0, switcherEnabled = false, scheduledMacroCount = 0, exitedByUser = false))
    }

    @Test
    fun aScheduledMacroKeepsTheServiceRunning() {
        assertTrue(KeepAliveController.shouldRun(keepAliveCount = 0, cloneCount = 0, switcherEnabled = false, scheduledMacroCount = 1, exitedByUser = false))
    }

    @Test
    fun exitedByUserOverridesEveryReasonToRun() {
        assertFalse(KeepAliveController.shouldRun(keepAliveCount = 2, cloneCount = 3, switcherEnabled = true, scheduledMacroCount = 1, exitedByUser = true))
        assertFalse(KeepAliveController.shouldRun(keepAliveCount = 0, cloneCount = 3, switcherEnabled = true, scheduledMacroCount = 0, exitedByUser = true))
        assertFalse(KeepAliveController.shouldRun(keepAliveCount = 0, cloneCount = 0, switcherEnabled = false, scheduledMacroCount = 2, exitedByUser = true))
    }
}
