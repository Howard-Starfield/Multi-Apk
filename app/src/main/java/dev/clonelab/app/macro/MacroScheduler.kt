package dev.clonelab.app.macro

import java.util.Calendar
import java.util.TimeZone

/** Decides whether a scheduled macro should run now. Pure. */
object MacroScheduler {
    private const val MINUTE_MS = 60_000L

    /**
     * A macro that never ran counts as if it ran at [Macro.createdAt], for both kinds of schedule: an
     * every-N macro first fires one interval after creation, a daily one waits for the next h:m (so
     * saving a daily macro at 15:00 does not run it at once because 09:00 has passed).
     * Needs [Macro.scheduleUserId].
     */
    fun due(macro: Macro, nowMs: Long, zone: TimeZone): Boolean {
        if (macro.scheduleUserId == null) return false
        val lastRun = baseline(macro, nowMs)
        return when (val schedule = macro.schedule) {
            MacroSchedule.Off -> false
            is MacroSchedule.EveryMinutes -> nowMs - lastRun >= schedule.minutes * MINUTE_MS
            is MacroSchedule.DailyAt -> {
                val todayAt = todayAt(schedule, nowMs, zone)
                nowMs >= todayAt && lastRun < todayAt
            }
        }
    }

    /**
     * When the macro last ran, or was created if it never did, never later than [nowMs]. A last run in
     * the future means the clock was wrong when it was stored (or has been set back since): that
     * timestamp says nothing, so the creation time stands in for it and the schedule cannot stay frozen
     * until the real time catches up.
     */
    fun baseline(macro: Macro, nowMs: Long): Long {
        val lastRunOrCreated = if (macro.lastScheduledRunAt == 0L) macro.createdAt else macro.lastScheduledRunAt
        val usable = if (lastRunOrCreated > nowMs) macro.createdAt else lastRunOrCreated
        return minOf(usable, nowMs)
    }

    private fun todayAt(schedule: MacroSchedule.DailyAt, nowMs: Long, zone: TimeZone): Long =
        Calendar.getInstance(zone).apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, schedule.hour)
            set(Calendar.MINUTE, schedule.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
}
