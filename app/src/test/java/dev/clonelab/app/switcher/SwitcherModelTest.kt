package dev.clonelab.app.switcher

import dev.clonelab.app.model.CloneId
import dev.clonelab.app.model.CloneRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ---- two levels: Apps and Clones

    private val none = SwitcherNav.NONE

    private fun reduce(
        records: List<CloneRecord>,
        focused: CloneId? = null,
        inLab: Boolean = false,
        nav: SwitcherNav = none,
        running: Set<CloneId> = emptySet(),
    ) = SwitcherModel.reduce(records, running, focused, inLab, nav)

    @Test
    fun hiddenWhenNothingQualifies() {
        // Neither a clone nor CloneLab in front.
        assertEquals(SwitcherView.Hidden, reduce(listOf(a0, b0)).view)
        // No clones at all, even inside CloneLab or with a focused clone.
        assertEquals(SwitcherView.Hidden, reduce(emptyList(), inLab = true).view)
        assertEquals(SwitcherView.Hidden, reduce(emptyList(), focused = a0.id).view)
        // A focused clone the store does not know, outside CloneLab.
        assertEquals(SwitcherView.Hidden, reduce(listOf(a0), focused = CloneId("com.zzz", 0)).view)
    }

    @Test
    fun appsLevelInsideCloneLabListsEveryApp() {
        val view = reduce(listOf(a0, b0, a1), inLab = true, running = setOf(a1.id)).view as SwitcherView.Apps
        assertEquals(listOf("com.a", "com.b"), view.apps.map { it.packageName })
        assertEquals(listOf(2, 1), view.apps.map { it.count })
        assertEquals(listOf(true, false), view.apps.map { it.anyRunning })
        assertEquals(a0, view.apps[0].representative)
    }

    @Test
    fun clonesLevelWhenACloneIsFocusedEvenInsideCloneLab() {
        for (inLab in listOf(false, true)) {
            val view = reduce(listOf(a0, b0, a1), focused = a1.id, inLab = inLab).view as SwitcherView.Clones
            assertEquals("com.a", view.packageName)
            assertEquals(listOf(a0.id, a1.id), view.items.map { it.id })
            assertEquals(listOf(SwitcherState.IDLE, SwitcherState.FOCUSED), view.items.map { it.state })
            assertTrue(view.showMacros)
        }
    }

    @Test
    fun drillingIntoAnAppAndBackInsideCloneLab() {
        val records = listOf(a0, b0, a1)
        val drilled = reduce(records, inLab = true, nav = SwitcherNav.drill(null, "com.a"), running = setOf(a0.id))
        val clones = drilled.view as SwitcherView.Clones
        assertEquals(listOf(SwitcherState.RUNNING, SwitcherState.IDLE), clones.items.map { it.state })
        assertFalse("Macros act on a focused clone", clones.showMacros)
        // The choice is kept while CloneLab stays in front.
        assertEquals(clones, reduce(records, inLab = true, nav = drilled.nav, running = setOf(a0.id)).view)
        // Back returns to the Apps level.
        val back = reduce(records, inLab = true, nav = SwitcherNav.back(null))
        assertTrue(back.view is SwitcherView.Apps)
    }

    @Test
    fun backFromAFocusedClonesAppShowsAppsAndAnotherAppCanBeOpened() {
        val records = listOf(a0, b0, a1)
        val apps = reduce(records, focused = a0.id, nav = SwitcherNav.back(a0.id))
        assertTrue(apps.view is SwitcherView.Apps)
        val other = reduce(records, focused = a0.id, nav = SwitcherNav.drill(a0.id, "com.b")).view as SwitcherView.Clones
        assertEquals("com.b", other.packageName)
        assertFalse("the focused clone belongs to another app", other.showMacros)
        assertTrue(other.items.none { it.state == SwitcherState.FOCUSED })
    }

    @Test
    fun theChoiceIsDroppedWhenTheFocusedCloneChanges() {
        val records = listOf(a0, b0, a1)
        val nav = SwitcherNav.back(a0.id)
        // Same focus: kept. Another clone focused: the default for that clone's app.
        assertTrue(reduce(records, focused = a0.id, nav = nav).view is SwitcherView.Apps)
        val moved = reduce(records, focused = b0.id, nav = nav)
        assertEquals("com.b", (moved.view as SwitcherView.Clones).packageName)
        assertEquals(b0.id, moved.nav.anchor)
        // Leaving a clone for CloneLab also drops a choice made while the clone was focused.
        val lab = reduce(records, inLab = true, nav = SwitcherNav.drill(a0.id, "com.a"))
        assertTrue(lab.view is SwitcherView.Apps)
    }

    @Test
    fun theSelectionIsResetOnceThePillWasHidden() {
        val records = listOf(a0, b0)
        val drilled = SwitcherNav.drill(null, "com.b")
        assertTrue(reduce(records, inLab = true, nav = drilled).view is SwitcherView.Clones)
        val hidden = reduce(records, inLab = false, nav = drilled)
        assertEquals(SwitcherView.Hidden, hidden.view)
        assertEquals(none, hidden.nav)
        // Shown again later: back at the Apps level.
        assertTrue(reduce(records, inLab = true, nav = hidden.nav).view is SwitcherView.Apps)
    }

    @Test
    fun anAppWhoseLastCloneWasRemovedFallsBackToApps() {
        val drilled = SwitcherNav.drill(null, "com.b")
        val after = reduce(listOf(a0, a1), inLab = true, nav = drilled)
        assertEquals(listOf("com.a"), (after.view as SwitcherView.Apps).apps.map { it.packageName })
        assertEquals(NavLevel.APPS, after.nav.level)
        assertNull(after.nav.packageName)
    }

    @Test
    fun appLabelsAreResolvedByTheCaller() {
        val view = SwitcherModel.reduce(listOf(a0), emptySet(), null, true, none, appLabelOf = { pkg, _ -> "L-$pkg" })
            .view as SwitcherView.Apps
        assertEquals("L-com.a", view.apps.single().label)
    }

    // ---- CloneLab in front

    @Test
    fun foregroundCounterTracksStartedScreensWithAGracePeriod() {
        var now = 1_000L
        val counter = ForegroundCounter({ now }, graceMs = 600)
        assertFalse(counter.isForeground())
        counter.onStarted()
        assertTrue(counter.isForeground())
        // Hop between two screens: the second starts before the first stops.
        counter.onStarted()
        counter.onStopped()
        assertTrue(counter.isForeground())
        counter.onStopped()
        // A rotation restarts the screen within the grace period.
        now += 300
        assertTrue(counter.isForeground())
        now += 400
        assertFalse(counter.isForeground())
    }

    @Test
    fun onlyOwnScreensCountNotTheTrampolineOrTheSetupGuide() {
        assertTrue(CloneLabForeground.counts("dev.clonelab.app.ui.home.HomeActivity"))
        assertTrue(CloneLabForeground.counts("dev.clonelab.app.ui.group.CloneGroupActivity"))
        assertFalse(CloneLabForeground.counts("dev.clonelab.app.shortcut.ShortcutTrampolineActivity"))
        assertFalse(CloneLabForeground.counts("dev.clonelab.app.ui.keepalive.KeepAliveGuideActivity"))
        assertFalse(CloneLabForeground.counts("top.niunaijun.blackbox.proxy.ProxyActivity"))
    }
}
