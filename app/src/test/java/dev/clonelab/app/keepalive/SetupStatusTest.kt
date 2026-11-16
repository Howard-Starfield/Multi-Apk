package dev.clonelab.app.keepalive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupStatusTest {
    private val allDone = SetupStatus(appPermissions = true, notifications = true, battery = true, overlay = true, autoStart = true)

    @Test
    fun nothingMissingWhenEverythingIsDone() {
        assertTrue(allDone.missing().isEmpty())
        assertTrue(allDone.complete)
    }

    @Test
    fun everythingMissingComesInFlowOrder() {
        val none = SetupStatus(appPermissions = false, notifications = false, battery = false, overlay = false, autoStart = false)
        assertEquals(
            listOf(
                SetupStep.APP_PERMISSIONS, SetupStep.NOTIFICATIONS, SetupStep.BATTERY, SetupStep.OVERLAY, SetupStep.AUTO_START,
            ),
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
    fun appPermissionsComeFirstEvenWhenOthersAreMissing() {
        assertEquals(listOf(SetupStep.APP_PERMISSIONS), allDone.copy(appPermissions = false).missing())
        assertEquals(
            listOf(SetupStep.APP_PERMISSIONS, SetupStep.OVERLAY),
            allDone.copy(appPermissions = false, overlay = false).missing(),
        )
        assertEquals(SetupStep.APP_PERMISSIONS, SetupStep.values().first())
    }

    @Test
    fun bannerCoversPermissionsBackgroundOrBoth() {
        assertNull(allDone.banner())
        assertEquals(SetupBanner.PERMISSIONS_ONLY, allDone.copy(appPermissions = false).banner())
        assertEquals(SetupBanner.BACKGROUND_ONLY, allDone.copy(battery = false).banner())
        assertEquals(SetupBanner.BACKGROUND_ONLY, allDone.copy(autoStart = false, overlay = false).banner())
        assertEquals(SetupBanner.BOTH, allDone.copy(appPermissions = false, notifications = false).banner())
    }

    @Test
    fun clonePermissionsAreStorageCameraAndMicrophone() {
        assertEquals(
            setOf(
                "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.CAMERA", "android.permission.RECORD_AUDIO",
            ),
            SetupStatus.CLONE_PERMISSIONS.toSet(),
        )
    }

    @Test
    fun autoStartCountsAsDoneWhenVisitedOrWhenThereIsNoVendorPage() {
        assertFalse(SetupStatus.autoStartDone(visited = false, hasVendorPage = true))
        assertTrue(SetupStatus.autoStartDone(visited = true, hasVendorPage = true))
        assertTrue(SetupStatus.autoStartDone(visited = false, hasVendorPage = false))
    }
}
