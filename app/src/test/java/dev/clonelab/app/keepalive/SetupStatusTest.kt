package dev.clonelab.app.keepalive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupStatusTest {
    private val allDone = SetupStatus(notifications = true, battery = true, overlay = true, autoStart = true)

    @Test
    fun nothingMissingWhenEverythingIsDone() {
        assertTrue(allDone.missing().isEmpty())
        assertTrue(allDone.complete)
    }

    @Test
    fun everythingMissingComesInFlowOrder() {
        val none = SetupStatus(notifications = false, battery = false, overlay = false, autoStart = false)
        assertEquals(
            listOf(SetupStep.NOTIFICATIONS, SetupStep.BATTERY, SetupStep.OVERLAY, SetupStep.AUTO_START),
            none.missing(),
        )
        assertFalse(none.complete)
    }

    @Test
    fun onlyTheMissingStepsAreListedInOrder() {
        assertEquals(listOf(SetupStep.OVERLAY), allDone.copy(overlay = false).missing())
        assertEquals(
            listOf(SetupStep.BATTERY, SetupStep.AUTO_START),
            allDone.copy(battery = false, autoStart = false).missing(),
        )
    }

    @Test
    fun autoStartCountsAsDoneWhenVisitedOrWhenThereIsNoVendorPage() {
        assertFalse(SetupStatus.autoStartDone(visited = false, hasVendorPage = true))
        assertTrue(SetupStatus.autoStartDone(visited = true, hasVendorPage = true))
        assertTrue(SetupStatus.autoStartDone(visited = false, hasVendorPage = false))
    }
}
