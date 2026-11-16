package dev.clonelab.app.macro

/** One thing a macro does. Coordinates are screen pixels at the screen size the macro was recorded on. */
sealed interface MacroAction {
    /** [holdMs] >= 500 reads as "long press" in the UI. */
    data class Tap(val x: Float, val y: Float, val holdMs: Long) : MacroAction

    /** At least two points; [SwipePoint.tMs] is relative to the first point. */
    data class Swipe(val points: List<SwipePoint>) : MacroAction {
        init {
            require(points.size >= 2) { "A swipe needs at least two points" }
        }
    }

    data class Key(val keyCode: Int) : MacroAction
}

data class SwipePoint(val tMs: Long, val x: Float, val y: Float)

/** [delayBeforeMs] is measured from the end of the previous step; the first step has 0. */
data class MacroStep(val delayBeforeMs: Long, val action: MacroAction)

sealed interface MacroSchedule {
    data object Off : MacroSchedule

    data class EveryMinutes(val minutes: Int) : MacroSchedule {
        init {
            require(minutes >= 1) { "minutes must be >= 1" }
        }
    }

    data class DailyAt(val hour: Int, val minute: Int) : MacroSchedule {
        init {
            require(hour in 0..23 && minute in 0..59) { "Not a time of day: $hour:$minute" }
        }
    }
}

data class Macro(
    val id: String,                    // UUID
    val name: String,
    val packageName: String,           // macros belong to an app; playable on any clone of it
    val screenWidth: Int,              // screen size when recorded, for scaling
    val screenHeight: Int,
    val steps: List<MacroStep>,
    val repeatCount: Int = 1,          // 0 = until stopped
    val repeatDelayMs: Long = 1000,
    val schedule: MacroSchedule = MacroSchedule.Off,
    val scheduleUserId: Int? = null,   // which clone a scheduled run targets
    val lastScheduledRunAt: Long = 0,  // wall-clock ms
    val createdAt: Long,
)
