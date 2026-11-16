package dev.clonelab.app.macro.ui

import dev.clonelab.app.macro.MacroAction
import dev.clonelab.app.macro.MacroStep
import kotlin.math.roundToInt

enum class StepKind { TAP, LONG_PRESS, SWIPE, KEY }

/** A step description as data: the UI turns it into text with a string resource per kind. */
data class StepText(val kind: StepKind, val values: List<Any>)

/** Pure formatting and stepping rules of the macro editor, kept free of Android types. */
object MacroFormat {
    const val LONG_PRESS_MS = 500L
    const val MAX_DELAY_MS = 3_600_000L
    private const val FINE_LIMIT_MS = 2_000L
    private const val FINE_STEP_MS = 100L
    private const val COARSE_STEP_MS = 500L

    /** Values of the repeat stepper; 0 means "until stopped" and comes last. */
    val REPEAT_VALUES = listOf(1, 2, 3, 5, 10, 20, 50, 100, 0)
    val INTERVAL_MINUTES = listOf(1, 5, 10, 15, 30, 60, 120, 360, 720)
    private const val MINUTE_STEP = 5

    fun describe(step: MacroStep, keyName: (Int) -> String): StepText = when (val a = step.action) {
        is MacroAction.Tap ->
            if (a.holdMs >= LONG_PRESS_MS) {
                StepText(StepKind.LONG_PRESS, listOf(a.x.roundToInt(), a.y.roundToInt(), a.holdMs))
            } else {
                StepText(StepKind.TAP, listOf(a.x.roundToInt(), a.y.roundToInt()))
            }
        is MacroAction.Swipe -> {
            val first = a.points.first()
            val last = a.points.last()
            StepText(
                StepKind.SWIPE,
                listOf(first.x.roundToInt(), first.y.roundToInt(), last.x.roundToInt(), last.y.roundToInt(), last.tMs - first.tMs),
            )
        }
        is MacroAction.Key -> StepText(StepKind.KEY, listOf(keyLabel(a.keyCode, keyName)))
    }

    /** "KEYCODE_BACK" becomes "BACK". */
    fun keyLabel(keyCode: Int, keyName: (Int) -> String): String =
        keyName(keyCode).removePrefix("KEYCODE_")

    /** 800 -> "0.8 s", 2500 -> "2.5 s". Rounded to a tenth; no locale involved. */
    fun formatSeconds(ms: Long): String {
        val tenths = (ms.coerceAtLeast(0) + 50) / 100
        return "${tenths / 10}.${tenths % 10} s"
    }

    /** One step up: 0.1 s below 2 s, 0.5 s from there on. */
    fun delayUp(ms: Long): Long =
        (if (ms < FINE_LIMIT_MS) ms + FINE_STEP_MS else ms + COARSE_STEP_MS).coerceAtMost(MAX_DELAY_MS)

    /** One step down, never below zero; 2.5 s -> 2.0 s -> 1.9 s. */
    fun delayDown(ms: Long): Long = when {
        ms <= 0 -> 0
        ms <= FINE_LIMIT_MS -> (ms - FINE_STEP_MS).coerceAtLeast(0)
        else -> (ms - COARSE_STEP_MS).coerceAtLeast(FINE_LIMIT_MS)
    }

    /** Typed milliseconds: digits only, clamped to an hour; null for anything else. */
    fun parseMillis(text: String): Long? {
        val digits = text.trim()
        if (digits.isEmpty() || digits.length > 12 || !digits.all { it in '0'..'9' }) return null
        return digits.toLong().coerceAtMost(MAX_DELAY_MS)
    }

    /** Next value of [values] above/below [current]; stays put at either end. Off-list values snap to a neighbour. */
    fun stepList(values: List<Int>, current: Int, up: Boolean): Int {
        val ordered = values.sortedBy { rank(it) }
        val r = rank(current)
        return if (up) {
            ordered.firstOrNull { rank(it) > r } ?: ordered.last()
        } else {
            ordered.lastOrNull { rank(it) < r } ?: ordered.first()
        }
    }

    /** 0 ("until stopped") ranks above every number. */
    private fun rank(value: Int): Long = if (value == 0) Long.MAX_VALUE else value.toLong()

    fun stepRepeat(current: Int, up: Boolean): Int = stepList(REPEAT_VALUES, current, up)

    fun stepInterval(current: Int, up: Boolean): Int = stepList(INTERVAL_MINUTES, current, up)

    /** Hours wrap around. */
    fun stepHour(hour: Int, up: Boolean): Int = Math.floorMod(hour + if (up) 1 else -1, 24)

    /** Steps of 5 that snap to the grid first (07 up -> 10, 07 down -> 05); wraps around the hour. */
    fun stepMinute(minute: Int, up: Boolean): Int =
        if (up) {
            ((minute / MINUTE_STEP + 1) * MINUTE_STEP) % 60
        } else {
            Math.floorMod(((minute + MINUTE_STEP - 1) / MINUTE_STEP - 1) * MINUTE_STEP, 60)
        }

    fun formatClock(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

    /** 75_000 -> "01:15". */
    fun formatElapsed(ms: Long): String {
        val total = ms.coerceAtLeast(0) / 1000
        return "%02d:%02d".format(total / 60, total % 60)
    }
}
