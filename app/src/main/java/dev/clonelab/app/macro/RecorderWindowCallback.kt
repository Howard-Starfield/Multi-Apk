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
                lastMoveWrittenAt = event.eventTime
                writeTouch(action, event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (touchSession != null && event.eventTime - lastMoveWrittenAt >= MOVE_INTERVAL_MS) {
                    lastMoveWrittenAt = event.eventTime
                    writeTouch(action, event)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                writeTouch(action, event)
                touchSession = null
            }
            // Additional pointers are ignored.
        }
    }

    private fun writeTouch(action: Int, event: MotionEvent) {
        val session = touchSession ?: return
        hook.append(session, """{"k":"t","a":$action,"t":${event.eventTime},"x":${event.rawX},"y":${event.rawY}}""")
    }

    private fun recordKey(event: KeyEvent) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return
                val session = hook.activeSession(packageName, userId) ?: return
                keySessions[event.keyCode] = session
                writeKey(session, event)
            }
            KeyEvent.ACTION_UP -> keySessions.remove(event.keyCode)?.let { writeKey(it, event) }
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
