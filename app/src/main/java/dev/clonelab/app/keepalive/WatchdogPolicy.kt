package dev.clonelab.app.keepalive

enum class WatchdogAction { None, Restart, Notify }

/** Decides what the watchdog does about one keep-alive clone. Pure, so it is unit-tested. */
object WatchdogPolicy {
    const val MIN_RESTART_INTERVAL_MS = 2 * 60 * 1000L

    /**
     * @param lastRestartAt time of the last automatic restart on the same clock as [now], or null for never
     * @param canDrawOverlays whether a background restart can start an activity (Android 10+ blocks it otherwise)
     * @param seenRunning the watchdog has seen this clone running since the service started. A clone the
     *   user has not opened is never launched on their behalf: launching brings it to the screen.
     * @param manuallyStopped the user stopped the clone on purpose and has not launched it since: never restart or notify
     */
    fun decide(
        now: Long,
        lastRestartAt: Long?,
        running: Boolean,
        canDrawOverlays: Boolean,
        manuallyStopped: Boolean,
        seenRunning: Boolean = true,
    ): WatchdogAction = when {
        running -> WatchdogAction.None
        !seenRunning -> WatchdogAction.None
        manuallyStopped -> WatchdogAction.None
        !canDrawOverlays -> WatchdogAction.Notify
        lastRestartAt != null && now - lastRestartAt < MIN_RESTART_INTERVAL_MS -> WatchdogAction.None
        else -> WatchdogAction.Restart
    }
}
