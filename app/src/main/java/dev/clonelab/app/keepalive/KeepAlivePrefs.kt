package dev.clonelab.app.keepalive

import android.content.Context

/** Small flags of the keep-alive setup: guide shown, setup steps visited, one-time migrations. */
class KeepAlivePrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("keepalive", Context.MODE_PRIVATE)

    /** @return true exactly once: the first time keep-alive is switched on. */
    fun consumeFirstTime(): Boolean {
        if (prefs.getBoolean(KEY_GUIDE_SHOWN, false)) return false
        prefs.edit().putBoolean(KEY_GUIDE_SHOWN, true).apply()
        return true
    }

    /** The vendor auto-start page was opened once; there is no way to read the real setting. */
    var autoStartVisited: Boolean
        get() = prefs.getBoolean(KEY_AUTOSTART_VISITED, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTOSTART_VISITED, value).apply()

    /** True once the POST_NOTIFICATIONS prompt was shown, to tell "never asked" from "permanently denied". */
    var notificationsAsked: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATIONS_ASKED, value).apply()

    /** @return true exactly once: the first time existing clones may be switched to keep-alive on. */
    fun consumeKeepAliveMigration(): Boolean {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return false
        prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
        return true
    }

    private companion object {
        const val KEY_GUIDE_SHOWN = "guide_shown"
        const val KEY_AUTOSTART_VISITED = "autostart_visited"
        const val KEY_NOTIFICATIONS_ASKED = "notifications_asked"
        const val KEY_MIGRATED = "keepalive_default_on_migrated"
    }
}
