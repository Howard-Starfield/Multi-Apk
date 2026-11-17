package dev.clonelab.app.ui.selection

import android.util.TypedValue
import android.view.View
import dev.clonelab.app.R
import dev.clonelab.app.databinding.ItemCloneBinding

/** A selected tile is tinted and shows a check mark where the settings button would be. */
fun ItemCloneBinding.showSelected(selected: Boolean) {
    selectedCheck.visibility = if (selected) View.VISIBLE else View.GONE
    if (selected) {
        root.setBackgroundResource(R.drawable.bg_tile_selected)
    } else {
        val value = TypedValue()
        root.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        root.setBackgroundResource(value.resourceId)
    }
}
