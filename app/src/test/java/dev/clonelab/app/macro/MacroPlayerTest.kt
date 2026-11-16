package dev.clonelab.app.macro

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroPlayerTest {

    private data class Event(val kind: String, val action: Int, val downTime: Long, val time: Long, val a: Float, val b: Float)

    /** Virtual time: sleeping advances the clock; injected events are stamped with it. */
    private class Harness(
        private val refuseAfter: Int = Int.MAX_VALUE,
        private val cancelOnSleep: Int = Int.MAX_VALUE,
    ) {
        var now = 10_000L
        val events = mutableListOf<Event>()
        val sleeps = mutableListOf<Long>()
        val progress = mutableListOf<Pair<Int, Int>>()
        var focusChecks = 0
        var focusLostOnCheck = Int.MAX_VALUE

        val injector = object : InputInjector {
            override fun pointer(action: Int, downTime: Long, eventTime: Long, x: Float, y: Float) {
                if (events.size >= refuseAfter) throw InjectionRefused("no")
                events += Event("p", action, downTime, eventTime, x, y)
            }

            override fun key(action: Int, downTime: Long, eventTime: Long, keyCode: Int) {
                if (events.size >= refuseAfter) throw InjectionRefused("no")
                events += Event("k", action, downTime, eventTime, keyCode.toFloat(), 0f)
            }
        }

        val player = MacroPlayer(
            injector = injector,
            clock = { now },
            sleeper = { ms ->
                sleeps += ms
                if (sleeps.size >= cancelOnSleep) throw CancellationException("stop")
                now += ms
            },
            isStillFocused = { ++focusChecks < focusLostOnCheck },
        )

        fun play(macro: Macro, w: Int = macro.screenWidth, h: Int = macro.screenHeight): PlayResult =
            runBlocking { player.play(macro, w, h) { r, s -> progress += r to s } }
    }

    private fun macro(vararg steps: MacroStep, repeat: Int = 1, repeatDelay: Long = 1000) = Macro(
        id = "m", name = "m", packageName = "p", screenWidth = 1000, screenHeight = 2000,
        steps = steps.toList(), repeatCount = repeat, repeatDelayMs = repeatDelay, createdAt = 0,
    )

    @Test
    fun tapIsDownWaitUpAtTheRightTimes() {
        val h = Harness()
        val result = h.play(macro(MacroStep(250, MacroAction.Tap(100f, 200f, 120))))
        assertEquals(PlayResult.Completed, result)
        assertEquals(listOf(250L, 120L), h.sleeps)
        assertEquals(
            listOf(
                Event("p", 0, 10_250, 10_250, 100f, 200f),
                Event("p", 1, 10_250, 10_370, 100f, 200f),
            ),
            h.events,
        )
    }

    @Test
    fun shortTapIsHeldForAtLeast40Ms() {
        val h = Harness()
        h.play(macro(MacroStep(0, MacroAction.Tap(1f, 1f, 5))))
        assertEquals(listOf(40L), h.sleeps)
        assertEquals(40L, h.events[1].time - h.events[0].time)
    }

    @Test
    fun swipeMovesAtEachPointsRelativeTimeThenLiftsAtTheLastPoint() {
        val h = Harness()
        val swipe = MacroAction.Swipe(listOf(SwipePoint(0, 10f, 10f), SwipePoint(100, 50f, 60f), SwipePoint(300, 90f, 100f)))
        h.play(macro(MacroStep(0, swipe)))
        assertEquals(listOf(100L, 200L), h.sleeps)
        assertEquals(
            listOf(
                Event("p", 0, 10_000, 10_000, 10f, 10f),
                Event("p", 2, 10_000, 10_100, 50f, 60f),
                Event("p", 2, 10_000, 10_300, 90f, 100f),
                Event("p", 1, 10_000, 10_300, 90f, 100f),
            ),
            h.events,
        )
    }

    @Test
    fun keyIsDownThenUp40MsLater() {
        val h = Harness()
        h.play(macro(MacroStep(0, MacroAction.Key(4))))
        assertEquals(
            listOf(Event("k", 0, 10_000, 10_000, 4f, 0f), Event("k", 1, 10_000, 10_040, 4f, 0f)),
            h.events,
        )
    }

    @Test
    fun coordinatesAreScaledToTheCurrentScreen() {
        val h = Harness()
        val swipe = MacroAction.Swipe(listOf(SwipePoint(0, 100f, 100f), SwipePoint(10, 200f, 400f)))
        h.play(macro(MacroStep(0, MacroAction.Tap(500f, 1000f, 50)), MacroStep(0, swipe)), w = 2000, h = 1000)
        assertEquals(1000f to 500f, h.events[0].a to h.events[0].b)
        assertEquals(200f to 50f, h.events[2].a to h.events[2].b)
        assertEquals(400f to 200f, h.events[3].a to h.events[3].b)
    }

    @Test
    fun repeatsPlayEveryStepWithTheRepeatDelayBetween() {
        val h = Harness()
        val m = macro(MacroStep(10, MacroAction.Key(1)), MacroStep(20, MacroAction.Key(2)), repeat = 3, repeatDelay = 777)
        assertEquals(PlayResult.Completed, h.play(m))
        assertEquals(12, h.events.size)
        assertEquals(listOf(0 to 0, 0 to 1, 1 to 0, 1 to 1, 2 to 0, 2 to 1), h.progress)
        // step delays and key presses, with 777 only between repeats (never before the first or after the last)
        assertEquals(listOf(10L, 40L, 20L, 40L, 777L, 10L, 40L, 20L, 40L, 777L, 10L, 40L, 20L, 40L), h.sleeps)
    }

    @Test
    fun repeatCountZeroRunsUntilCancelled() {
        val h = Harness(cancelOnSleep = 60)
        val result = h.play(macro(MacroStep(5, MacroAction.Key(1)), repeat = 0, repeatDelay = 10))
        assertEquals(PlayResult.Stopped, result)
        assertTrue(h.progress.last().first > 3)
    }

    @Test
    fun lostFocusStopsBeforeTheStepRuns() {
        val h = Harness().apply { focusLostOnCheck = 2 }
        val result = h.play(macro(MacroStep(0, MacroAction.Key(1)), MacroStep(0, MacroAction.Key(2)), MacroStep(0, MacroAction.Key(3))))
        assertEquals(PlayResult.FocusLost, result)
        assertEquals(2, h.events.size) // only the first step ran
        assertEquals(listOf(0 to 0), h.progress)
    }

    @Test
    fun cancellationDuringAHoldLiftsTheFinger() {
        val h = Harness(cancelOnSleep = 1)
        val result = h.play(macro(MacroStep(0, MacroAction.Tap(30f, 40f, 2000))))
        assertEquals(PlayResult.Stopped, result)
        assertEquals(listOf(0, 3), h.events.map { it.action })
        assertEquals(30f to 40f, h.events.last().a to h.events.last().b)
    }

    @Test
    fun cancellationBetweenStepsSendsNothingExtra() {
        val h = Harness(cancelOnSleep = 2)
        val result = h.play(macro(MacroStep(0, MacroAction.Key(1)), MacroStep(500, MacroAction.Key(2))))
        assertEquals(PlayResult.Stopped, result)
        assertEquals(2, h.events.size)
    }

    @Test
    fun refusedInjectionEndsThePlayWithTheMessage() {
        val h = Harness(refuseAfter = 1)
        val result = h.play(macro(MacroStep(0, MacroAction.Tap(1f, 1f, 100)), MacroStep(0, MacroAction.Key(2))))
        assertEquals(PlayResult.Refused("no"), result)
    }

    @Test
    fun macroWithoutStepsCompletesImmediately() {
        val h = Harness()
        assertEquals(PlayResult.Completed, h.play(macro(repeat = 0)))
        assertTrue(h.events.isEmpty())
    }
}
