package dev.clonelab.app.switcher

import android.content.Context

/** User setting and remembered placement of the floating switcher. */
class SwitcherPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("switcher", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var edge: Int
        get() = prefs.getInt(KEY_EDGE, SwitcherGeometry.EDGE_RIGHT)
        set(value) = prefs.edit().putInt(KEY_EDGE, value).apply()

    /** Top of the pill in pixels; clamped to the screen whenever it is applied. */
    var y: Int
        get() = prefs.getInt(KEY_Y, DEFAULT_Y)
        set(value) = prefs.edit().putInt(KEY_Y, value).apply()

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_EDGE = "edge"
        const val KEY_Y = "y"
        const val DEFAULT_Y = 400
    }
}
