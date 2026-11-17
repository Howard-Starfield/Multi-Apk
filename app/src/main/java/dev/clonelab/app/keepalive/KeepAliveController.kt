package dev.clonelab.app.keepalive

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Makes [KeepAliveService] match the stored records. Main process only.
 *
 * It never calls stopService: every sync delivers an intent through startForegroundService, the service
 * enters the foreground first (as Android requires) and then stops itself when no clone wants keep-alive.
 * Syncs run one at a time, so a start and a stop can no longer overtake each other.
 */
object KeepAliveController {
    private const val TAG = "KeepAliveController"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    /**
     * Whether [KeepAliveService] has a reason to run: a clone with keep-alive, or the floating switcher
     * being on with at least one clone, or a macro with a schedule. The service re-evaluates this on every start command and stops
     * itself when it no longer holds. [exitedByUser] (the user chose "Exit CloneLab") overrides all of it
     * until the user opens CloneLab again. Pure, so the stop logic is testable.
     */
    fun shouldRun(
        keepAliveCount: Int,
        cloneCount: Int,
        switcherEnabled: Boolean,
        scheduledMacroCount: Int,
        exitedByUser: Boolean,
    ): Boolean =
        !exitedByUser && (keepAliveCount > 0 || (switcherEnabled && cloneCount > 0) || scheduledMacroCount > 0)

    /**
     * The user opened a screen or a clone shortcut: ends an "Exit CloneLab" and, only then, runs a sync
     * (the screens that always sync do so themselves). Cheap otherwise; safe on the main thread.
     */
    fun reviveAfterExit(context: Context) {
        val app = context.applicationContext
        val prefs = KeepAlivePrefs(app)
        if (!prefs.exitedByUser) return
        prefs.clearExited()
        sync(app)
    }

    /** Fire and forget; [onDone] runs on a background thread once the service has been sent its intent. */
    fun sync(context: Context, onDone: () -> Unit = {}) {
        val app = context.applicationContext
        scope.launch {
            try {
                mutex.withLock { deliver(app) }
            } finally {
                onDone()
            }
        }
    }

    private fun deliver(app: Context) {
        // "Exit CloneLab": stay down until the user opens the app again.
        if (KeepAlivePrefs(app).exitedByUser) return
        try {
            ContextCompat.startForegroundService(app, KeepAliveService.intent(app))
        } catch (e: Exception) {
            Log.w(TAG, "Could not reach the keep-alive service", e)
        }
    }
}
