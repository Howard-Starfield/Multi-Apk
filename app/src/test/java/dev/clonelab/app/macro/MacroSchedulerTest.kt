package dev.clonelab.app.macro

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class MacroSchedulerTest {
    private val zone = TimeZone.getTimeZone("UTC")
    private val minute = 60_000L

    private fun at(hour: Int, minute: Int, second: Int = 0, dayOfMonth: Int = 15): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(2026, Calendar.MARCH, dayOfMonth, hour, minute, second)
        }.timeInMillis

    private fun macro(
        schedule: MacroSchedule,
        lastRun: Long = 0,
        createdAt: Long = 0,
        userId: Int? = 0,
    ) = Macro(
        id = "m", name = "m", packageName = "p", screenWidth = 1, screenHeight = 1, steps = emptyList(),
        schedule = schedule, scheduleUserId = userId, lastScheduledRunAt = lastRun, createdAt = createdAt,
    )

    @Test
    fun everyNIsDueExactlyAtTheInterval() {
        val m = macro(MacroSchedule.EveryMinutes(10), lastRun = 1_000_000)
        assertFalse(MacroScheduler.due(m, 1_000_000 + 10 * minute - 1, zone))
        assertTrue(MacroScheduler.due(m, 1_000_000 + 10 * minute, zone))
        assertTrue(MacroScheduler.due(m, 1_000_000 + 60 * minute, zone))
    }

    @Test
    fun neverRunEveryNCountsFromCreation() {
        val m = macro(MacroSchedule.EveryMinutes(5), lastRun = 0, createdAt = 2_000_000)
        assertFalse(MacroScheduler.due(m, 2_000_000 + 5 * minute - 1, zone))
        assertTrue(MacroScheduler.due(m, 2_000_000 + 5 * minute, zone))
    }

    @Test
    fun dailyIsNotDueBeforeItsTime() {
        val m = macro(MacroSchedule.DailyAt(9, 30), lastRun = at(9, 30, dayOfMonth = 14))
        assertFalse(MacroScheduler.due(m, at(9, 29, 59), zone))
    }

    @Test
    fun dailyIsDueAtAndAfterItsTime() {
        val m = macro(MacroSchedule.DailyAt(9, 30), lastRun = at(9, 31, dayOfMonth = 14))
        assertTrue(MacroScheduler.due(m, at(9, 30), zone))
        assertTrue(MacroScheduler.due(m, at(23, 0), zone))
    }

    @Test
    fun dailyThatAlreadyRanTodayIsNotDueAgain() {
        val m = macro(MacroSchedule.DailyAt(9, 30), lastRun = at(9, 30, 5))
        assertFalse(MacroScheduler.due(m, at(9, 31), zone))
        assertFalse(MacroScheduler.due(m, at(23, 59), zone))
        assertTrue(MacroScheduler.due(m, at(9, 30, dayOfMonth = 16), zone))
    }

    @Test
    fun dailyCreatedAfterTodaysTimeWaitsForTomorrow() {
        val m = macro(MacroSchedule.DailyAt(9, 0), lastRun = 0, createdAt = at(15, 0))
        assertFalse(MacroScheduler.due(m, at(15, 1), zone))
        assertTrue(MacroScheduler.due(m, at(9, 0, dayOfMonth = 16), zone))
    }

    @Test
    fun dailyUsesTheGivenTimeZone() {
        val plus2 = TimeZone.getTimeZone("GMT+02:00")
        val m = macro(MacroSchedule.DailyAt(9, 0), lastRun = at(0, 0, dayOfMonth = 1))
        assertFalse(MacroScheduler.due(m, at(6, 59), plus2))
        assertTrue(MacroScheduler.due(m, at(7, 0), plus2))
    }

    @Test
    fun offIsNeverDue() {
        assertFalse(MacroScheduler.due(macro(MacroSchedule.Off), Long.MAX_VALUE / 2, zone))
    }

    @Test
    fun withoutATargetCloneNothingIsDue() {
        assertFalse(MacroScheduler.due(macro(MacroSchedule.EveryMinutes(1), userId = null), Long.MAX_VALUE / 2, zone))
        assertFalse(MacroScheduler.due(macro(MacroSchedule.DailyAt(0, 0), userId = null), at(12, 0), zone))
    }
}
