package dev.clonelab.app.macro.ui

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import dev.clonelab.app.R
import dev.clonelab.app.macro.Macro
import dev.clonelab.app.macro.MacroSchedule
import kotlin.math.hypot

/**
 * Shared WindowManager plumbing of the macro windows, with the switcher's defensive handling: every
 * call is on the main thread, every add/update/remove is guarded, and a refused window just reports
 * false. Never hands out a window it has not been asked to track: callers keep their own view.
 */
internal class OverlayHost(context: Context) {
    val themed = ContextThemeWrapper(context, R.style.Theme_CloneLab)
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val density = context.resources.displayMetrics.density
    val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    fun dp(value: Int) = (value * density + 0.5f).toInt()

    fun inflate(layout: Int): View = LayoutInflater.from(themed).inflate(layout, null)

    fun screenSize(): Point {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return Point(metrics.widthPixels, metrics.heightPixels)
    }

    private fun type() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    /**
     * Full-screen scrim window. A focusable one (editor) takes keys and the keyboard and resizes
     * around it; a non-focusable one (panel) leaves the clone's focus alone.
     */
    fun scrimParams(focusable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!focusable) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type(),
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            if (focusable) {
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            }
        }
    }

    /** Small bar: never focusable, touches outside it reach the clone. */
    fun barParams(): WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        type(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    fun add(view: View, lp: WindowManager.LayoutParams): Boolean {
        try {
            windowManager.addView(view, lp)
            return true
        } catch (e: WindowManager.BadTokenException) {
            Log.w(TAG, "Overlay window refused", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Overlay permission missing", e)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Overlay window invalid", e)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Overlay could not be added", e)
        }
        return false
    }

    /** @return false when the window is gone. */
    fun update(view: View, lp: WindowManager.LayoutParams): Boolean {
        try {
            windowManager.updateViewLayout(view, lp)
            return true
        } catch (e: IllegalArgumentException) {
            // The window is gone.
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not update an overlay", e)
            return true
        }
        return false
    }

    fun remove(view: View) {
        try {
            windowManager.removeViewImmediate(view)
        } catch (e: IllegalArgumentException) {
            // Not attached any more.
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not remove an overlay", e)
        }
    }

    /** Card width and the scroll area's height cap for a window of [availableHeight] pixels. */
    fun sizeCard(
        card: View,
        scroll: MaxHeightScrollView,
        widthDp: Int,
        heightFraction: Float,
        chromeDp: Int,
        availableHeight: Int = screenSize().y,
    ) {
        val screen = screenSize()
        val width = minOf(dp(widthDp), screen.x - dp(24))
        val lp = card.layoutParams
        if (lp.width != width) {
            lp.width = width
            card.layoutParams = lp
        }
        val cardMax = minOf((screen.y * heightFraction).toInt(), availableHeight - dp(24))
        scroll.maxHeightPx = (cardMax - dp(chromeDp)).coerceAtLeast(dp(96))
    }

    private companion object {
        const val TAG = "MacroOverlays"
    }
}

/** Where the bars sit: horizontal centre and top, shared so a bar keeps its place when the next one replaces it. */
internal class BarPosition {
    var centerX: Int? = null
    var y: Int? = null
}

/**
 * One small draggable bar window. Dragging works from any part of the bar that is not a button.
 * The window is as small as its content, so everything outside it still reaches the clone.
 */
internal class BarWindow(
    private val host: OverlayHost,
    private val layout: Int,
    private val position: BarPosition,
) {
    var view: View? = null
        private set
    private var params: WindowManager.LayoutParams? = null

    val isShown get() = view != null

    fun show(): View? {
        view?.let { return it }
        val v = host.inflate(layout)
        v.setOnTouchListener(DragListener())
        val lp = host.barParams()
        if (!host.add(v, lp)) return null
        view = v
        params = lp
        reposition()
        return v
    }

    fun hide() {
        val v = view ?: return
        view = null
        params = null
        v.animate().cancel()
        host.remove(v)
    }

    /** Places the bar at the remembered spot (top-centre by default), clamped to the current screen. */
    fun reposition() {
        val v = view ?: return
        val lp = params ?: return
        val screen = host.screenSize()
        v.measure(
            View.MeasureSpec.makeMeasureSpec(screen.x, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(screen.y, View.MeasureSpec.AT_MOST),
        )
        val w = v.measuredWidth
        val h = v.measuredHeight
        val centerX = position.centerX ?: (screen.x / 2)
        lp.x = (centerX - w / 2).coerceIn(0, (screen.x - w).coerceAtLeast(0))
        lp.y = (position.y ?: host.dp(DEFAULT_TOP_DP)).coerceIn(0, (screen.y - h).coerceAtLeast(0))
        if (!host.update(v, lp) && v === view) {
            view = null
            params = null
        }
    }

    private inner class DragListener : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val lp = params ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && hypot(dx, dy) > host.touchSlop) dragging = true
                    if (dragging) {
                        val screen = host.screenSize()
                        val w = v.width.takeIf { it > 0 } ?: v.measuredWidth
                        val h = v.height.takeIf { it > 0 } ?: v.measuredHeight
                        lp.x = (startX + dx).toInt().coerceIn(0, (screen.x - w).coerceAtLeast(0))
                        lp.y = (startY + dy).toInt().coerceIn(0, (screen.y - h).coerceAtLeast(0))
                        position.centerX = lp.x + w / 2
                        position.y = lp.y
                        host.update(v, lp)
                    }
                }
            }
            return true
        }
    }

    private companion object {
        const val DEFAULT_TOP_DP = 40
    }
}

/** Text the macro windows build from string resources. */
internal object MacroText {
    private const val SEPARATOR = " · "

    /** "12 steps · repeats 3× · every 30 min". */
    fun summary(context: Context, macro: Macro): String {
        val r = context.resources
        val parts = mutableListOf(r.getQuantityString(R.plurals.macro_summary_steps, macro.steps.size, macro.steps.size))
        parts += if (macro.repeatCount == 0) {
            r.getString(R.string.macro_summary_repeat_forever)
        } else {
            r.getString(R.string.macro_summary_repeat, macro.repeatCount)
        }
        when (val s = macro.schedule) {
            MacroSchedule.Off -> Unit
            is MacroSchedule.EveryMinutes -> parts += r.getString(R.string.macro_summary_every, s.minutes)
            is MacroSchedule.DailyAt -> parts += r.getString(R.string.macro_summary_daily, MacroFormat.formatClock(s.hour, s.minute))
        }
        return parts.joinToString(SEPARATOR)
    }

    fun step(context: Context, text: StepText): String {
        val id = when (text.kind) {
            StepKind.TAP -> R.string.macro_step_tap
            StepKind.LONG_PRESS -> R.string.macro_step_long_press
            StepKind.SWIPE -> R.string.macro_step_swipe
            StepKind.KEY -> R.string.macro_step_key
        }
        return context.getString(id, *text.values.toTypedArray())
    }

    fun keyName(code: Int): String = android.view.KeyEvent.keyCodeToString(code)
}
