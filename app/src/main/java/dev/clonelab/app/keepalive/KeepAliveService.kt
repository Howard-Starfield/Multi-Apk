package dev.clonelab.app.keepalive

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.ViewConfiguration
import android.widget.Toast
import dev.clonelab.app.R
import dev.clonelab.app.ServiceLocator
import dev.clonelab.app.macro.InstrumentationInjector
import dev.clonelab.app.macro.MacroRunState
import dev.clonelab.app.macro.MacroRunner
import dev.clonelab.app.macro.MacroSchedule
import dev.clonelab.app.macro.ScreenMetrics
import dev.clonelab.app.macro.ui.MacroOverlays
import dev.clonelab.app.model.CloneId
import dev.clonelab.app.model.CloneRecord
import dev.clonelab.app.switcher.FocusDebouncer
import dev.clonelab.app.switcher.SwitcherItem
import dev.clonelab.app.switcher.SwitcherModel
import dev.clonelab.app.switcher.SwitcherState
import dev.clonelab.app.switcher.SwitcherOverlay
import dev.clonelab.app.switcher.SwitcherPrefs
import dev.clonelab.app.ui.AppVisuals
import dev.clonelab.app.ui.toUserMessage
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
import java.lang.ref.WeakReference

/**
 * Foreground service of the main process that keeps the app alive while any clone has keep-alive on,
 * watches those clones, and owns the floating clone switcher. It holds no wake lock. The engine has
 * its own DaemonService in :black; this one is separate.
 */
class KeepAliveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchdog: Job? = null
    private var switcherJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Touched on the main thread only. */
    private var overlay: SwitcherOverlay? = null

    /** The macro panel, editor and bars. Touched on the main thread only. */
    private var macroOverlays: MacroOverlays? = null

    /** Id of the latest start command; stopSelf(id) is ignored by Android if a newer start arrived. */
    @Volatile
    private var latestStartId = 0
    private val lastRestartAt = mutableMapOf<CloneId, Long>()
    private val alerted = mutableSetOf<CloneId>()
    private val seenRunning = mutableSetOf<CloneId>()

    /** Strong reference for the service's lifetime; the UI sees it through [macroRunner]. */
    private var macroRunnerField: MacroRunner? = null
    private lateinit var notifications: KeepAliveNotifications

    override fun onCreate() {
        super.onCreate()
        notifications = KeepAliveNotifications(this)
        notifications.createChannels()
        ManualStops.install(this)
        overlay = SwitcherOverlay(this, SwitcherPrefs(this), ::switchTo, onMacros = { macroOverlays?.openPanel() })
        val runner = MacroRunner(
            scope = scope,
            recorder = ServiceLocator.macroRecorder(this),
            store = ServiceLocator.macroStore(this),
            engine = ServiceLocator.engine(),
            injector = InstrumentationInjector(),
            launchClone = { id -> ServiceLocator.repository(applicationContext).launch(id) },
            screenSize = { ScreenMetrics.realSize(this) },
            tapSlopPx = { ViewConfiguration.get(this).scaledTouchSlop.toFloat() },
        )
        macroRunnerField = runner
        runnerRef = WeakReference(runner)
        macroOverlays = MacroOverlays(this, scope, { macroRunner }, { busy -> overlay?.setMacroBusy(busy) })
            .also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        notifications.createChannels() // re-applies the channel names, which follow the system language
        try {
            startForeground(KeepAliveNotifications.SERVICE_ID, notifications.service(0))
        } catch (e: Exception) {
            // Android 12+ refuses this when started from the background; do not crash the app.
            Log.w(TAG, "Could not enter the foreground", e)
            stopSelf(startId)
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
            // Nobody wants the service any more (this is also how a "stop" request ends it).
            stopSelf(latestStartId)
        }
        if (switcherJob?.isActive != true) switcherJob = scope.launch { runSwitcher() }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onConfigurationChanged()
        macroOverlays?.onConfigurationChanged()
    }

    override fun onDestroy() {
        runnerRef = null
        macroRunnerField?.shutdown()
        macroRunnerField = null
        scope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        // Cancelled coroutines may still have main-thread work queued: remove the windows right here.
        macroOverlays?.destroy()
        macroOverlays = null
        overlay?.hide()
        overlay = null
        super.onDestroy()
    }

    /** One watchdog pass. @return false when nothing wants the service any more. */
    private suspend fun tick(): Boolean = withContext(Dispatchers.IO) {
        val all = ServiceLocator.store(applicationContext).all()
        val records = all.filter { it.keepAlive }
        val macros = ServiceLocator.macroStore(applicationContext).all()
        val scheduled = macros.count { it.schedule != MacroSchedule.Off && it.scheduleUserId != null }
        val runner = macroRunner
        val macroBusy = runner != null && runner.state.value != MacroRunState.Idle
        val wanted = KeepAliveController.shouldRun(
            records.size, all.size, SwitcherPrefs(this@KeepAliveService).enabled, scheduled,
        )
        if (!wanted && !macroBusy) return@withContext false
        runner?.tick()
        if (records.isEmpty()) return@withContext true // only the switcher needs the service
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
                seenRunning.add(id)
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
                seenRunning = id in seenRunning,
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

    /** An entry of the pill was tapped (main thread): launch that clone off the main thread. */
    private fun switchTo(id: CloneId) {
        scope.launch(Dispatchers.IO) {
            val repository = ServiceLocator.repository(applicationContext)
            repository.launch(id).onFailure {
                val message = getString(R.string.switcher_launch_failed, it.toUserMessage(R.string.error_launch_failed).resolve(this@KeepAliveService))
                withContext(Dispatchers.Main) { Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show() }
            }
        }
    }

    private class Prepared(
        val items: List<SwitcherItem>,
        val records: List<CloneRecord>?,
        val icons: Map<CloneId, Drawable>,
    )

    /**
     * Polls the focused clone and mirrors it into the pill. Engine, store and icon work run on IO;
     * window calls run on the main thread. The pill is removed when the setting is off, the overlay
     * permission is gone or the screen is off. Icons are rebuilt only when the records change.
     */
    private suspend fun runSwitcher() {
        val debouncer = FocusDebouncer()
        val prefs = SwitcherPrefs(this)
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        var lastRecords: List<CloneRecord>? = null
        var icons: Map<CloneId, Drawable> = emptyMap()
        var pushed: List<SwitcherItem>? = null
        try {
            while (true) {
                val allowed = prefs.enabled &&
                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) &&
                    power.isInteractive
                var items = emptyList<SwitcherItem>()
                if (allowed) {
                    try {
                        val prepared = withContext(Dispatchers.IO) { prepareSwitcher(debouncer, lastRecords, icons) }
                        items = prepared.items
                        lastRecords = prepared.records
                        icons = prepared.icons
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Switcher poll failed", e)
                    }
                } else {
                    debouncer.reset()
                }
                val shownIcons = icons
                withContext(Dispatchers.Main.immediate) {
                    val focusedId = items.firstOrNull { it.state == SwitcherState.FOCUSED }?.id
                    val appLabel = focusedId?.let { id ->
                        lastRecords?.firstOrNull { it.id.packageName == id.packageName }?.appLabel
                    } ?: focusedId?.packageName.orEmpty()
                    macroOverlays?.onFocusChanged(focusedId, appLabel)
                    val window = overlay ?: return@withContext
                    if (items.isEmpty()) {
                        pushed = null
                        window.hide()
                    } else if (items != pushed || !window.isShown) {
                        window.show(items, shownIcons)
                        // A refused window is retried on the next pass.
                        pushed = if (window.isShown) items else null
                    }
                }
                delay(SWITCHER_POLL_MS)
            }
        } finally {
            // Normally onDestroy has removed the window; this covers a job that ends on its own.
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { overlay?.hide() }
        }
    }

    private fun prepareSwitcher(
        debouncer: FocusDebouncer,
        lastRecords: List<CloneRecord>?,
        lastIcons: Map<CloneId, Drawable>,
    ): Prepared {
        val engine = ServiceLocator.engine()
        val focused = debouncer.onPoll(engine.focusedClone())
            ?: return Prepared(emptyList(), lastRecords, lastIcons)
        val records = ServiceLocator.store(applicationContext).all().filter { it.id.packageName == focused.packageName }
        var icons = lastIcons
        if (records != lastRecords || icons.isEmpty()) {
            AppVisuals.warmUp(applicationContext, engine, records)
            icons = records.associate { it.id to AppVisuals.icon(applicationContext, it) }
        }
        val running = records.filter { it.id != focused && engine.isRunning(it.id) }.map { it.id }.toSet()
        val items = SwitcherModel.items(records, focused, running, AppVisuals::label)
        return Prepared(items, records, icons)
    }

    companion object {
        private const val TAG = "KeepAliveService"
        private const val INTERVAL_MS = 30_000L
        private const val READY_TIMEOUT_MS = 5_000L
        private const val SWITCHER_POLL_MS = 700L

        @Volatile
        private var runnerRef: WeakReference<MacroRunner>? = null

        /** The macro runner of the running service, or null when the service is not running. */
        val macroRunner: MacroRunner? get() = runnerRef?.get()

        fun intent(context: Context) = Intent(context, KeepAliveService::class.java)
    }
}
