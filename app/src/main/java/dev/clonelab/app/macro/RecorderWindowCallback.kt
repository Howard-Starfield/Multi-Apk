package dev.clonelab.app.macro

import android.os.Build
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.SearchEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * A Window.Callback that behaves exactly like [delegate] and, before touch and key events are passed
 * on, reports them to [hook] when recording is on. Recording can never throw into the app.
 */
class RecorderWindowCallback(
    private val delegate: Window.Callback,
    private val hook: RecorderHook,
    private val packageName: String,
    private val userId: Int,
) : Window.Callback {

    /** Session of the gesture in progress, decided at its DOWN. Main thread only. */
    private var touchSession: String? = null

    /** Id of the finger that went down first; only that one is logged. Main thread only. */
    private var trackedPointerId = -1
    private var lastMoveWrittenAt = 0L
    private val keySessions = HashMap<Int, String>()

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        if (event != null) {
            try {
                recordTouch(event)
            } catch (e: Exception) {
                // never into the app
            }
        }
        return delegate.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event != null) {
            try {
                recordKey(event)
            } catch (e: Exception) {
                // never into the app
            }
        }
        return delegate.dispatchKeyEvent(event)
    }

    private fun recordTouch(event: MotionEvent) {
        val action = event.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                touchSession = hook.activeSession(packageName, userId)
                trackedPointerId = event.getPointerId(0)
                lastMoveWrittenAt = event.eventTime
                writeTouch(action, event, 0)
            }
            MotionEvent.ACTION_MOVE -> {
                if (touchSession != null && event.eventTime - lastMoveWrittenAt >= MOVE_INTERVAL_MS) {
                    val index = event.findPointerIndex(trackedPointerId)
                    if (index < 0) return
                    lastMoveWrittenAt = event.eventTime
                    writeTouch(action, event, index)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // The gesture is the first finger's: it ends when that finger lifts, whatever the others do.
                if (event.getPointerId(event.actionIndex) == trackedPointerId) {
                    writeTouch(MotionEvent.ACTION_UP, event, event.actionIndex)
                    touchSession = null
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                writeTouch(action, event, event.findPointerIndex(trackedPointerId).coerceAtLeast(0))
                touchSession = null
            }
            // Additional pointers going down are ignored.
        }
    }

    /**
     * Writes the position of pointer [index]. Re-checks that the session is still the one being
     * recorded, so a gesture that began before Stop ends the session instead of feeding it.
     */
    private fun writeTouch(action: Int, event: MotionEvent, index: Int) {
        val session = touchSession ?: return
        if (hook.activeSession(packageName, userId) != session) {
            touchSession = null
            return
        }
        // rawX/rawY are the first pointer's; the others are shifted by the same window offset.
        val x = event.getX(index) + (event.rawX - event.getX(0))
        val y = event.getY(index) + (event.rawY - event.getY(0))
        hook.append(session, """{"k":"t","a":$action,"t":${event.eventTime},"x":$x,"y":$y}""")
    }

    private fun recordKey(event: KeyEvent) {
        if (!RecordableKeys.isRecordable(event.keyCode)) return
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return
                val session = hook.activeSession(packageName, userId) ?: return
                keySessions[event.keyCode] = session
                writeKey(session, event)
            }
            KeyEvent.ACTION_UP -> keySessions.remove(event.keyCode)?.let {
                if (hook.activeSession(packageName, userId) == it) writeKey(it, event)
            }
        }
    }

    private fun writeKey(session: String, event: KeyEvent) {
        hook.append(session, """{"k":"k","a":${event.action},"t":${event.eventTime},"c":${event.keyCode}}""")
    }

    // Everything below is plain delegation.

    override fun dispatchKeyShortcutEvent(event: KeyEvent?): Boolean = delegate.dispatchKeyShortcutEvent(event)

    override fun dispatchTrackballEvent(event: MotionEvent?): Boolean = delegate.dispatchTrackballEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent?): Boolean = delegate.dispatchGenericMotionEvent(event)

    override fun dispatchPopulateAccessibilityEvent(event: AccessibilityEvent?): Boolean =
        delegate.dispatchPopulateAccessibilityEvent(event)

    override fun onCreatePanelView(featureId: Int): View? = delegate.onCreatePanelView(featureId)

    override fun onCreatePanelMenu(featureId: Int, menu: Menu): Boolean = delegate.onCreatePanelMenu(featureId, menu)

    override fun onPreparePanel(featureId: Int, view: View?, menu: Menu): Boolean =
        delegate.onPreparePanel(featureId, view, menu)

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean = delegate.onMenuOpened(featureId, menu)

    override fun onMenuItemSelected(featureId: Int, item: MenuItem): Boolean =
        delegate.onMenuItemSelected(featureId, item)

    override fun onWindowAttributesChanged(attrs: WindowManager.LayoutParams?) =
        delegate.onWindowAttributesChanged(attrs)

    override fun onContentChanged() = delegate.onContentChanged()

    override fun onWindowFocusChanged(hasFocus: Boolean) = delegate.onWindowFocusChanged(hasFocus)

    override fun onAttachedToWindow() = delegate.onAttachedToWindow()

    override fun onDetachedFromWindow() = delegate.onDetachedFromWindow()

    override fun onPanelClosed(featureId: Int, menu: Menu) = delegate.onPanelClosed(featureId, menu)

    override fun onSearchRequested(): Boolean = delegate.onSearchRequested()

    // API 23
    override fun onSearchRequested(searchEvent: SearchEvent?): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) delegate.onSearchRequested(searchEvent) else false

    override fun onWindowStartingActionMode(callback: ActionMode.Callback?): ActionMode? =
        delegate.onWindowStartingActionMode(callback)

    // API 23
    override fun onWindowStartingActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            delegate.onWindowStartingActionMode(callback, type)
        } else {
            delegate.onWindowStartingActionMode(callback)
        }

    override fun onActionModeStarted(mode: ActionMode?) = delegate.onActionModeStarted(mode)

    override fun onActionModeFinished(mode: ActionMode?) = delegate.onActionModeFinished(mode)

    // API 26
    override fun onPointerCaptureChanged(hasCapture: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) delegate.onPointerCaptureChanged(hasCapture)
    }

    private companion object {
        const val MOVE_INTERVAL_MS = 12L
    }
}

/**
 * The only keys a macro may record: navigation and hardware keys. Letters, digits and symbols are never
 * recorded, so text typed into a clone (passwords, messages) cannot end up in a macro or on disk.
 */
object RecordableKeys {
    private val allowed = setOf(
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ESCAPE,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SEARCH,
    )

    fun isRecordable(keyCode: Int): Boolean = keyCode in allowed
}
