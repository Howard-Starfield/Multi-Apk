package dev.clonelab.app.macro.ui

import dev.clonelab.app.macro.Macro
import dev.clonelab.app.macro.MacroAction
import dev.clonelab.app.macro.MacroSchedule
import dev.clonelab.app.macro.MacroStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MacroDraftTest {
    private fun step(delay: Long) = MacroStep(delay, MacroAction.Key(4))

    private val draft = MacroDraft.recorded("id", "Macro 1", "pkg", 1080 to 2340, listOf(step(0), step(500), step(900)), 42)

    private fun stored(schedule: MacroSchedule, lastRun: Long = 555, userId: Int? = 1) = Macro(
        "id", "n", "pkg", 1, 1, draft.steps, schedule = schedule, scheduleUserId = userId,
        lastScheduledRunAt = lastRun, createdAt = 42,
    )

    @Test
    fun recordedDraftHasTheDefaults() {
        assertEquals(1, draft.repeatCount)
        assertEquals(1000L, draft.repeatDelayMs)
        assertEquals(MacroSchedule.Off, draft.schedule)
        assertEquals(1080, draft.screenWidth)
    }

    @Test
    fun stepDelayChangesOnlyThatStep() {
        val changed = draft.withStepDelay(1, 700)
        assertEquals(listOf(0L, 700L, 900L), changed.steps.map { it.delayBeforeMs })
        assertEquals(listOf(0L, 500L, 900L), draft.steps.map { it.delayBeforeMs })
    }

    @Test
    fun stepDelayIsClampedAndBadIndexIgnored() {
        assertEquals(0L, draft.withStepDelay(0, -3).steps[0].delayBeforeMs)
        assertEquals(MacroFormat.MAX_DELAY_MS, draft.withStepDelay(0, Long.MAX_VALUE).steps[0].delayBeforeMs)
        assertEquals(draft, draft.withStepDelay(9, 100))
        assertEquals(draft, draft.withStepDelay(-1, 100))
    }

    @Test
    fun removingAStepKeepsTheOthersInOrder() {
        assertEquals(listOf(0L, 900L), draft.withoutStep(1).steps.map { it.delayBeforeMs })
        assertEquals(draft, draft.withoutStep(3))
    }

    @Test
    fun repeatAndRepeatDelay() {
        assertEquals(0, draft.withRepeat(0).repeatCount)
        assertEquals(0, draft.withRepeat(-4).repeatCount)
        assertEquals(20, draft.withRepeat(20).repeatCount)
        assertEquals(2500L, draft.withRepeatDelay(2500).repeatDelayMs)
        assertEquals(0L, draft.withRepeatDelay(-1).repeatDelayMs)
    }

    @Test
    fun problemsAreReported() {
        assertNull(draft.problem())
        assertEquals(DraftProblem.BLANK_NAME, draft.withName("   ").problem())
        assertEquals(DraftProblem.NO_STEPS, draft.withoutStep(0).withoutStep(0).withoutStep(0).problem())
    }

    @Test
    fun newScheduledMacroStartsItsClockAndTargetsTheClone() {
        val macro = draft.withName("  Farm ").withSchedule(MacroSchedule.EveryMinutes(30))
            .toMacro(scheduleUserId = 2, now = 1000, previous = null)
        assertEquals("Farm", macro.name)
        assertEquals(2, macro.scheduleUserId)
        assertEquals(1000L, macro.lastScheduledRunAt)
        assertEquals(42L, macro.createdAt)
    }

    @Test
    fun unchangedScheduleKeepsItsClock() {
        val previous = stored(MacroSchedule.EveryMinutes(30))
        val macro = MacroDraft.fromMacro(previous).withName("renamed").toMacro(scheduleUserId = 3, now = 9999, previous = previous)
        assertEquals(555L, macro.lastScheduledRunAt)
        assertEquals(3, macro.scheduleUserId)
    }

    @Test
    fun changedScheduleRestartsTheClockAndSwitchingOffClearsTheTarget() {
        val previous = stored(MacroSchedule.EveryMinutes(30))
        val changed = MacroDraft.fromMacro(previous).withSchedule(MacroSchedule.DailyAt(9, 0)).toMacro(0, 7000, previous)
        assertEquals(7000L, changed.lastScheduledRunAt)
        val off = MacroDraft.fromMacro(previous).withSchedule(MacroSchedule.Off).toMacro(0, 8000, previous)
        assertNull(off.scheduleUserId)
        assertEquals(8000L, off.lastScheduledRunAt)
    }

    @Test
    fun roundTripThroughMacroIsEqualAndTestMacroIsOnceAndUnscheduled() {
        val edited = draft.withRepeat(0).withSchedule(MacroSchedule.DailyAt(8, 30))
        val macro = edited.toMacro(1, 5, null)
        assertEquals(edited, MacroDraft.fromMacro(macro))
        val test = MacroDraft.fromMacro(macro).toTestMacro()
        assertEquals(1, test.repeatCount)
        assertEquals(MacroSchedule.Off, test.schedule)
        assertNull(test.scheduleUserId)
        assertEquals(macro.steps, test.steps)
    }
}
