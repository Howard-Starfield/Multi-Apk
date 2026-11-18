package dev.clonelab.app.keepalive

enum class WatchdogAction { None, Notify }

/**
 * Decides what the watchdog does about one keep-alive clone. Pure, so it is unit-tested.
 *
 * It never relaunches a clone itself: a launch brings the clone to the front over whatever the user is
 * doing, and a clone that crashes on start (seen with packed apps) would be relaunched again and again.
 * A stopped clone gets a notification the user can tap to open it.
 */
object WatchdogPolicy {
    /**
     * @param seenRunning the watchdog has seen this clone running since the service started. A clone the
     *   user has not opened is not reported as stopped.
     * @param manuallyStopped the user stopped the clone on purpose and has not launched it since: never notify
     */
    fun decide(running: Boolean, manuallyStopped: Boolean, seenRunning: Boolean): WatchdogAction = when {
        running -> WatchdogAction.None
        !seenRunning -> WatchdogAction.None
        manuallyStopped -> WatchdogAction.None
        else -> WatchdogAction.Notify
    }
}
