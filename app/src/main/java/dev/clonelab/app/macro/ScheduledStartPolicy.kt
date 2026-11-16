package dev.clonelab.app.macro

/** Decides whether a due scheduled macro may start right now. Pure. */
object ScheduledStartPolicy {
    /**
     * A scheduled run needs a phone that is in use: screen on and not locked, because injected taps on a
     * dark or locked screen would land on the lock screen. It also needs a way to get the clone to the
     * front: the overlay permission, or the clone already being in front. When this says no the macro
     * stays due and is tried again at the next tick.
     */
    fun mayStart(interactive: Boolean, keyguardLocked: Boolean, canDrawOverlays: Boolean, targetFocused: Boolean): Boolean =
        interactive && !keyguardLocked && (canDrawOverlays || targetFocused)
}
