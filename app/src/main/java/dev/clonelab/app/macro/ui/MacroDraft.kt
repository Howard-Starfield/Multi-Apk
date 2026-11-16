package dev.clonelab.app.macro.ui

import dev.clonelab.app.macro.Macro
import dev.clonelab.app.macro.MacroSchedule
import dev.clonelab.app.macro.MacroStep

enum class DraftProblem { BLANK_NAME, NO_STEPS }

/**
 * The editor's in-memory copy of a macro. Immutable: every edit returns a new draft, so "nothing
 * changed" is plain equality and a hidden editor can be restored exactly.
 */
data class MacroDraft(
    val id: String,
    val name: String,
    val packageName: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val steps: List<MacroStep>,
    val repeatCount: Int = 1,
    val repeatDelayMs: Long = 1000,
    val schedule: MacroSchedule = MacroSchedule.Off,
    val createdAt: Long,
    /** The last finite repeat count, so switching Loop off again restores it. */
    val lastFiniteRepeat: Int = 1,
) {
    val isLoop: Boolean get() = repeatCount == 0

    fun withName(value: String) = copy(name = value)

    fun withStepDelay(index: Int, ms: Long): MacroDraft {
        if (index !in steps.indices) return this
        val value = ms.coerceIn(0, MacroFormat.MAX_DELAY_MS)
        return copy(steps = steps.mapIndexed { i, s -> if (i == index) s.copy(delayBeforeMs = value) else s })
    }

    fun withoutStep(index: Int): MacroDraft =
        if (index !in steps.indices) this else copy(steps = steps.filterIndexed { i, _ -> i != index })

    /** 0 means until stopped (see [withLoop]); a positive count is remembered as the last finite one. */
    fun withRepeat(count: Int): MacroDraft {
        val value = count.coerceAtLeast(0)
        return copy(repeatCount = value, lastFiniteRepeat = if (value > 0) value else lastFiniteRepeat)
    }

    /** Loop on = repeat until stopped (count 0); off = back to the last finite count the user had. */
    fun withLoop(on: Boolean): MacroDraft = when {
        on -> copy(repeatCount = 0)
        isLoop -> copy(repeatCount = lastFiniteRepeat.coerceAtLeast(1))
        else -> this
    }

    fun withRepeatDelay(ms: Long) = copy(repeatDelayMs = ms.coerceIn(0, MacroFormat.MAX_DELAY_MS))

    fun withSchedule(value: MacroSchedule) = copy(schedule = value)

    fun problem(): DraftProblem? = when {
        name.isBlank() -> DraftProblem.BLANK_NAME
        steps.isEmpty() -> DraftProblem.NO_STEPS
        else -> null
    }

    /**
     * The macro to store. A scheduled macro targets [scheduleUserId]; when the schedule differs from
     * [previous] (or the macro is new) the schedule clock starts at [now] so it does not fire at once.
     */
    fun toMacro(scheduleUserId: Int?, now: Long, previous: Macro?): Macro {
        val scheduled = schedule != MacroSchedule.Off
        val changed = schedule != (previous?.schedule ?: MacroSchedule.Off)
        return Macro(
            id = id,
            name = name.trim(),
            packageName = packageName,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            steps = steps,
            repeatCount = repeatCount,
            repeatDelayMs = repeatDelayMs,
            schedule = schedule,
            scheduleUserId = if (scheduled) scheduleUserId else null,
            lastScheduledRunAt = if (changed) now else previous?.lastScheduledRunAt ?: 0,
            createdAt = createdAt,
        )
    }

    /** What a test run plays: the current state, once, never scheduled. */
    fun toTestMacro(): Macro =
        toMacro(null, 0, null).copy(repeatCount = 1, schedule = MacroSchedule.Off, scheduleUserId = null)

    companion object {
        fun fromMacro(macro: Macro) = MacroDraft(
            id = macro.id,
            name = macro.name,
            packageName = macro.packageName,
            screenWidth = macro.screenWidth,
            screenHeight = macro.screenHeight,
            steps = macro.steps,
            repeatCount = macro.repeatCount,
            repeatDelayMs = macro.repeatDelayMs,
            schedule = macro.schedule,
            createdAt = macro.createdAt,
            lastFiniteRepeat = if (macro.repeatCount > 0) macro.repeatCount else 1,
        )

        fun recorded(
            id: String,
            name: String,
            packageName: String,
            screenSize: Pair<Int, Int>,
            steps: List<MacroStep>,
            createdAt: Long,
        ) = MacroDraft(
            id = id,
            name = name,
            packageName = packageName,
            screenWidth = screenSize.first,
            screenHeight = screenSize.second,
            steps = steps,
            createdAt = createdAt,
        )
    }
}
