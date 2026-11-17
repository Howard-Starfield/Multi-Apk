package dev.clonelab.app.switcher

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock

/**
 * Counts started activities with a short grace period, so a rotation (stop, then start a moment later)
 * or a hop between two screens never reads as "CloneLab left the screen". Pure; the clock is injected.
 */
class ForegroundCounter(private val clock: () -> Long, private val graceMs: Long = GRACE_MS) {
    private var started = 0
    private var lastStopAt = Long.MIN_VALUE

    @Synchronized
    fun onStarted() {
        started++
    }

    @Synchronized
    fun onStopped() {
        if (started > 0) started--
        lastStopAt = clock()
    }

    @Synchronized
    fun isForeground(): Boolean =
        started > 0 || (lastStopAt != Long.MIN_VALUE && clock() - lastStopAt < graceMs)

    companion object {
        const val GRACE_MS = 600L
    }
}

/**
 * Whether one of CloneLab's own screens is in front, for the floating switcher. Main process only:
 * [install] is called from the main process's Application, clone activities live in other processes
 * and never reach it. The translucent shortcut trampoline and the setup guide do not count.
 */
object CloneLabForeground {
    private const val OWN_PACKAGE_PREFIX = "dev.clonelab.app."
    private val EXCLUDED = setOf(
        "dev.clonelab.app.shortcut.ShortcutTrampolineActivity",
        "dev.clonelab.app.ui.keepalive.KeepAliveGuideActivity",
    )

    private val counter = ForegroundCounter(SystemClock::elapsedRealtime)

    /** Safe to read from any thread. */
    val isForeground: Boolean get() = counter.isForeground()

    /** Whether an activity of this class is one of the screens that show the pill. Pure. */
    fun counts(className: String): Boolean = className.startsWith(OWN_PACKAGE_PREFIX) && className !in EXCLUDED

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            // Only started/stopped matter, and each activity counts on both or neither.
            override fun onActivityStarted(activity: Activity) {
                if (counts(activity.javaClass.name)) counter.onStarted()
            }

            override fun onActivityStopped(activity: Activity) {
                if (counts(activity.javaClass.name)) counter.onStopped()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
