package dev.clonelab.app.keepalive

import dev.clonelab.app.model.CloneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualStopsTest {
    private val a = CloneId("com.a", 0)
    private val b = CloneId("com.a", 1)

    @Test
    fun entriesSurviveANewInstanceOnTheSameBacking() {
        val backing = MemoryManualStopBacking()
        ManualStopSet(backing).apply {
            add(a)
            add(b)
        }

        val afterRestart = ManualStopSet(backing)

        assertTrue(afterRestart.contains(a))
        assertTrue(afterRestart.contains(b))
    }

    @Test
    fun removeIsPersistedPerClone() {
        val backing = MemoryManualStopBacking()
        ManualStopSet(backing).apply {
            add(a)
            add(b)
            remove(a)
        }

        val afterRestart = ManualStopSet(backing)

        assertFalse(afterRestart.contains(a))
        assertTrue(afterRestart.contains(b))
    }

    @Test
    fun clearAllEmptiesBacking() {
        val backing = MemoryManualStopBacking()
        ManualStopSet(backing).apply {
            add(a)
            clearAll()
        }
        assertEquals(emptySet<String>(), backing.load())
    }
}
