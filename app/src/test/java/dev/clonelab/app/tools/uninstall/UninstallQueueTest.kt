package dev.clonelab.app.tools.uninstall

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UninstallQueueTest {
    @Test
    fun walksThePackagesInOrderAndCountsResults() {
        var q = UninstallQueue(listOf("a", "b", "c"))
        assertEquals("a", q.current)
        assertEquals(1, q.position)
        q = q.recordResult(true)
        assertEquals("b", q.current)
        q = q.recordResult(false).recordResult(true)
        assertTrue(q.isFinished)
        assertEquals(listOf("a", "c"), q.uninstalled)
        assertEquals(listOf("b"), q.declined)
        assertEquals(3, q.position)
        assertEquals(listOf("b"), q.remaining())
        assertEquals(q, q.recordResult(true))
    }

    @Test
    fun cancelStopsBeforeTheNextPackage() {
        val q = UninstallQueue(listOf("a", "b")).recordResult(true).cancel()
        assertNull(q.current)
        assertTrue(q.isFinished)
        assertEquals(listOf("b"), q.remaining())
    }

    @Test
    fun goneMeansRemovedWhateverTheDialogSaid() = runTest {
        assertTrue(confirmRemoved("a", accepted = false, isInstalled = { false }))
    }

    @Test
    fun declinedAndStillInstalledIsNotRemoved() = runTest {
        var checks = 0
        assertFalse(confirmRemoved("a", accepted = false, isInstalled = { checks++; true }))
        assertEquals(1, checks)
    }

    @Test
    fun acceptedWaitsForThePackageManagerToCatchUp() = runTest {
        var checks = 0
        assertTrue(confirmRemoved("a", accepted = true, isInstalled = { ++checks < 3 }))
        assertEquals(3, checks)
        assertFalse(confirmRemoved("a", accepted = true, isInstalled = { true }, attempts = 2))
    }
}
