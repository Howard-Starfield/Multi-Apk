package dev.clonelab.app.keepalive

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import dev.clonelab.app.ServiceLocator
import dev.clonelab.app.model.CloneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service of the main process that keeps the app alive while any clone has keep-alive on,
 * and watches those clones. It holds no wake lock. The engine has its own DaemonService in :black;
 * this one is separate.
 */
class KeepAliveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchdog: Job? = null
    private val lastRestartAt = mutableMapOf<CloneId, Long>()
    private val alerted = mutableSetOf<CloneId>()
    private lateinit var notifications: KeepAliveNotifications

    override fun onCreate() {
        super.onCreate()
        notifications = KeepAliveNotifications(this)
        notifications.createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(KeepAliveNotifications.SERVICE_ID, notifications.service(0))
        } catch (e: Exception) {
            // Android 12+ refuses this when started from the background; do not crash the app.
            Log.w(TAG, "Could not enter the foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        watchdog?.cancel()
        watchdog = scope.launch {
            while (isActive) {
                try {
                    if (!tick()) break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Watchdog pass failed", e)
                }
                delay(INTERVAL_MS)
            }
            stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** One watchdog pass. @return false when no clone wants keep-alive any more. */
    private suspend fun tick(): Boolean = withContext(Dispatchers.IO) {
        val records = ServiceLocator.store(applicationContext).all().filter { it.keepAlive }
        if (records.isEmpty()) return@withContext false
        val engine = ServiceLocator.engine()
        if (!engine.awaitReady(READY_TIMEOUT_MS)) return@withContext true
        val repository = ServiceLocator.repository(applicationContext)
        val canDraw = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this@KeepAliveService)
        var runningCount = 0
        for (record in records) {
            val id = record.id
            if (!engine.isInstalled(id)) continue
            if (engine.isRunning(id)) {
                runningCount++
                alerted.remove(id)
                notifications.cancelStopped(record)
                continue
            }
            val now = SystemClock.elapsedRealtime()
            val manuallyStopped = ManualStops.contains(id)
            if (manuallyStopped) {
                alerted.remove(id)
                notifications.cancelStopped(record)
            }
            val action = WatchdogPolicy.decide(
                now, lastRestartAt[id], running = false, canDrawOverlays = canDraw, manuallyStopped = manuallyStopped,
            )
            when (action) {
                WatchdogAction.None -> Unit
                WatchdogAction.Restart -> {
                    lastRestartAt[id] = now
                    repository.launch(id).onFailure { Log.w(TAG, "Restart of $id failed: ${it.message}") }
                }
                WatchdogAction.Notify -> if (alerted.add(id)) notifications.stopped(record)
            }
        }
        notifications.updateService(runningCount)
        true
    }

    companion object {
        private const val TAG = "KeepAliveService"
        private const val INTERVAL_MS = 30_000L
        private const val READY_TIMEOUT_MS = 5_000L

        fun intent(context: Context) = Intent(context, KeepAliveService::class.java)
    }
}
