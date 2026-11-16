package dev.clonelab.app.macro

import org.json.JSONObject
import kotlin.math.hypot

/**
 * Pure mirror of one line of the session file. For touch lines [action] is the MotionEvent action
 * (0 down, 1 up, 2 move, 3 cancel) and x/y are set; for key lines it is the KeyEvent action
 * (0 down, 1 up) and [keyCode] is set. [t] is the event time in ms.
 */
data class RawEvent(
    val kind: Kind,
    val action: Int,
    val t: Long,
    val x: Float = 0f,
    val y: Float = 0f,
    val keyCode: Int = 0,
) {
    enum class Kind { TOUCH, KEY }
}

/** @return null for anything that is not a well-formed event line. */
fun parseRawLine(line: String): RawEvent? = try {
    val o = JSONObject(line)
    when (o.getString("k")) {
        "t" -> RawEvent(
            RawEvent.Kind.TOUCH, o.getInt("a"), o.getLong("t"),
            x = o.getDouble("x").toFloat(), y = o.getDouble("y").toFloat(),
        )
        "k" -> RawEvent(RawEvent.Kind.KEY, o.getInt("a"), o.getLong("t"), keyCode = o.getInt("c"))
        else -> null
    }
} catch (e: Exception) {
    null
}

/** Turns raw touch and key events into macro steps. A touch sequence that ends in a cancel is dropped. */
object GestureClassifier {
    private const val ACTION_DOWN = 0
    private const val ACTION_UP = 1
    private const val ACTION_MOVE = 2
    private const val ACTION_CANCEL = 3
    const val MAX_SWIPE_POINTS = 64

    /** A step with its start and end time, before delays are computed. */
    private class Timed(val start: Long, val end: Long, val action: MacroAction)

    private class Sample(val t: Long, val x: Float, val y: Float)

    fun classify(lines: List<RawEvent>, tapSlopPx: Float): List<MacroStep> {
        val events = lines.sortedBy { it.t } // stable: equal times keep their written order
        val timed = ArrayList<Timed>()
        var gesture: MutableList<Sample>? = null
        val keyDowns = LinkedHashMap<Int, Long>()

        fun finishGesture() {
            gesture?.let { timed += gestureStep(it, tapSlopPx) }
            gesture = null
        }

        for (e in events) {
            when (e.kind) {
                RawEvent.Kind.TOUCH -> when (e.action) {
                    ACTION_DOWN -> {
                        finishGesture() // a previous gesture that never got its UP
                        gesture = mutableListOf(Sample(e.t, e.x, e.y))
                    }
                    ACTION_MOVE -> gesture?.add(Sample(e.t, e.x, e.y))
                    ACTION_UP -> {
                        gesture?.add(Sample(e.t, e.x, e.y))
                        finishGesture()
                    }
                    // The app or system took the touch away (e.g. a scroll container stole it): the
                    // gesture never completed, so replaying it would do something else. Its time stays
                    // in the gap before the next step, because delays run from the previous kept step.
                    ACTION_CANCEL -> gesture = null
                }
                RawEvent.Kind.KEY -> when (e.action) {
                    ACTION_DOWN -> if (e.keyCode !in keyDowns) keyDowns[e.keyCode] = e.t // repeats are ignored
                    ACTION_UP -> keyDowns.remove(e.keyCode)?.let {
                        timed += Timed(it, e.t, MacroAction.Key(e.keyCode))
                    }
                }
            }
        }
        finishGesture()
        // An unmatched DOWN still yields a step.
        keyDowns.forEach { (code, t) -> timed += Timed(t, t, MacroAction.Key(code)) }

        var previousEnd = 0L
        return timed.sortedBy { it.start }.mapIndexed { index, step ->
            val delay = if (index == 0) 0L else (step.start - previousEnd).coerceAtLeast(0L)
            previousEnd = step.end
            MacroStep(delay, step.action)
        }
    }

    private fun gestureStep(samples: List<Sample>, tapSlopPx: Float): Timed {
        val first = samples.first()
        val last = samples.last()
        val moved = samples.maxOf { hypot(it.x - first.x, it.y - first.y) }
        if (moved <= tapSlopPx) {
            return Timed(first.t, last.t, MacroAction.Tap(first.x, first.y, last.t - first.t))
        }
        val points = samples.filterIndexed { i, s ->
            // Drop consecutive duplicate positions, but never the final sample: it carries the end time.
            i == 0 || i == samples.lastIndex || s.x != samples[i - 1].x || s.y != samples[i - 1].y
        }
        val sampled = if (points.size <= MAX_SWIPE_POINTS) points else {
            List(MAX_SWIPE_POINTS) { i ->
                points[Math.round(i * (points.size - 1).toDouble() / (MAX_SWIPE_POINTS - 1)).toInt()]
            }
        }
        return Timed(first.t, last.t, MacroAction.Swipe(sampled.map { SwipePoint(it.t - first.t, it.x, it.y) }))
    }
}
