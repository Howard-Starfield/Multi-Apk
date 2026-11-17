package dev.clonelab.app.keepalive

import org.junit.Assert.assertEquals
import org.junit.Test

class ExitCloneLabTest {
    private val pkg = "dev.clonelab.app"

    @Test
    fun cloneProcessesDieFirstThenTheEngineServerThenThisProcess() {
        val processes = listOf(
            OwnProcess(pkg, 100),
            OwnProcess("$pkg:black", 200),
            OwnProcess("$pkg:p0", 300),
            OwnProcess("$pkg:p7", 400),
        )
        assertEquals(listOf(300, 400, 200, 100), ExitCloneLab.killOrder(processes, pkg, myPid = 100))
    }

    @Test
    fun theCurrentProcessIsLastEvenWhenItIsAStubProcess() {
        val processes = listOf(OwnProcess("$pkg:p1", 300), OwnProcess("$pkg:black", 200), OwnProcess("$pkg:p2", 400))
        assertEquals(listOf(400, 200, 300), ExitCloneLab.killOrder(processes, pkg, myPid = 300))
    }

    @Test
    fun processesOfOtherPackagesAreLeftAlone() {
        val processes = listOf(OwnProcess("com.other", 500), OwnProcess("$pkg:p0", 300), OwnProcess(pkg, 100))
        assertEquals(listOf(300, 100), ExitCloneLab.killOrder(processes, pkg, myPid = 100))
    }

    @Test
    fun theCurrentProcessIsAddedWhenTheSystemDidNotListIt() {
        assertEquals(listOf(300, 100), ExitCloneLab.killOrder(listOf(OwnProcess("$pkg:p0", 300)), pkg, myPid = 100))
        assertEquals(listOf(100), ExitCloneLab.killOrder(emptyList(), pkg, myPid = 100))
    }

    @Test
    fun aPidIsNeverListedTwice() {
        val processes = listOf(OwnProcess("$pkg:p0", 300), OwnProcess("$pkg:p0", 300))
        assertEquals(listOf(300, 100), ExitCloneLab.killOrder(processes, pkg, myPid = 100))
    }
}
