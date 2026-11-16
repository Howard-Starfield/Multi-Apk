package dev.clonelab.app.macro.ui

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.clonelab.app.R
import dev.clonelab.app.macro.MacroSchedule

/**
 * The editor card. It works on an immutable [MacroDraft]; every change is reported through
 * [Listener.onChanged] so the owner can keep the draft while the window is hidden. The window is
 * focusable (name and millisecond fields need the keyboard) and BACK acts like Discard.
 * Main thread only.
 */
internal class MacroEditor(
    private val host: OverlayHost,
    initial: MacroDraft,
    /** The stored version when editing an existing macro; used to tell whether anything changed. */
    private val baseline: MacroDraft?,
    private val cloneNumber: Int,
    private val listener: Listener,
) {
    interface Listener {
        fun onChanged(draft: MacroDraft)
        fun onSave(draft: MacroDraft)
        fun onTest(draft: MacroDraft)
        fun onDiscard()
    }

    private var draft = initial
    private var lastEvery = (initial.schedule as? MacroSchedule.EveryMinutes)?.minutes ?: DEFAULT_EVERY
    private var lastDaily = (initial.schedule as? MacroSchedule.DailyAt) ?: MacroSchedule.DailyAt(9, 0)

    private var root: View? = null
    private var card: View? = null
    private var scroll: MaxHeightScrollView? = null
    private lateinit var steps: LinearLayout
    private lateinit var repeat: StepperView
    private lateinit var repeatDelay: StepperView
    private lateinit var interval: StepperView
    private lateinit var daily: View
    private lateinit var hour: StepperView
    private lateinit var minute: StepperView
    private lateinit var note: TextView
    private lateinit var discard: TextView
    private lateinit var segments: List<Pair<TextView, (MacroSchedule) -> Boolean>>

    val isShown get() = root != null

    fun show(): Boolean {
        if (root != null) return true
        val v = host.inflate(R.layout.overlay_macro_editor)
        bind(v)
        val lp = host.scrimParams(focusable = true)
        if (!host.add(v, lp)) return false
        root = v
        // The first layout is not final: the keyboard resizes the window, so re-fit the card each time.
        v.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) applySizing(bottom - top)
        }
        v.requestFocus() // keep the keyboard closed until a field is tapped
        return true
    }

    fun hide() {
        val v = root ?: return
        root = null
        card = null
        scroll = null
        host.remove(v)
    }

    fun onConfigurationChanged() {
        applySizing(root?.height?.takeIf { it > 0 } ?: host.screenSize().y)
        root?.requestLayout()
    }

    private fun applySizing(available: Int) {
        val c = card ?: return
        val s = scroll ?: return
        host.sizeCard(c, s, widthDp = 320, heightFraction = 0.75f, chromeDp = 128, availableHeight = available)
    }

    private fun bind(v: View) {
        card = v.findViewById(R.id.card)
        scroll = v.findViewById(R.id.scroll)
        applySizing(host.screenSize().y)
        (v as OverlayRootLayout).onBack = { listener.onDiscard() }
        v.findViewById<TextView>(R.id.title).setText(
            if (baseline == null) R.string.macro_editor_title_new else R.string.macro_editor_title_edit,
        )

        val name = v.findViewById<EditText>(R.id.name)
        name.setText(draft.name)
        name.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                update { it.withName(s?.toString().orEmpty()) }
            }
        })

        steps = v.findViewById(R.id.steps)
        repeat = v.findViewById(R.id.repeat)
        repeatDelay = v.findViewById(R.id.repeatDelay)
        interval = v.findViewById(R.id.interval)
        daily = v.findViewById(R.id.daily)
        hour = v.findViewById(R.id.hour)
        minute = v.findViewById(R.id.minute)
        note = v.findViewById(R.id.scheduleNote)
        discard = v.findViewById(R.id.discard)

        repeat.onStep = { up ->
            update { it.withRepeat(MacroFormat.stepRepeat(it.repeatCount, up)) }
            showRepeat()
        }
        repeatDelay.onStep = { up ->
            update { it.withRepeatDelay(if (up) MacroFormat.delayUp(it.repeatDelayMs) else MacroFormat.delayDown(it.repeatDelayMs)) }
            showRepeat()
        }
        repeatDelay.onTyped = { ms ->
            update { it.withRepeatDelay(ms) }
            showRepeat()
        }
        interval.onStep = { up ->
            val minutes = MacroFormat.stepInterval(lastEvery, up)
            lastEvery = minutes
            update { it.withSchedule(MacroSchedule.EveryMinutes(minutes)) }
            showSchedule()
        }
        hour.onStep = { up ->
            lastDaily = MacroSchedule.DailyAt(MacroFormat.stepHour(lastDaily.hour, up), lastDaily.minute)
            update { it.withSchedule(lastDaily) }
            showSchedule()
        }
        minute.onStep = { up ->
            lastDaily = MacroSchedule.DailyAt(lastDaily.hour, MacroFormat.stepMinute(lastDaily.minute, up))
            update { it.withSchedule(lastDaily) }
            showSchedule()
        }

        segments = listOf(
            v.findViewById<TextView>(R.id.segOff) to { s: MacroSchedule -> s == MacroSchedule.Off },
            v.findViewById<TextView>(R.id.segEvery) to { s: MacroSchedule -> s is MacroSchedule.EveryMinutes },
            v.findViewById<TextView>(R.id.segDaily) to { s: MacroSchedule -> s is MacroSchedule.DailyAt },
        )
        v.findViewById<View>(R.id.segOff).setOnClickListener {
            update { it.withSchedule(MacroSchedule.Off) }
            showSchedule()
        }
        v.findViewById<View>(R.id.segEvery).setOnClickListener {
            update { it.withSchedule(MacroSchedule.EveryMinutes(lastEvery)) }
            showSchedule()
        }
        v.findViewById<View>(R.id.segDaily).setOnClickListener {
            update { it.withSchedule(lastDaily) }
            showSchedule()
        }

        v.findViewById<View>(R.id.save).setOnClickListener { listener.onSave(draft) }
        v.findViewById<View>(R.id.test).setOnClickListener { listener.onTest(draft) }
        discard.setOnClickListener { listener.onDiscard() }

        rebuildSteps()
        showRepeat()
        showSchedule()
        showDiscardLabel()
    }

    private fun update(change: (MacroDraft) -> MacroDraft) {
        draft = change(draft)
        listener.onChanged(draft)
        showDiscardLabel()
    }

    private fun showDiscardLabel() {
        val unchanged = baseline != null && draft == baseline
        discard.setText(if (unchanged) R.string.macro_close else R.string.macro_discard)
    }

    private fun showRepeat() {
        repeat.setValue(
            if (draft.repeatCount == 0) {
                host.themed.getString(R.string.macro_repeat_forever)
            } else {
                host.themed.getString(R.string.macro_repeat_value, draft.repeatCount)
            },
        )
        repeatDelay.setValue(MacroFormat.formatSeconds(draft.repeatDelayMs), draft.repeatDelayMs)
    }

    private fun showSchedule() {
        val schedule = draft.schedule
        for ((view, matches) in segments) {
            view.setBackgroundResource(
                if (matches(schedule)) R.drawable.bg_macro_btn_primary else R.drawable.bg_macro_btn_secondary,
            )
        }
        val every = schedule as? MacroSchedule.EveryMinutes
        interval.visibility = if (every != null) View.VISIBLE else View.GONE
        if (every != null) interval.setValue(host.themed.getString(R.string.macro_minutes_value, every.minutes))
        val dailyAt = schedule as? MacroSchedule.DailyAt
        daily.visibility = if (dailyAt != null) View.VISIBLE else View.GONE
        if (dailyAt != null) {
            hour.setValue("%02d".format(dailyAt.hour))
            minute.setValue("%02d".format(dailyAt.minute))
        }
        note.visibility = if (schedule == MacroSchedule.Off) View.GONE else View.VISIBLE
        note.text = host.themed.getString(R.string.macro_schedule_note, cloneNumber)
    }

    private fun rebuildSteps() {
        steps.removeAllViews()
        draft.steps.forEachIndexed { index, step ->
            val row = host.inflate(R.layout.item_macro_step)
            row.findViewById<TextView>(R.id.index).text = (index + 1).toString()
            row.findViewById<TextView>(R.id.description).text =
                MacroText.step(host.themed, MacroFormat.describe(step, MacroText::keyName))
            val wait = row.findViewById<StepperView>(R.id.wait)
            fun showWait() {
                val ms = draft.steps.getOrNull(index)?.delayBeforeMs ?: return
                wait.setValue(MacroFormat.formatSeconds(ms), ms)
            }
            showWait()
            wait.onStep = { up ->
                val ms = draft.steps.getOrNull(index)?.delayBeforeMs ?: 0
                update { it.withStepDelay(index, if (up) MacroFormat.delayUp(ms) else MacroFormat.delayDown(ms)) }
                showWait()
            }
            wait.onTyped = { ms ->
                update { it.withStepDelay(index, ms) }
                showWait()
            }
            row.findViewById<View>(R.id.remove).setOnClickListener {
                update { it.withoutStep(index) }
                rebuildSteps()
            }
            steps.addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private companion object {
        const val DEFAULT_EVERY = 30
    }
}
