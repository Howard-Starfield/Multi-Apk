package dev.clonelab.app.update

import android.content.Context

/** When the last successful check ran, the release it found, and which version's banner was dismissed. */
class UpdatePrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE)

    var lastCheck: Long
        get() = prefs.getLong(KEY_LAST_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CHECK, value).apply()

    /** The tag whose banner the user dismissed. */
    var dismissedTag: String?
        get() = prefs.getString(KEY_DISMISSED, null)
        set(value) = prefs.edit().putString(KEY_DISMISSED, value).apply()

    /** The raw GitHub answer of the last check that found a newer release, so the banner survives restarts. */
    var releaseJson: String?
        get() = prefs.getString(KEY_RELEASE, null)
        set(value) = prefs.edit().putString(KEY_RELEASE, value).apply()

    private companion object {
        const val KEY_LAST_CHECK = "last_check"
        const val KEY_DISMISSED = "dismissed_tag"
        const val KEY_RELEASE = "release_json"
    }
}
