package dev.clonelab.app.switcher

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.clonelab.app.R
import dev.clonelab.app.macro.ui.MacroBusy
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
    private val onMacros: () -> Unit = {},
) {
    private val themed = ContextThemeWrapper(context, R.style.Theme_CloneLab)
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val handler = Handler(Looper.getMainLooper())
    private val autoCollapse = Runnable { setCollapsed(true) }
    private val adapter = SwitcherAdapter { id ->
        onSelect(id)
        scheduleCollapse(COLLAPSE_AFTER_SELECT_MS)
    }

    /** The pill always starts as the slim tab and folds back to it when left alone. */
    private var collapsed = true

    /** True while the expand or collapse animation runs; taps are ignored until it ends. */
    private var animating = false

    private var root: View? = null
    private var pill: View? = null
    private var list: RecyclerView? = null
    private var tab: View? = null
    private var grip: View? = null
    private var macrosDot: View? = null
    private var macroBusy = MacroBusy.NONE
    private var params: WindowManager.LayoutParams? = null

    val isShown: Boolean get() = root != null

    /** Shows or refreshes the pill. An empty list hides it. */
    fun show(items: List<SwitcherItem>, icons: Map<CloneId, Drawable>) {
        if (items.isEmpty()) {
            hide()
            return
        }
        if (root == null && !attach()) return
        adapter.update(items, icons)
        list?.let { rv ->
            val lp = rv.layoutParams
            lp.height = SwitcherGeometry.visibleRows(items.size) * dp(ROW_DP)
            rv.layoutParams = lp
        }
        applyCollapsed()
        applyMacroBusy()
        reposition()
    }

    /** Red dot while a recording runs, green while a macro plays. */
    fun setMacroBusy(value: MacroBusy) {
        macroBusy = value
        applyMacroBusy()
    }

    private fun applyMacroBusy() {
        val dot = macrosDot ?: return
        when (macroBusy) {
            MacroBusy.NONE -> dot.visibility = View.GONE
            MacroBusy.RECORDING -> {
                dot.setBackgroundResource(R.drawable.bg_macro_dot_red)
                dot.visibility = View.VISIBLE
            }
            MacroBusy.PLAYING -> {
                dot.setBackgroundResource(R.drawable.bg_running_dot)
                dot.visibility = View.VISIBLE
            }
        }
    }

    fun hide() {
        handler.removeCallbacks(autoCollapse)
        collapsed = true
        animating = false
        pill?.animate()?.cancel()
        tab?.animate()?.cancel()
        val view = root ?: return
        root = null
        grip = null
        macrosDot = null
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
        // Scrolling or touching the list counts as activity and postpones the auto-collapse.
        rv.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (!collapsed) scheduleCollapse(COLLAPSE_IDLE_MS)
                return false
            }
        })
        val drag = DragListener()
        view.findViewById<View>(R.id.handle).setOnTouchListener(drag)
        view.findViewById<View>(R.id.tab).setOnTouchListener(drag)
        // The Macros button folds the pill away and hands over to the macro panel.
        view.findViewById<View>(R.id.macros).setOnClickListener {
            setCollapsed(true)
            onMacros()
        }
        // A touch anywhere else on the screen folds the pill away. The touch itself still reaches
        // the clone underneath: this window only watches it.
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE && !collapsed) setCollapsed(true)
            false
        }

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
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
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
        grip = view.findViewById(R.id.grip)
        macrosDot = view.findViewById(R.id.macrosDot)
        params = lp
        return true
    }

    private fun applyCollapsed() {
        pill?.visibility = if (collapsed) View.GONE else View.VISIBLE
        tab?.visibility = if (collapsed) View.VISIBLE else View.GONE
        grip?.let {
            val left = prefs.edge == SwitcherGeometry.EDGE_LEFT
            (it.layoutParams as FrameLayout.LayoutParams).let { lp ->
                val gravity = (if (left) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL
                if (lp.gravity != gravity) {
                    lp.gravity = gravity
                    it.layoutParams = lp
                }
            }
            it.background = LayerDrawable(
                arrayOf(
                    tabBackground(prefs.edge),
                    GripDotsDrawable(dotRadius = 1.5f * density, gap = 3f * density, color = GRIP_COLOR),
                ),
            )
        }
    }

    /** Expands or collapses with a short scale-and-fade that grows out of the screen edge. */
    private fun setCollapsed(value: Boolean) {
        handler.removeCallbacks(autoCollapse)
        if (root == null) {
            collapsed = value
            return
        }
        if (animating || value == collapsed) {
            if (!collapsed) scheduleCollapse(COLLAPSE_IDLE_MS)
            return
        }
        val pillView = pill
        val tabView = tab
        if (pillView == null || tabView == null) {
            collapsed = value
            applyCollapsed()
            reposition()
            return
        }
        animating = true
        if (value) {
            pillView.animate().cancel()
            setPivotToEdge(pillView)
            pillView.animate()
                .alpha(0f).scaleX(SHRUNK_SCALE).scaleY(SHRUNK_SCALE)
                .setDuration(COLLAPSE_MS).setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    collapsed = true
                    animating = false
                    pillView.alpha = 1f
                    pillView.scaleX = 1f
                    pillView.scaleY = 1f
                    if (root == null) return@withEndAction
                    applyCollapsed()
                    reposition()
                    tabView.alpha = 0f
                    tabView.animate().alpha(1f).setDuration(TAB_FADE_MS).start()
                }
                .start()
        } else {
            collapsed = false
            applyCollapsed()
            reposition()
            pillView.alpha = 0f
            pillView.scaleX = SHRUNK_SCALE
            pillView.scaleY = SHRUNK_SCALE
            // The pill has just become visible; its size is known after the next layout pass.
            pillView.post {
                setPivotToEdge(pillView)
                pillView.animate()
                    .alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(EXPAND_MS).setInterpolator(DecelerateInterpolator())
                    .withEndAction { animating = false }
                    .start()
            }
            scheduleCollapse(COLLAPSE_IDLE_MS)
        }
    }

    private fun setPivotToEdge(view: View) {
        view.pivotX = if (prefs.edge == SwitcherGeometry.EDGE_LEFT) 0f else view.width.toFloat()
        view.pivotY = dp(TAB_CENTER_DP).toFloat().coerceAtMost(view.height.toFloat())
    }

    private fun scheduleCollapse(delayMs: Long) {
        handler.removeCallbacks(autoCollapse)
        handler.postDelayed(autoCollapse, delayMs)
    }

    /** Rounded on the side that faces the screen interior. */
    private fun tabBackground(edge: Int): Drawable {
        val r = dp(6).toFloat()
        return GradientDrawable().apply {
            setColor(TAB_COLOR)
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
                    handler.removeCallbacks(autoCollapse)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && hypot(dx, dy) > touchSlop * TAP_SLOP_FACTOR) dragging = true
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
                MotionEvent.ACTION_CANCEL -> if (dragging) snap(view, lp) else if (!collapsed) scheduleCollapse(COLLAPSE_IDLE_MS)
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
            if (!collapsed) scheduleCollapse(COLLAPSE_IDLE_MS)
        }

        private fun toggle() = setCollapsed(!collapsed)
    }

    private companion object {
        const val TAG = "SwitcherOverlay"
        const val COLUMNS = 2
        const val ROW_DP = 54
        const val TAB_COLOR = 0x661C1B1F // slim and see-through: it sits on top of the clone
        const val GRIP_COLOR = 0xCCFFFFFF.toInt()
        const val EXPAND_MS = 200L
        const val COLLAPSE_MS = 150L
        const val TAB_FADE_MS = 120L
        const val SHRUNK_SCALE = 0.6f
        const val TAB_CENTER_DP = 40

        /** A finger on a small edge handle wobbles; allow more movement before a tap becomes a drag. */
        const val TAP_SLOP_FACTOR = 2f
        const val COLLAPSE_IDLE_MS = 4_000L
        const val COLLAPSE_AFTER_SELECT_MS = 400L
    }
}
