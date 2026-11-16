package dev.clonelab.app.macro

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager

/** The real display size in pixels, as the switcher overlay measures it. */
object ScreenMetrics {
    fun realSize(context: Context): Pair<Int, Int> {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }
}
