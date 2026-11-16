package dev.clonelab.app.keepalive

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import dev.clonelab.app.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Starts or stops [KeepAliveService] to match the stored records. Main process only. */
object KeepAliveController {
    private const val TAG = "KeepAliveController"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Fire and forget; [onDone] runs on a background thread once the service was started or stopped. */
    fun sync(context: Context, onDone: () -> Unit = {}) {
        val app = context.applicationContext
        scope.launch {
            try {
                apply(app)
            } finally {
                onDone()
            }
        }
    }

    private fun apply(app: Context) {
        val wanted = try {
            ServiceLocator.store(app).all().any { it.keepAlive }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read clones", e)
            return
        }
        try {
            if (wanted) {
                ContextCompat.startForegroundService(app, KeepAliveService.intent(app))
            } else {
                app.stopService(KeepAliveService.intent(app))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not change the keep-alive service state", e)
        }
    }
}
