package dev.clonelab.app.macro

import dev.clonelab.app.macro.RawEvent.Kind.KEY
import dev.clonelab.app.macro.RawEvent.Kind.TOUCH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureClassifierTest {
    private val slop = 20f

    private fun down(t: Long, x: Float, y: Float) = RawEvent(TOUCH, 0, t, x, y)
    private fun up(t: Long, x: Float, y: Float) = RawEvent(TOUCH, 1, t, x, y)
    private fun move(t: Long, x: Float, y: Float) = RawEvent(TOUCH, 2, t, x, y)
    private fun cancel(t: Long, x: Float, y: Float) = RawEvent(TOUCH, 3, t, x, y)
    private fun keyDown(t: Long, code: Int) = RawEvent(KEY, 0, t, keyCode = code)
    private fun keyUp(t: Long, code: Int) = RawEvent(KEY, 1, t, keyCode = code)

    @Test
    fun tapWithSmallJitterIsATapAtTheDownPosition() {
        val steps = GestureClassifier.classify(listOf(down(100, 50f, 60f), move(110, 55f, 62f), up(160, 52f, 61f)), slop)
        assertEquals(listOf(MacroStep(0, MacroAction.Tap(50f, 60f, 60))), steps)
    }

    @Test
    fun longPressKeepsItsHoldTime() {
        val steps = GestureClassifier.classify(listOf(down(0, 10f, 10f), up(900, 10f, 10f)), slop)
        assertEquals(MacroAction.Tap(10f, 10f, 900), steps.single().action)
    }

    @Test
    fun cancelEndsAGestureLikeUp() {
        val steps = GestureClassifier.classify(listOf(down(0, 1f, 1f), cancel(30, 1f, 1f)), slop)
        assertEquals(MacroAction.Tap(1f, 1f, 30), steps.single().action)
    }

    @Test
    fun movementBeyondSlopIsASwipeRelativeToDown() {
        val steps = GestureClassifier.classify(
            listOf(down(1000, 0f, 0f), move(1050, 100f, 0f), move(1100, 200f, 0f), up(1150, 300f, 0f)),
            slop,
        )
        val swipe = steps.single().action as MacroAction.Swipe
        assertEquals(
            listOf(SwipePoint(0, 0f, 0f), SwipePoint(50, 100f, 0f), SwipePoint(100, 200f, 0f), SwipePoint(150, 300f, 0f)),
            swipe.points,
        )
    }

    @Test
    fun consecutiveDuplicatePositionsAreDroppedButTheEndIsKept() {
        val steps = GestureClassifier.classify(
            listOf(down(0, 0f, 0f), move(10, 100f, 0f), move(20, 100f, 0f), move(30, 100f, 0f), up(40, 100f, 0f)),
            slop,
        )
        val points = (steps.single().action as MacroAction.Swipe).points
        assertEquals(listOf(SwipePoint(0, 0f, 0f), SwipePoint(10, 100f, 0f), SwipePoint(40, 100f, 0f)), points)
    }

    @Test
    fun longSwipesAreSampledToTheCapKeepingFirstAndLast() {
        val events = mutableListOf(down(0, 0f, 0f))
        for (i in 1..499) events += move(i * 10L, i.toFloat() * 3, 0f)
        events += up(5000, 1500f, 0f)
        val points = (GestureClassifier.classify(events, slop).single().action as MacroAction.Swipe).points
        assertEquals(GestureClassifier.MAX_SWIPE_POINTS, points.size)
        assertEquals(SwipePoint(0, 0f, 0f), points.first())
        assertEquals(SwipePoint(5000, 1500f, 0f), points.last())
        assertTrue(points.zipWithNext().all { (a, b) -> b.tMs > a.tMs })
    }

    @Test
    fun swipeThatReturnsToItsStartIsStillASwipe() {
        val steps = GestureClassifier.classify(listOf(down(0, 0f, 0f), move(10, 200f, 0f), up(20, 0f, 0f)), slop)
        assertEquals(3, (steps.single().action as MacroAction.Swipe).points.size)
    }

    @Test
    fun keyDownAndUpOfTheSameCodeMakeOneStep() {
        val steps = GestureClassifier.classify(listOf(keyDown(0, 4), keyUp(80, 4)), slop)
        assertEquals(listOf(MacroStep(0, MacroAction.Key(4))), steps)
    }

    @Test
    fun keyRepeatsAreIgnored() {
        val steps = GestureClassifier.classify(listOf(keyDown(0, 66), keyDown(50, 66), keyDown(100, 66), keyUp(150, 66)), slop)
        assertEquals(1, steps.size)
    }

    @Test
    fun unmatchedKeyDownStillYieldsAStep() {
        val steps = GestureClassifier.classify(listOf(keyDown(0, 25)), slop)
        assertEquals(listOf(MacroStep(0, MacroAction.Key(25))), steps)
    }

    @Test
    fun delaysAreMeasuredFromThePreviousUp() {
        val steps = GestureClassifier.classify(
            listOf(
                down(1000, 10f, 10f), up(1100, 10f, 10f),
                down(1600, 20f, 20f), up(1650, 20f, 20f),
                keyDown(2000, 4), keyUp(2040, 4),
                down(2100, 5f, 5f), up(2110, 5f, 5f),
            ),
            slop,
        )
        assertEquals(listOf(0L, 500L, 350L, 60L), steps.map { it.delayBeforeMs })
    }

    @Test
    fun unfinishedGestureIsClosedAtItsLastSample() {
        val swipe = GestureClassifier.classify(listOf(down(0, 0f, 0f), move(40, 150f, 0f), move(80, 300f, 0f)), slop)
        assertEquals(SwipePoint(80, 300f, 0f), (swipe.single().action as MacroAction.Swipe).points.last())

        val tap = GestureClassifier.classify(listOf(down(0, 7f, 8f)), slop)
        assertEquals(MacroAction.Tap(7f, 8f, 0), tap.single().action)
    }

    @Test
    fun aNewDownClosesAGestureThatNeverEnded() {
        val steps = GestureClassifier.classify(listOf(down(0, 1f, 1f), move(10, 2f, 2f), down(500, 9f, 9f), up(520, 9f, 9f)), slop)
        assertEquals(2, steps.size)
        assertEquals(490L, steps[1].delayBeforeMs)
    }

    @Test
    fun outOfOrderEventsAreSortedByTime() {
        val steps = GestureClassifier.classify(
            listOf(up(160, 52f, 61f), down(100, 50f, 60f), down(300, 5f, 5f), up(320, 5f, 5f)),
            slop,
        )
        assertEquals(listOf(MacroStep(0, MacroAction.Tap(50f, 60f, 60)), MacroStep(140, MacroAction.Tap(5f, 5f, 20))), steps)
    }

    @Test
    fun strayMoveAndUpWithoutDownAreIgnored() {
        assertTrue(GestureClassifier.classify(listOf(move(0, 1f, 1f), up(5, 1f, 1f)), slop).isEmpty())
    }

    @Test
    fun emptyInputGivesNoSteps() {
        assertTrue(GestureClassifier.classify(emptyList(), slop).isEmpty())
    }

    @Test
    fun parsesTouchAndKeyLines() {
        assertEquals(down(12, 3.5f, 4.5f), parseRawLine("""{"k":"t","a":0,"t":12,"x":3.5,"y":4.5}"""))
        assertEquals(keyUp(99, 4), parseRawLine("""{"k":"k","a":1,"t":99,"c":4}"""))
    }

    @Test
    fun garbageLinesParseToNull() {
        assertNull(parseRawLine(""))
        assertNull(parseRawLine("not json"))
        assertNull(parseRawLine("""{"k":"x","a":0,"t":1}"""))
        assertNull(parseRawLine("""{"k":"t","a":0,"t":1}"""))
        assertNull(parseRawLine("""{"k":"t","a":0,"t":1,"x":1.0"""))
        assertNotNull(parseRawLine("""{"k":"k","a":0,"t":1,"c":3}"""))
    }
}
