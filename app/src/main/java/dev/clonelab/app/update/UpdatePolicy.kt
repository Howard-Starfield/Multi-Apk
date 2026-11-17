package dev.clonelab.app.update

/** When to check on its own, when to show the banner, which APK is acceptable; pure so it can be tested. */
object UpdatePolicy {
    const val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    /** At most once per interval; a clock that went backwards counts as due. */
    fun shouldAutoCheck(lastCheck: Long, now: Long, interval: Long = AUTO_CHECK_INTERVAL_MS): Boolean =
        lastCheck <= 0L || now < lastCheck || now - lastCheck >= interval

    /** Dismissing hides the banner for that version only. */
    fun showBanner(release: ReleaseInfo?, dismissedTag: String?): Boolean =
        release != null && release.tag != dismissedTag

    /** An APK is acceptable only when it is our own package and a higher build. */
    fun isNewerBuild(archivePackage: String?, archiveCode: Long, ownPackage: String, ownCode: Long): Boolean =
        archivePackage == ownPackage && archiveCode > ownCode
}
