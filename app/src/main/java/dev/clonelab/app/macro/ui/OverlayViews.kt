package dev.clonelab.app.macro.ui

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.clonelab.app.R

/**
 * Root of a floating card window: a scrim that reports BACK. Only a focusable window ever receives
 * the key, so [onBack] is used by the editor; the panel closes through its scrim and close button.
 */
class OverlayRootLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {

    var onBack: (() -> Unit)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBack?.invoke()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

/** A ScrollView that never grows beyond [maxHeightPx], so the buttons around it stay visible. */
class MaxHeightScrollView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    ScrollView(context, attrs) {

    var maxHeightPx: Int = Int.MAX_VALUE
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        if (mode == MeasureSpec.EXACTLY) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val offered = if (mode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(heightMeasureSpec)
        val cap = minOf(offered, maxHeightPx).coerceAtLeast(0)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
    }
}

/**
 * "- value +" control. The value can optionally be tapped to type a number of milliseconds
 * ([onTyped] set); the typed text is committed on Done or when the field loses focus.
 */
class StepperView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {

    var onStep: ((up: Boolean) -> Unit)? = null
    var onTyped: ((Long) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val value = TextView(context)
    private val edit = EditText(context)
    private var typedValueMs = 0L
    private var ending = false

    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        addView(button(R.drawable.ic_remove, R.string.macro_decrease, up = false))
        value.apply {
            gravity = android.view.Gravity.CENTER
            minWidth = dp(64)
            setTextColor(WHITE)
            textSize = 13f
            setBackgroundResource(R.drawable.bg_macro_field)
            setPadding(dp(6), 0, dp(6), 0)
            setOnClickListener { if (onTyped != null) beginEdit() }
        }
        addView(value, LayoutParams(LayoutParams.WRAP_CONTENT, dp(32)).apply { setMargins(dp(6), 0, dp(6), 0) })
        edit.apply {
            visibility = GONE
            gravity = android.view.Gravity.CENTER
            minWidth = dp(64)
            setTextColor(WHITE)
            textSize = 13f
            hint = context.getString(R.string.macro_ms_hint)
            setHintTextColor(DIM)
            setBackgroundResource(R.drawable.bg_macro_field)
            setPadding(dp(6), 0, dp(6), 0)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    endEdit(commit = true)
                    true
                } else {
                    false
                }
            }
            setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) endEdit(commit = true) }
        }
        addView(edit, LayoutParams(LayoutParams.WRAP_CONTENT, dp(32)).apply { setMargins(dp(6), 0, dp(6), 0) })
        addView(button(R.drawable.ic_plus, R.string.macro_increase, up = true))
    }

    /** [ms] is what the field offers for editing when the value is typeable. */
    fun setValue(text: String, ms: Long = 0) {
        value.text = text
        typedValueMs = ms
    }

    private fun button(icon: Int, description: Int, up: Boolean): View = ImageView(context).apply {
        setImageResource(icon)
        setBackgroundResource(R.drawable.bg_macro_btn_secondary)
        contentDescription = context.getString(description)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setOnClickListener {
            endEdit(commit = true)
            onStep?.invoke(up)
        }
        layoutParams = LayoutParams(dp(32), dp(32))
    }

    private fun beginEdit() {
        edit.setText(typedValueMs.toString())
        edit.selectAll()
        value.visibility = GONE
        edit.visibility = VISIBLE
        edit.requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun endEdit(commit: Boolean) {
        if (ending || edit.visibility != VISIBLE) return
        ending = true
        try {
            val parsed = MacroFormat.parseMillis(edit.text.toString())
            edit.visibility = GONE
            value.visibility = VISIBLE
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(windowToken, 0)
            if (commit && parsed != null) onTyped?.invoke(parsed)
        } finally {
            ending = false
        }
    }

    private fun dp(v: Int) = (v * density + 0.5f).toInt()

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val DIM = 0xB3FFFFFF.toInt()
    }
}
