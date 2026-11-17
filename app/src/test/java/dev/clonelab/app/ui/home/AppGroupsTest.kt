package dev.clonelab.app.ui.home

import dev.clonelab.app.data.CloneItem
import dev.clonelab.app.model.CloneId
import dev.clonelab.app.model.CloneRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppGroupsTest {

    private fun item(
        pkg: String, user: Int, sort: Int, created: Long = 0, label: String = pkg.uppercase(),
        running: Boolean = false, installed: Boolean = true,
    ) = CloneItem(
        CloneRecord(CloneId(pkg, user), appLabel = label, sortOrder = sort, createdAt = created),
        installed = installed, running = running,
    )

    @Test
    fun noClonesNoGroups() {
        assertTrue(groupByApp(emptyList()).isEmpty())
    }

    @Test
    fun singleCloneAppIsStillAGroup() {
        val groups = groupByApp(listOf(item("a", 0, 0)))
        assertEquals(1, groups.size)
        assertEquals(1, groups[0].cloneCount)
        assertEquals("a", groups[0].packageName)
    }

    @Test
    fun countsClonesPerPackage() {
        val groups = groupByApp(listOf(item("a", 0, 0), item("b", 0, 1), item("a", 1, 2), item("a", 2, 3)))
        assertEquals(mapOf("a" to 3, "b" to 1), groups.associate { it.packageName to it.cloneCount })
    }

    @Test
    fun ordersGroupsByTheirSmallestSortOrder() {
        val groups = groupByApp(listOf(item("a", 0, 5), item("b", 0, 0), item("a", 1, 1), item("c", 0, 7)))
        assertEquals(listOf("b", "a", "c"), groups.map { it.packageName })
    }

    @Test
    fun sortOrderTiesGoToTheEarliestCreated() {
        val groups = groupByApp(listOf(item("a", 0, 0, created = 20), item("b", 0, 0, created = 10)))
        assertEquals(listOf("b", "a"), groups.map { it.packageName })
    }

    @Test
    fun runningIfAnyCloneRuns() {
        val groups = groupByApp(
            listOf(item("a", 0, 0), item("a", 1, 1, running = true), item("b", 0, 2), item("b", 1, 3)),
        )
        assertTrue(groups.first { it.packageName == "a" }.anyRunning)
        assertFalse(groups.first { it.packageName == "b" }.anyRunning)
    }

    @Test
    fun brokenCloneStillCountsAndGroups() {
        val groups = groupByApp(listOf(item("a", 0, 0), item("a", 1, 1, installed = false)))
        assertEquals(2, groups.single().cloneCount)
    }

    @Test
    fun representativeIsTheFirstCloneAndSuppliesTheLabel() {
        val groups = groupByApp(
            listOf(item("a", 3, 4, label = "Late"), item("a", 1, 2, label = "Karaoke"), item("a", 2, 3, label = "Mid")),
        )
        assertEquals(CloneId("a", 1), groups.single().representative.id)
        assertEquals("Karaoke", groups.single().label)
    }

    @Test
    fun blankLabelFallsBackToAnotherCloneThenThePackage() {
        val other = groupByApp(listOf(item("a", 0, 0, label = ""), item("a", 1, 1, label = "Real")))
        assertEquals("Real", other.single().label)
        val none = groupByApp(listOf(item("a.b.c", 0, 0, label = " ")))
        assertEquals("a.b.c", none.single().label)
    }
}
