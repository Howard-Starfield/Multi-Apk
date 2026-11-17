package dev.clonelab.app.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.clonelab.app.ServiceLocator
import dev.clonelab.app.model.CloneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The Stop / Stop all buttons of the running-clone notifications. Not exported: only our own explicit
 * PendingIntents reach it. A stop goes through [dev.clonelab.app.data.CloneRepository.stop], which marks
 * a manual stop, so the watchdog leaves the clone alone until the user opens it again.
 */
class RunningActionsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val packageName = intent.getStringExtra(EXTRA_PACKAGE)
        if (packageName.isNullOrBlank() || (action != ACTION_STOP && action != ACTION_STOP_ALL)) return
        val userId = intent.getIntExtra(EXTRA_USER_ID, 0)
        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                handle(app, action, packageName, userId)
            } catch (e: Exception) {
                Log.w(TAG, "Stop from notification failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(app: Context, action: String, packageName: String, userId: Int) {
        val engine = ServiceLocator.engine()
        engine.awaitReady(READY_TIMEOUT_MS)
        val ids = if (action == ACTION_STOP_ALL) {
            // Every clone of the app that is running right now, not the ones the notification last listed.
            ServiceLocator.store(app).all().map { it.id }.filter { it.packageName == packageName && engine.isRunning(it) }
        } else {
            listOf(CloneId(packageName, userId))
        }
        val repository = ServiceLocator.repository(app)
        ids.forEach { repository.stop(it) }
        RunningNotifications.onStopped(app, ids, packageName.takeIf { action == ACTION_STOP_ALL })
    }

    companion object {
        private const val TAG = "RunningActions"
        private const val READY_TIMEOUT_MS = 5_000L
        const val ACTION_STOP = "dev.clonelab.app.action.STOP_CLONE"
        const val ACTION_STOP_ALL = "dev.clonelab.app.action.STOP_ALL_CLONES"
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_USER_ID = "userId"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun intent(context: Context, action: String, packageName: String, userId: Int) =
            Intent(context, RunningActionsReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_PACKAGE, packageName)
                .putExtra(EXTRA_USER_ID, userId)
    }
}
