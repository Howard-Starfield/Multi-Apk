package dev.clonelab.app.ui.detail

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.GridLayout
import androidx.appcompat.app.AlertDialog
import dev.clonelab.app.R

/** Lets the user pick between the app icon, a coloured text icon and a photo. */
class EditIconDialog(
    private val context: Context,
    private val onApp: () -> Unit,
    private val onText: (color: Int) -> Unit,
    private val onImage: () -> Unit,
) {
    fun show() {
        val options = arrayOf(
            context.getString(R.string.icon_option_app),
            context.getString(R.string.icon_option_text),
            context.getString(R.string.icon_option_image),
        )
        AlertDialog.Builder(context)
            .setTitle(R.string.detail_edit_icon)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> onApp()
                    1 -> showSwatches()
                    else -> onImage()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSwatches() {
        val colors = context.resources.obtainTypedArray(R.array.icon_swatches).use { array ->
            IntArray(array.length()) { array.getColor(it, 0) }
        }
        val grid = GridLayout(context).apply {
            columnCount = COLUMNS
            val pad = dp(PADDING_DP)
            setPadding(pad, pad, pad, pad)
        }
        lateinit var dialog: AlertDialog
        colors.forEach { color ->
            val swatch = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
                layoutParams = GridLayout.LayoutParams().apply {
                    width = dp(SWATCH_DP)
                    height = dp(SWATCH_DP)
                    setMargins(dp(MARGIN_DP), dp(MARGIN_DP), dp(MARGIN_DP), dp(MARGIN_DP))
                }
                setOnClickListener {
                    dialog.dismiss()
                    onText(color)
                }
            }
            grid.addView(swatch)
        }
        dialog = AlertDialog.Builder(context)
            .setTitle(R.string.icon_pick_color)
            .setView(grid)
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val COLUMNS = 4
        const val SWATCH_DP = 48
        const val MARGIN_DP = 8
        const val PADDING_DP = 16
    }
}
