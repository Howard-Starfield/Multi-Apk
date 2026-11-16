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
     * itself when it no longer holds. Pure, so the stop logic is testable.
     */
    fun shouldRun(keepAliveCount: Int, cloneCount: Int, switcherEnabled: Boolean, scheduledMacroCount: Int): Boolean =
        keepAliveCount > 0 || (switcherEnabled && cloneCount > 0) || scheduledMacroCount > 0

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
        try {
            ContextCompat.startForegroundService(app, KeepAliveService.intent(app))
        } catch (e: Exception) {
            Log.w(TAG, "Could not reach the keep-alive service", e)
        }
    }
}
