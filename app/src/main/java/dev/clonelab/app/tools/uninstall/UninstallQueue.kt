package dev.clonelab.app.tools.uninstall

import kotlinx.coroutines.delay

/**
 * Apps being uninstalled one at a time, each through the system's own confirmation dialog.
 * Immutable; every step returns a new value. Cancelling stops before the next app, not the current one.
 */
data class UninstallQueue(
    val packages: List<String>,
    val uninstalled: List<String> = emptyList(),
    val declined: List<String> = emptyList(),
    val cancelled: Boolean = false,
) {
    private val handled: Int get() = uninstalled.size + declined.size

    val total: Int get() = packages.size

    /** 1-based number of the app being handled now, for "Uninstalling 2 of 5". */
    val position: Int get() = minOf(handled + 1, total)

    /** The app to uninstall next, or null when the queue is done or cancelled. */
    val current: String? get() = if (cancelled) null else packages.getOrNull(handled)

    val isFinished: Boolean get() = current == null

    fun recordResult(removed: Boolean): UninstallQueue {
        val pkg = packages.getOrNull(handled) ?: return this
        return if (removed) copy(uninstalled = uninstalled + pkg) else copy(declined = declined + pkg)
    }

    fun cancel(): UninstallQueue = copy(cancelled = true)

    /** The packages not uninstalled, in queue order. */
    fun remaining(): List<String> = packages - uninstalled.toSet()
}

/**
 * Whether [packageName] is really gone after the uninstall dialog returned. The dialog's result is not
 * trusted on its own: some phones report Cancel after a successful uninstall, and the package manager can
 * lag a moment behind. If the user [accepted], it is checked again [attempts] times, [delayMs] apart.
 */
suspend fun confirmRemoved(
    packageName: String,
    accepted: Boolean,
    isInstalled: (String) -> Boolean,
    attempts: Int = 3,
    delayMs: Long = 300,
): Boolean {
    if (!isInstalled(packageName)) return true
    if (!accepted) return false
    repeat(attempts) {
        delay(delayMs)
        if (!isInstalled(packageName)) return true
    }
    return false
}
