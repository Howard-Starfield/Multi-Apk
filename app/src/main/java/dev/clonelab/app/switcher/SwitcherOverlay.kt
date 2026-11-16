package dev.clonelab.app.switcher

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
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
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.clonelab.app.R
import dev.clonelab.app.model.CloneId
import kotlin.math.hypot

/**
 * The floating clone switcher window. Owned by KeepAliveService; every method must be called on the
 * main thread. Window calls are guarded so a lost permission or a dying window never crashes the app.
 * The window never takes focus, so the clone underneath keeps its keyboard and input.
 */
class SwitcherOverlay(
    context: Context,
    private val prefs: SwitcherPrefs,
    onSelect: (CloneId) -> Unit,
) {
    private val themed = ContextThemeWrapper(context, R.style.Theme_CloneLab)
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val adapter = SwitcherAdapter(onSelect)

    private var root: View? = null
    private var pill: View? = null
    private var list: RecyclerView? = null
    private var tab: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var focusedNumber = 0

    val isShown: Boolean get() = root != null

    /** Shows or refreshes the pill. An empty list hides it. */
    fun show(items: List<SwitcherItem>, icons: Map<CloneId, Drawable>) {
        if (items.isEmpty()) {
            hide()
            return
        }
        if (root == null && !attach()) return
        focusedNumber = items.firstOrNull { it.state == SwitcherState.FOCUSED }?.number ?: items.first().number
        adapter.update(items, icons)
        list?.let { rv ->
            val lp = rv.layoutParams
            lp.height = SwitcherGeometry.visibleRows(items.size) * dp(ROW_DP)
            rv.layoutParams = lp
        }
        applyCollapsed()
        reposition()
    }

    fun hide() {
        val view = root ?: return
        root = null
        pill = null
        list = null
        tab = null
        params = null
        try {
            windowManager.removeViewImmediate(view)
        } catch (e: IllegalArgumentException) {
            // Not attached any more.
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not remove the switcher", e)
        }
    }

    /** Screen size or rotation changed: keep the pill on screen. */
    fun onConfigurationChanged() {
        if (root != null) reposition()
    }

    private fun attach(): Boolean {
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_switcher, null)
        val rv = view.findViewById<RecyclerView>(R.id.list)
        rv.layoutManager = GridLayoutManager(themed, COLUMNS)
        rv.adapter = adapter
        rv.itemAnimator = null
        val drag = DragListener()
        view.findViewById<View>(R.id.handle).setOnTouchListener(drag)
        view.findViewById<View>(R.id.tab).setOnTouchListener(drag)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0
        lp.y = prefs.y
        try {
            windowManager.addView(view, lp)
        } catch (e: WindowManager.BadTokenException) {
            Log.w(TAG, "Overlay window refused", e)
            return false
        } catch (e: SecurityException) {
            Log.w(TAG, "Overlay permission missing", e)
            return false
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Overlay window invalid", e)
            return false
        } catch (e: RuntimeException) {
            Log.w(TAG, "Overlay could not be added", e)
            return false
        }
        root = view
        pill = view.findViewById(R.id.pill)
        list = rv
        tab = view.findViewById(R.id.tab)
        params = lp
        return true
    }

    private fun applyCollapsed() {
        val collapsed = prefs.collapsed
        pill?.visibility = if (collapsed) View.GONE else View.VISIBLE
        tab?.let {
            it.visibility = if (collapsed) View.VISIBLE else View.GONE
            it.text = focusedNumber.toString()
            it.background = tabBackground(prefs.edge)
        }
    }

    /** Rounded on the side that faces the screen interior. */
    private fun tabBackground(edge: Int): Drawable {
        val r = dp(14).toFloat()
        return GradientDrawable().apply {
            setColor(ContextCompat.getColor(themed, R.color.switcher_surface))
            setStroke(dp(1), ContextCompat.getColor(themed, R.color.switcher_outline))
            cornerRadii = if (edge == SwitcherGeometry.EDGE_LEFT) {
                floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            } else {
                floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            }
        }
    }

    /** Places the window at the saved edge and y, clamped to the current screen. */
    private fun reposition() {
        val view = root ?: return
        val lp = params ?: return
        val screen = screenSize()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(screen.x, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(screen.y, View.MeasureSpec.AT_MOST),
        )
        lp.x = SwitcherGeometry.xFor(prefs.edge, view.measuredWidth, screen.x)
        lp.y = SwitcherGeometry.clampY(prefs.y, view.measuredHeight, screen.y)
        update(view, lp)
    }

    private fun update(view: View, lp: WindowManager.LayoutParams) {
        try {
            windowManager.updateViewLayout(view, lp)
        } catch (e: IllegalArgumentException) {
            // The window is gone; the next show() re-adds it.
            if (view === root) hide()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not move the switcher", e)
        }
    }

    private fun screenSize(): android.graphics.Point {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return android.graphics.Point(metrics.widthPixels, metrics.heightPixels)
    }

    private fun dp(value: Int) = (value * density + 0.5f).toInt()

    /** Drag moves the pill and snaps it to the nearest edge on release; a plain tap collapses or expands it. */
    private inner class DragListener : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val view = root ?: return false
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
                    if (!dragging && hypot(dx, dy) > touchSlop) dragging = true
                    if (dragging) {
                        val screen = screenSize()
                        val width = view.width.takeIf { it > 0 } ?: view.measuredWidth
                        val height = view.height.takeIf { it > 0 } ?: view.measuredHeight
                        lp.x = (startX + dx).toInt().coerceIn(0, (screen.x - width).coerceAtLeast(0))
                        lp.y = SwitcherGeometry.clampY((startY + dy).toInt(), height, screen.y)
                        update(view, lp)
                    }
                }
                MotionEvent.ACTION_UP -> if (dragging) snap(view, lp) else toggle()
                MotionEvent.ACTION_CANCEL -> if (dragging) snap(view, lp)
            }
            return true
        }

        private fun snap(view: View, lp: WindowManager.LayoutParams) {
            dragging = false
            val screen = screenSize()
            val width = view.width.takeIf { it > 0 } ?: view.measuredWidth
            prefs.edge = SwitcherGeometry.nearestEdge(lp.x + width / 2f, screen.x)
            prefs.y = lp.y
            applyCollapsed() // the collapsed tab is rounded towards the interior of the screen
            reposition()
        }

        private fun toggle() {
            prefs.collapsed = !prefs.collapsed
            applyCollapsed()
            reposition()
        }
    }

    private companion object {
        const val TAG = "SwitcherOverlay"
        const val COLUMNS = 2
        const val ROW_DP = 40
    }
}
