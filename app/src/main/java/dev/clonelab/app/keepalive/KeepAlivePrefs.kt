package dev.clonelab.app.keepalive

import android.content.Context

/** Remembers that the keep-alive guide was already shown once. */
class KeepAlivePrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("keepalive", Context.MODE_PRIVATE)

    /** @return true exactly once: the first time keep-alive is switched on. */
    fun consumeFirstTime(): Boolean {
        if (prefs.getBoolean(KEY_GUIDE_SHOWN, false)) return false
        prefs.edit().putBoolean(KEY_GUIDE_SHOWN, true).apply()
        return true
    }

    private companion object {
        const val KEY_GUIDE_SHOWN = "guide_shown"
    }
}
