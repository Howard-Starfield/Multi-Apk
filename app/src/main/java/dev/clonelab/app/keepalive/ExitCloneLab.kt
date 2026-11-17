package dev.clonelab.app.keepalive

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.clonelab.app.ServiceLocator
import kotlinx.coroutines.runBlocking

/** One of our own processes, as reported by [ActivityManager.getRunningAppProcesses]. */
data class OwnProcess(val name: String, val pid: Int)

/**
 * "Exit CloneLab": stops everything and ends every process of the app, so nothing runs or drains battery
 * until the user opens CloneLab (or a clone shortcut) again. Triggered through [ExitReceiver] by the
 * "Exit" button of the service notification and by the Home menu. Every step is guarded on its own: a
 * failure is logged and the next step still runs.
 */
object ExitCloneLab {
    private const val TAG = "ExitCloneLab"
    const val ACTION_EXIT = "dev.clonelab.app.action.EXIT_CLONELAB"

    /** Engine's sticky foreground service in :black. A string, so only the engine files import the engine. */
    private const val ENGINE_DAEMON = "top.niunaijun.blackbox.core.system.DaemonService"
    private const val ENGINE_READY_TIMEOUT_MS = 3_000L
    private const val SERVICE_STOP_WAIT_MS = 2_500L
    private const val POLL_MS = 50L

    private fun intent(context: Context): Intent =
        Intent(context, ExitReceiver::class.java).setAction(ACTION_EXIT).setData(Uri.parse("clonelab://exit"))

    /** For the notification button. Explicit and immutable; the data URI keeps it distinct from the Stop buttons. */
    fun pendingIntent(context: Context): PendingIntent {
        val immutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, 0, intent(context), PendingIntent.FLAG_UPDATE_CURRENT or immutable)
    }

    /** For in-app callers (Home menu). */
    fun request(context: Context) {
        context.applicationContext.sendBroadcast(intent(context.applicationContext))
    }

    /**
     * The pids to kill, in order: clone/stub processes first, the engine server (:black) after them, this
     * process last. Only processes whose name starts with [packageName] are considered.
     */
    fun killOrder(processes: List<OwnProcess>, packageName: String, myPid: Int): List<Int> {
        fun rank(p: OwnProcess) = when {
            p.pid == myPid -> 2
            p.name == "$packageName:black" -> 1
            else -> 0
        }
        val own = processes.filter { it.name.startsWith(packageName) }
        val withSelf = if (own.any { it.pid == myPid }) own else own + OwnProcess(packageName, myPid)
        return withSelf.distinctBy { it.pid }.sortedBy(::rank).map { it.pid }
    }

    /**
     * Steps 1-6 except the very last kill: [Process.killProcess] of the current process is left to the
     * caller, after it has released its broadcast. Runs off the main thread (blocks, up to a few seconds).
     */
    fun run(app: Context) {
        val prefs = KeepAlivePrefs(app)
        // 1. The flag first: from now on nothing may bring the service back.
        step("persist flag") { prefs.exitedByUser = true }
        // 2. Macros and every overlay window.
        step("hide overlays") { KeepAliveService.prepareExit() }
        step("cancel recording files") { ServiceLocator.macroRecorder(app).cancel() }
        // 3. Stop every running clone through the normal path (marks them as manually stopped).
        step("stop clones") { stopClones(app) }
        // 4. Notifications, then the keep-alive service through its own command.
        step("cancel notifications") { NotificationManagerCompat.from(app).cancelAll() }
        step("stop service") { stopKeepAliveService(app) }
        step("cancel notifications again") { NotificationManagerCompat.from(app).cancelAll() }
        // 5. The engine's daemon (explicit stop: Android does not sticky-restart a service that was stopped).
        step("stop engine daemon") { app.stopService(Intent().setClassName(app, ENGINE_DAEMON)) }
        // 6. Recents, then the processes.
        step("remove tasks") { removeTasks(app) }
        step("kill processes") { killProcesses(app) }
    }

    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "Exit step failed: $name", e)
        }
    }

    private fun stopClones(app: Context) {
        val engine = ServiceLocator.engine()
        if (!engine.awaitReady(ENGINE_READY_TIMEOUT_MS)) return
        val repository = ServiceLocator.repository(app)
        for (record in ServiceLocator.store(app).all()) {
            val id = record.id
            try {
                if (engine.isRunning(id)) runBlocking { repository.stop(id) }
            } catch (e: Exception) {
                Log.w(TAG, "Could not stop $id", e)
            }
        }
    }

    private fun stopKeepAliveService(app: Context) {
        if (!KeepAliveService.isAlive) return
        // startForegroundService, like every other start: the service enters the foreground first (the
        // contract), then sees the command, leaves the foreground and stops itself.
        val delivered = try {
            ContextCompat.startForegroundService(app, KeepAliveService.exitIntent(app))
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not deliver the exit command", e)
            false
        }
        if (delivered && waitUntilStopped(SERVICE_STOP_WAIT_MS)) return
        app.stopService(KeepAliveService.intent(app))
        waitUntilStopped(SERVICE_STOP_WAIT_MS)
    }

    private fun waitUntilStopped(timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (KeepAliveService.isAlive && System.currentTimeMillis() < end) Thread.sleep(POLL_MS)
        return !KeepAliveService.isAlive
    }

    private fun removeTasks(app: Context) {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (task in am.appTasks) {
            try {
                task.finishAndRemoveTask()
            } catch (e: Exception) {
                Log.w(TAG, "Could not remove a task", e)
            }
        }
    }

    /** Everything except the current process; the caller kills that one last. */
    private fun killProcesses(app: Context) {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val myPid = Process.myPid()
        val processes = am.runningAppProcesses.orEmpty().map { OwnProcess(it.processName, it.pid) }
        for (pid in killOrder(processes, app.packageName, myPid)) {
            if (pid != myPid) Process.killProcess(pid)
        }
    }
}
