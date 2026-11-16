package dev.clonelab.app.macro

import android.app.Instrumentation
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/** Android refused to deliver an injected event. */
class InjectionRefused(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Where [MacroPlayer] sends its events. Times are SystemClock.uptimeMillis values. */
interface InputInjector {
    /** @throws InjectionRefused */
    fun pointer(action: Int, downTime: Long, eventTime: Long, x: Float, y: Float)

    /** @throws InjectionRefused */
    fun key(action: Int, downTime: Long, eventTime: Long, keyCode: Int)
}

/**
 * Injects through [Instrumentation.sendPointerSync] / [Instrumentation.sendKeySync]. Android lets an
 * app inject input into windows owned by its own uid, and every clone window belongs to our uid
 * (clones run in stub processes of this very app), so no INJECT_EVENTS permission is needed. These
 * calls throw on the main thread: use them from a background thread only.
 */
class InstrumentationInjector(private val instrumentation: Instrumentation = Instrumentation()) : InputInjector {

    override fun pointer(action: Int, downTime: Long, eventTime: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            instrumentation.sendPointerSync(event)
        } catch (e: SecurityException) {
            throw InjectionRefused(e.message ?: "Input injection was refused", e)
        } finally {
            event.recycle()
        }
    }

    override fun key(action: Int, downTime: Long, eventTime: Long, keyCode: Int) {
        val event = KeyEvent(downTime, eventTime, action, keyCode, 0)
        try {
            instrumentation.sendKeySync(event)
        } catch (e: SecurityException) {
            throw InjectionRefused(e.message ?: "Input injection was refused", e)
        }
    }
}
