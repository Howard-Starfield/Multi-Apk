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

    /** Whether running clones are listed in notifications with Stop buttons (default on). */
    var showRunningClones: Boolean
        get() = prefs.getBoolean(KEY_SHOW_RUNNING, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_RUNNING, value).apply()

    /**
     * The user chose "Exit CloneLab". While set, nothing starts [KeepAliveService] by itself (boot, sync,
     * schedules, watchdog). Written with commit(): the process is killed right after it is set.
     */
    var exitedByUser: Boolean
        get() = prefs.getBoolean(KEY_EXITED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_EXITED, value).commit()
        }

    /** The user opened CloneLab (or a clone shortcut) again. Cheap when the flag is not set; safe on the main thread. */
    fun clearExited() {
        if (exitedByUser) prefs.edit().putBoolean(KEY_EXITED, false).apply()
    }

    private companion object {
        const val KEY_EXITED = "exited_by_user"
        const val KEY_SHOW_RUNNING = "show_running_clones"
        const val KEY_GUIDE_SHOWN = "guide_shown"
        const val KEY_AUTOSTART_VISITED = "autostart_visited"
        const val KEY_NOTIFICATIONS_ASKED = "notifications_asked"
        const val KEY_MIGRATED = "keepalive_default_on_migrated"
    }
}
