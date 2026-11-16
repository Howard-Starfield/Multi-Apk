package dev.clonelab.app.macro.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.clonelab.app.R
import dev.clonelab.app.macro.Macro

/**
 * The macro list card: a dimmed full-screen scrim with a centred card. Non-focusable, so the clone
 * keeps its window focus; it closes through the scrim, the close button or by starting something.
 * Main thread only.
 */
internal class MacroPanel(
    private val host: OverlayHost,
    private val listener: Listener,
) {
    interface Listener {
        fun onPlay(macro: Macro)
        fun onEdit(macro: Macro)
        fun onDelete(macro: Macro)
        fun onRecord()
        fun onClose()
    }

    private var root: View? = null
    private var list: ViewGroup? = null
    private var empty: View? = null
    private var card: View? = null
    private var scroll: MaxHeightScrollView? = null

    val isShown get() = root != null

    fun show(title: String): Boolean {
        if (root != null) return true
        val v = host.inflate(R.layout.overlay_macro_panel)
        v.findViewById<TextView>(R.id.title).text = title
        v.findViewById<View>(R.id.close).setOnClickListener { listener.onClose() }
        v.findViewById<View>(R.id.record).setOnClickListener { listener.onRecord() }
        v.setOnClickListener { listener.onClose() } // the scrim; the card consumes its own taps
        val lp = host.scrimParams(focusable = false)
        card = v.findViewById(R.id.card)
        scroll = v.findViewById(R.id.scroll)
        applySizing()
        if (!host.add(v, lp)) {
            card = null
            scroll = null
            return false
        }
        root = v
        list = v.findViewById(R.id.list)
        empty = v.findViewById(R.id.empty)
        return true
    }

    /** Fills the list. Called once the store has been read off the main thread. */
    fun setMacros(macros: List<Macro>) {
        val container = list ?: return
        for (i in container.childCount - 1 downTo 0) {
            val child = container.getChildAt(i)
            if (child !== empty) container.removeViewAt(i)
        }
        empty?.visibility = if (macros.isEmpty()) View.VISIBLE else View.GONE
        for (macro in macros) container.addView(row(container, macro))
    }

    private fun row(parent: ViewGroup, macro: Macro): View {
        val row = host.inflate(R.layout.item_macro_row)
        row.findViewById<TextView>(R.id.name).text = macro.name
        row.findViewById<TextView>(R.id.summary).text = MacroText.summary(host.themed, macro)
        val normal = row.findViewById<View>(R.id.normal)
        val confirm = row.findViewById<View>(R.id.confirm)
        row.findViewById<View>(R.id.play).setOnClickListener { listener.onPlay(macro) }
        row.findViewById<View>(R.id.edit).setOnClickListener { listener.onEdit(macro) }
        row.findViewById<View>(R.id.delete).setOnClickListener {
            normal.visibility = View.GONE
            confirm.visibility = View.VISIBLE
        }
        row.findViewById<View>(R.id.no).setOnClickListener {
            confirm.visibility = View.GONE
            normal.visibility = View.VISIBLE
        }
        row.findViewById<View>(R.id.yes).setOnClickListener { listener.onDelete(macro) }
        row.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return row
    }

    /** Screen size changed: re-measure the card. The window itself is full-screen and follows. */
    fun onConfigurationChanged() {
        applySizing()
        root?.requestLayout()
    }

    private fun applySizing() {
        val c = card ?: return
        val s = scroll ?: return
        host.sizeCard(c, s, widthDp = 300, heightFraction = 0.6f, chromeDp = 124)
    }

    fun hide() {
        val v = root ?: return
        root = null
        list = null
        empty = null
        card = null
        scroll = null
        host.remove(v)
    }
}
