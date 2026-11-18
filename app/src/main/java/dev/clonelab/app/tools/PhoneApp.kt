package dev.clonelab.app.tools

import java.util.Locale

/**
 * An app installed on the phone itself (not a clone), as the Tools screens list it.
 * [apkPaths] is the base APK first, then any split APKs; [apkBytes] is their total size.
 */
data class PhoneApp(
    val packageName: String,
    val label: String,
    val versionName: String,
    val installTime: Long,
    val apkPaths: List<String>,
    val apkBytes: Long,
    val system: Boolean,
) {
    /** Installed as several APK files, so it can only be exported as an .apks archive. */
    val isSplit: Boolean get() = apkPaths.size > 1
}

enum class AppSort { NAME, INSTALL_DATE }

/**
 * The apps matching [query] (label or package name, ignoring case); system apps only if [showSystem].
 */
fun filterApps(apps: List<PhoneApp>, query: String, showSystem: Boolean): List<PhoneApp> {
    val q = query.trim()
    return apps.filter { app ->
        (showSystem || !app.system) &&
            (q.isEmpty() || app.label.contains(q, ignoreCase = true) || app.packageName.contains(q, ignoreCase = true))
    }
}

/** By name (A to Z), or newest install first; ties broken by package name so the order is stable. */
fun sortApps(apps: List<PhoneApp>, sort: AppSort): List<PhoneApp> = when (sort) {
    AppSort.NAME -> apps.sortedWith(compareBy({ it.label.lowercase(Locale.ROOT) }, { it.packageName }))
    AppSort.INSTALL_DATE -> apps.sortedWith(compareByDescending<PhoneApp> { it.installTime }.thenBy { it.packageName })
}
