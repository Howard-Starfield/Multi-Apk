package dev.clonelab.app.switcher

import dev.clonelab.app.model.CloneId
import dev.clonelab.app.model.CloneRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitcherModelTest {
    private fun record(pkg: String, user: Int, order: Int, name: String? = null) =
        CloneRecord(id = CloneId(pkg, user), appLabel = pkg, customName = name, sortOrder = order, createdAt = order.toLong())

    private val a0 = record("com.a", 0, order = 0)
    private val b0 = record("com.b", 0, order = 1)
    private val a1 = record("com.a", 1, order = 2)
    private val a2 = record("com.a", 2, order = 3)

    @Test
    fun emptyWhenNothingIsFocused() {
        assertTrue(SwitcherModel.items(listOf(a0, a1), null, setOf(a0.id)).isEmpty())
    }

    @Test
    fun emptyWhenTheFocusedCloneIsUnknown() {
        assertTrue(SwitcherModel.items(listOf(a0, a1), CloneId("com.zzz", 0), emptySet()).isEmpty())
    }

    @Test
    fun onlyClonesOfTheFocusedPackageAreListed() {
        val items = SwitcherModel.items(listOf(a0, b0, a1), a0.id, emptySet())
        assertEquals(listOf(a0.id, a1.id), items.map { it.id })
    }

    @Test
    fun itemsFollowTheStoreSortOrderWhateverTheInputOrder() {
        val items = SwitcherModel.items(listOf(a2, a0, a1), a1.id, emptySet())
        assertEquals(listOf(0, 1, 2), items.map { it.id.userId })
        assertEquals(listOf(1, 2, 3), items.map { it.number })
    }

    @Test
    fun statesAreFocusedRunningOrIdle() {
        val items = SwitcherModel.items(listOf(a0, a1, a2), a1.id, setOf(a0.id, a1.id))
        assertEquals(
            listOf(SwitcherState.RUNNING, SwitcherState.FOCUSED, SwitcherState.IDLE),
            items.map { it.state },
        )
    }

    @Test
    fun focusedWinsEvenWhenNotInTheRunningSet() {
        val items = SwitcherModel.items(listOf(a0, a1), a0.id, emptySet())
        assertEquals(SwitcherState.FOCUSED, items[0].state)
        assertEquals(SwitcherState.IDLE, items[1].state)
    }

    @Test
    fun labelsComeFromTheLabelFunctionOrTheDisplayName() {
        val named = record("com.a", 3, order = 4, name = "Work")
        val items = SwitcherModel.items(listOf(a0, named), a0.id, emptySet())
        assertEquals(listOf("com.a 1", "Work"), items.map { it.label })
        val custom = SwitcherModel.items(listOf(a0), a0.id, emptySet()) { "X${it.number}" }
        assertEquals("X1", custom.single().label)
    }

    @Test
    fun debouncerShowsAtOnceAndHidesAfterTwoMissedPolls() {
        val d = FocusDebouncer()
        assertNull(d.onPoll(null))
        assertEquals(a0.id, d.onPoll(a0.id))
        assertEquals(a0.id, d.onPoll(null))
        assertNull(d.onPoll(null))
    }

    @Test
    fun aSingleMissDuringATransitionDoesNotHideAndResetsTheCount() {
        val d = FocusDebouncer()
        d.onPoll(a0.id)
        assertEquals(a0.id, d.onPoll(null))
        assertEquals(a1.id, d.onPoll(a1.id))
        assertEquals(a1.id, d.onPoll(null))
        assertNull(d.onPoll(null))
    }

    @Test
    fun geometrySnapsClampsAndCountsRows() {
        assertEquals(SwitcherGeometry.EDGE_LEFT, SwitcherGeometry.nearestEdge(100f, 1080))
        assertEquals(SwitcherGeometry.EDGE_RIGHT, SwitcherGeometry.nearestEdge(700f, 1080))
        assertEquals(0, SwitcherGeometry.xFor(SwitcherGeometry.EDGE_LEFT, 200, 1080))
        assertEquals(880, SwitcherGeometry.xFor(SwitcherGeometry.EDGE_RIGHT, 200, 1080))
        assertEquals(0, SwitcherGeometry.clampY(-50, 300, 2000))
        assertEquals(1700, SwitcherGeometry.clampY(1900, 300, 2000))
        assertEquals(0, SwitcherGeometry.clampY(100, 3000, 2000))
        assertEquals(0, SwitcherGeometry.visibleRows(0))
        assertEquals(1, SwitcherGeometry.visibleRows(1))
        assertEquals(1, SwitcherGeometry.visibleRows(2))
        assertEquals(2, SwitcherGeometry.visibleRows(3))
        assertEquals(5, SwitcherGeometry.visibleRows(10))
        assertEquals(5, SwitcherGeometry.visibleRows(11))
    }
}
