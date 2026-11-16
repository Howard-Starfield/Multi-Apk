package dev.clonelab.app.macro.ui

import dev.clonelab.app.macro.MacroAction
import dev.clonelab.app.macro.MacroStep
import dev.clonelab.app.macro.SwipePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MacroFormatTest {
    private val names = { code: Int -> if (code == 4) "KEYCODE_BACK" else "KEYCODE_$code" }

    private fun tap(holdMs: Long) = MacroStep(0, MacroAction.Tap(540f, 1200.4f, holdMs))

    @Test
    fun shortTapIsATap() {
        assertEquals(StepText(StepKind.TAP, listOf(540, 1200)), MacroFormat.describe(tap(80), names))
    }

    @Test
    fun holdOfHalfASecondIsALongPress() {
        assertEquals(StepText(StepKind.LONG_PRESS, listOf<Any>(540, 1200, 500L)), MacroFormat.describe(tap(500), names))
        assertEquals(StepKind.TAP, MacroFormat.describe(tap(499), names).kind)
    }

    @Test
    fun swipeUsesFirstAndLastPointAndDuration() {
        val swipe = MacroStep(
            0,
            MacroAction.Swipe(listOf(SwipePoint(0, 10f, 20f), SwipePoint(100, 50f, 60f), SwipePoint(320, 100f, 200f))),
        )
        assertEquals(StepText(StepKind.SWIPE, listOf<Any>(10, 20, 100, 200, 320L)), MacroFormat.describe(swipe, names))
    }

    @Test
    fun keyNameLosesItsPrefix() {
        assertEquals(StepText(StepKind.KEY, listOf("BACK")), MacroFormat.describe(MacroStep(0, MacroAction.Key(4)), names))
    }

    @Test
    fun secondsHaveOneDecimal() {
        assertEquals("0.0 s", MacroFormat.formatSeconds(0))
        assertEquals("0.8 s", MacroFormat.formatSeconds(800))
        assertEquals("0.8 s", MacroFormat.formatSeconds(750))
        assertEquals("2.5 s", MacroFormat.formatSeconds(2500))
        assertEquals("12.0 s", MacroFormat.formatSeconds(12_000))
        assertEquals("0.0 s", MacroFormat.formatSeconds(-5))
    }

    @Test
    fun delayStepsAreFineBelowTwoSecondsAndCoarseAbove() {
        assertEquals(100, MacroFormat.delayUp(0))
        assertEquals(2000, MacroFormat.delayUp(1900))
        assertEquals(2500, MacroFormat.delayUp(2000))
        assertEquals(3000, MacroFormat.delayUp(2500))
        assertEquals(1900, MacroFormat.delayDown(2000))
        assertEquals(2000, MacroFormat.delayDown(2300))
        assertEquals(2000, MacroFormat.delayDown(2500))
        assertEquals(0, MacroFormat.delayDown(50))
        assertEquals(0, MacroFormat.delayDown(0))
        assertEquals(MacroFormat.MAX_DELAY_MS, MacroFormat.delayUp(MacroFormat.MAX_DELAY_MS))
    }

    @Test
    fun typedMillisecondsAreParsedStrictly() {
        assertEquals(750L, MacroFormat.parseMillis(" 750 "))
        assertEquals(0L, MacroFormat.parseMillis("0"))
        assertNull(MacroFormat.parseMillis(""))
        assertNull(MacroFormat.parseMillis("-5"))
        assertNull(MacroFormat.parseMillis("1.5"))
        assertEquals(MacroFormat.MAX_DELAY_MS, MacroFormat.parseMillis("99999999"))
    }

    @Test
    fun repeatStepperWalksTheListAndEndsAtUntilStopped() {
        assertEquals(2, MacroFormat.stepRepeat(1, up = true))
        assertEquals(100, MacroFormat.stepRepeat(50, up = true))
        assertEquals(0, MacroFormat.stepRepeat(100, up = true))
        assertEquals(0, MacroFormat.stepRepeat(0, up = true))
        assertEquals(100, MacroFormat.stepRepeat(0, up = false))
        assertEquals(1, MacroFormat.stepRepeat(1, up = false))
        assertEquals(5, MacroFormat.stepRepeat(4, up = true))
        assertEquals(3, MacroFormat.stepRepeat(4, up = false))
    }

    @Test
    fun intervalStepperWalksTheListAndStopsAtEnds() {
        assertEquals(5, MacroFormat.stepInterval(1, up = true))
        assertEquals(720, MacroFormat.stepInterval(360, up = true))
        assertEquals(720, MacroFormat.stepInterval(720, up = true))
        assertEquals(1, MacroFormat.stepInterval(1, up = false))
        assertEquals(60, MacroFormat.stepInterval(120, up = false))
        assertEquals(30, MacroFormat.stepInterval(45, up = false))
    }

    @Test
    fun clockSteppersWrapAndSnapMinutesToFive() {
        assertEquals(0, MacroFormat.stepHour(23, up = true))
        assertEquals(23, MacroFormat.stepHour(0, up = false))
        assertEquals(10, MacroFormat.stepMinute(7, up = true))
        assertEquals(5, MacroFormat.stepMinute(7, up = false))
        assertEquals(0, MacroFormat.stepMinute(55, up = true))
        assertEquals(55, MacroFormat.stepMinute(0, up = false))
        assertEquals(0, MacroFormat.stepMinute(5, up = false))
        assertEquals("09:05", MacroFormat.formatClock(9, 5))
    }

    @Test
    fun elapsedIsMinutesAndSeconds() {
        assertEquals("00:00", MacroFormat.formatElapsed(999))
        assertEquals("01:15", MacroFormat.formatElapsed(75_000))
        assertEquals("100:00", MacroFormat.formatElapsed(6_000_000))
    }
}
