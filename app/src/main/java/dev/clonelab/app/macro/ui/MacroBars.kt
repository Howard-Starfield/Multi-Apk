package dev.clonelab.app.macro.ui

import android.animation.ObjectAnimator
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.TextView
import dev.clonelab.app.R

/**
 * The bar shown while a recording is armed (3-2-1 countdown) or running (pulsing dot, mm:ss).
 * Its window is separate from the clone's, so touches on it are neither seen by the clone nor recorded.
 * Main thread only.
 */
internal class RecordingBar(
    private val host: OverlayHost,
    position: BarPosition,
    private val onStop: () -> Unit,
    private val onCancel: () -> Unit,
) {
    private val window = BarWindow(host, R.layout.overlay_macro_recording_bar, position)
    private val handler = Handler(Looper.getMainLooper())
    private var startedAt = 0L
    private var pulse: ObjectAnimator? = null
    private var label: TextView? = null
    private var stop: View? = null

    private val tick = object : Runnable {
        override fun run() {
            val l = label ?: return
            l.text = MacroFormat.formatElapsed(System.currentTimeMillis() - startedAt)
            handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        // The window manager dropped the window: the pulse and the timer belong to a dead view. Stop
        // them and forget the views, so the next show binds a new view and starts both again.
        window.onLost = ::releaseViews
    }

    val isShown get() = window.isShown

    private fun releaseViews() {
        handler.removeCallbacks(tick)
        stopPulse()
        label = null
        stop = null
        startedAt = 0
    }

    private fun ensure(): Boolean {
        if (window.isShown) return true
        val v = window.show() ?: return false
        label = v.findViewById(R.id.label)
        stop = v.findViewById<View>(R.id.stop).also { it.setOnClickListener { onStop() } }
        v.findViewById<View>(R.id.cancel).setOnClickListener { onCancel() }
        return true
    }

    /** Shows "3", "2", "1": a cancellable bar without the Stop button. */
    fun showCountdown(seconds: Int) {
        if (!ensure()) return
        handler.removeCallbacks(tick)
        stopPulse()
        stop?.visibility = View.GONE
        label?.text = seconds.toString()
        label?.contentDescription = host.themed.getString(R.string.macro_countdown_label, seconds)
        window.reposition()
    }

    /** [startedAt] is the runner's wall-clock start of the recording. */
    fun showRecording(startedAt: Long) {
        if (!ensure()) return
        val changed = this.startedAt != startedAt || stop?.visibility != View.VISIBLE
        this.startedAt = startedAt
        stop?.visibility = View.VISIBLE
        label?.contentDescription = null
        if (changed) {
            handler.removeCallbacks(tick)
            tick.run()
            window.reposition()
        }
        startPulse()
    }

    private fun startPulse() {
        if (pulse != null) return
        val dot = window.view?.findViewById<View>(R.id.dot) ?: return
        pulse = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f).apply {
            duration = PULSE_MS
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        window.view?.findViewById<View>(R.id.dot)?.alpha = 1f
    }

    fun disableButtons() {
        stop?.isEnabled = false
    }

    fun reposition() = window.reposition()

    fun hide() {
        releaseViews()
        window.hide()
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val PULSE_MS = 700L
    }
}

/**
 * The bar shown while a macro plays: macro name, "2/10" repeat counter and a Stop button.
 *
 * It is a touchable window exactly as large as its content (not a full-screen not-touchable window with
 * a hole), so a recorded tap that lands under the bar hits the bar instead of the clone. The default
 * spot is top-centre and the bar can be dragged out of the way.
 */
internal class PlayingBar(
    private val host: OverlayHost,
    position: BarPosition,
    private val onStop: () -> Unit,
) {
    private val window = BarWindow(host, R.layout.overlay_macro_playing_bar, position)
    private var name: TextView? = null
    private var counter: TextView? = null

    init {
        window.onLost = {
            name = null
            counter = null
        }
    }

    val isShown get() = window.isShown

    fun show(macroName: String, repeatIndex: Int, repeatCount: Int) {
        if (!window.isShown) {
            val v = window.show() ?: return
            name = v.findViewById(R.id.name)
            counter = v.findViewById(R.id.counter)
            v.findViewById<View>(R.id.stop).setOnClickListener { onStop() }
        }
        val counterText = MacroFormat.runCounter(repeatIndex, repeatCount) { host.themed.getString(R.string.macro_playing_run, it) }
        var changed = false
        if (name?.text?.toString() != macroName) {
            name?.text = macroName
            changed = true
        }
        if (counter?.text?.toString() != counterText) {
            counter?.text = counterText
            changed = true
        }
        if (changed) window.reposition()
    }

    fun reposition() = window.reposition()

    fun hide() {
        name = null
        counter = null
        window.hide()
    }
}
