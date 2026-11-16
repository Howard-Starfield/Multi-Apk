package dev.clonelab.app.macro

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay

sealed interface PlayResult {
    data object Completed : PlayResult
    data object Stopped : PlayResult
    data object FocusLost : PlayResult
    /** Android or the run itself refused; [detail] is the system's own text, if it gave any. */
    data class Refused(val detail: String?) : PlayResult

    /** The target clone could not be started before the run. */
    data class LaunchFailed(val cause: Throwable) : PlayResult
}

/** Source of event times (SystemClock.uptimeMillis in production). */
fun interface UptimeClock {
    fun uptimeMs(): Long
}

/** Waits without blocking a thread; cancellable. */
fun interface Sleeper {
    suspend fun sleep(ms: Long)
}

object SystemUptimeClock : UptimeClock {
    override fun uptimeMs(): Long = android.os.SystemClock.uptimeMillis()
}

object CoroutineSleeper : Sleeper {
    override suspend fun sleep(ms: Long) = delay(ms)
}

/**
 * Replays a macro through an [InputInjector]. Cancelling the coroutine is "Stop". The injector's calls
 * block, so run [play] on a dedicated background thread, never the main one.
 */
class MacroPlayer(
    private val injector: InputInjector,
    private val clock: UptimeClock = SystemUptimeClock,
    private val sleeper: Sleeper = CoroutineSleeper,
    private val isStillFocused: () -> Boolean,
) {
    /** Where the finger is while a gesture is open, so a cancel can lift it. */
    private var openTouch: OpenTouch? = null

    private class OpenTouch(val downTime: Long, var x: Float, var y: Float)

    suspend fun play(
        macro: Macro,
        currentWidth: Int,
        currentHeight: Int,
        onProgress: (repeatIndex: Int, stepIndex: Int) -> Unit,
    ): PlayResult {
        val scaleX = if (macro.screenWidth > 0) currentWidth.toFloat() / macro.screenWidth else 1f
        val scaleY = if (macro.screenHeight > 0) currentHeight.toFloat() / macro.screenHeight else 1f
        try {
            if (macro.steps.isEmpty()) return PlayResult.Completed
            var repeat = 0
            while (macro.repeatCount == 0 || repeat < macro.repeatCount) {
                if (repeat > 0) wait(macro.repeatDelayMs)
                for ((index, step) in macro.steps.withIndex()) {
                    wait(step.delayBeforeMs)
                    if (!isStillFocused()) return PlayResult.FocusLost
                    onProgress(repeat, index)
                    perform(step.action, scaleX, scaleY)
                }
                repeat++
            }
            return PlayResult.Completed
        } catch (e: CancellationException) {
            liftFinger()
            return PlayResult.Stopped
        } catch (e: InjectionRefused) {
            liftFinger()
            return PlayResult.Refused(e.message)
        }
    }

    private suspend fun wait(ms: Long) {
        if (ms > 0) sleeper.sleep(ms)
    }

    private suspend fun perform(action: MacroAction, scaleX: Float, scaleY: Float) {
        when (action) {
            is MacroAction.Tap -> {
                val x = action.x * scaleX
                val y = action.y * scaleY
                val downTime = touchDown(x, y)
                wait(maxOf(action.holdMs, MIN_PRESS_MS))
                touchEnd(MotionEventActions.UP, downTime, x, y)
            }
            is MacroAction.Swipe -> {
                val first = action.points.first()
                val downTime = touchDown(first.x * scaleX, first.y * scaleY)
                var previousT = first.tMs
                for (point in action.points.drop(1)) {
                    wait(point.tMs - previousT)
                    previousT = point.tMs
                    val x = point.x * scaleX
                    val y = point.y * scaleY
                    injector.pointer(MotionEventActions.MOVE, downTime, clock.uptimeMs(), x, y)
                    openTouch?.let { it.x = x; it.y = y }
                }
                val last = action.points.last()
                touchEnd(MotionEventActions.UP, downTime, last.x * scaleX, last.y * scaleY)
            }
            is MacroAction.Key -> {
                val downTime = clock.uptimeMs()
                injector.key(KeyActions.DOWN, downTime, downTime, action.keyCode)
                wait(MIN_PRESS_MS)
                injector.key(KeyActions.UP, downTime, clock.uptimeMs(), action.keyCode)
            }
        }
    }

    private fun touchDown(x: Float, y: Float): Long {
        val downTime = clock.uptimeMs()
        openTouch = OpenTouch(downTime, x, y)
        injector.pointer(MotionEventActions.DOWN, downTime, downTime, x, y)
        return downTime
    }

    private fun touchEnd(action: Int, downTime: Long, x: Float, y: Float) {
        injector.pointer(action, downTime, clock.uptimeMs(), x, y)
        openTouch = null
    }

    /** After a stop or a refusal: never leave a finger held down in the app. */
    private suspend fun liftFinger() {
        val touch = openTouch ?: return
        openTouch = null
        kotlinx.coroutines.withContext(NonCancellable) {
            try {
                injector.pointer(MotionEventActions.CANCEL, touch.downTime, clock.uptimeMs(), touch.x, touch.y)
            } catch (e: InjectionRefused) {
                // nothing more can be done
            }
        }
    }

    /** MotionEvent / KeyEvent action values, spelled out so this class stays free of Android types. */
    private object MotionEventActions {
        const val DOWN = 0
        const val UP = 1
        const val MOVE = 2
        const val CANCEL = 3
    }

    private object KeyActions {
        const val DOWN = 0
        const val UP = 1
    }

    companion object {
        const val MIN_PRESS_MS = 40L
    }
}
