package dev.clonelab.app.keepalive

import android.app.KeyguardManager
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
import android.provider.Settings
import android.util.Log
import android.view.ViewConfiguration
import android.widget.Toast
import androidx.core.app.ServiceCompat
import dev.clonelab.app.R
import dev.clonelab.app.ServiceLocator
import dev.clonelab.app.macro.InstrumentationInjector
import dev.clonelab.app.macro.MacroRunState
import dev.clonelab.app.macro.MacroRunner
import dev.clonelab.app.macro.MacroSchedule
import dev.clonelab.app.macro.ScheduledStartPolicy
import dev.clonelab.app.macro.ScreenMetrics
import dev.clonelab.app.macro.ui.MacroOverlays
import dev.clonelab.app.model.CloneId
import dev.clonelab.app.model.CloneRecord
import dev.clonelab.app.switcher.CloneLabForeground
import dev.clonelab.app.switcher.FocusDebouncer
import dev.clonelab.app.switcher.NavLevel
import dev.clonelab.app.switcher.SwitcherModel
import dev.clonelab.app.switcher.SwitcherNav
import dev.clonelab.app.switcher.SwitcherView
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference

/**
 * Foreground service of the main process that keeps the app alive while any clone has keep-alive on,
 * watches those clones, and owns the floating clone switcher. It holds no wake lock. The engine has
 * its own DaemonService in :black; this one is separate.
 */
class KeepAliveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchdog: Job? = null

    /** One watchdog pass at a time: its sets are plain HashSets and a stop must be reported once. */
    private val tickMutex = Mutex()
    private var switcherJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Touched on the main thread only. */
    private var overlay: SwitcherOverlay? = null

    /** What the user chose in the pill. Written by taps (main thread) and by the poll (IO); see [prepareSwitcher]. */
    private val switcherNav = AtomicReference(SwitcherNav.NONE)

    /** The focused clone the pill was last reduced for (null inside CloneLab): the context of a tap. */
    @Volatile
    private var navAnchor: CloneId? = null

    /** Wakes the switcher loop before its 700 ms are up. */
    private val switcherWake = Channel<Unit>(Channel.CONFLATED)

    /** The macro panel, editor and bars. Touched on the main thread only. */
    private var macroOverlays: MacroOverlays? = null

    /** Id of the latest start command; stopSelf(id) is ignored by Android if a newer start arrived. */
    @Volatile
    private var latestStartId = 0
    private val alerted = mutableSetOf<CloneId>()
    private val seenRunning = mutableSetOf<CloneId>()

    /** Strong reference for the service's lifetime; the UI sees it through [macroRunner]. */
    private var macroRunnerField: MacroRunner? = null
    private lateinit var notifications: KeepAliveNotifications
    private lateinit var runningNotifications: RunningNotifications

    override fun onCreate() {
        super.onCreate()
        alive = true
        instanceRef = WeakReference(this)
        notifications = KeepAliveNotifications(this)
        notifications.createChannels()
        runningNotifications = RunningNotifications(this, scope, notifications, KeepAlivePrefs(this))
        ManualStops.install(this)
        overlay = SwitcherOverlay(
            this, SwitcherPrefs(this), ::switchTo,
            onSelectApp = { packageName -> navigate(SwitcherNav.drill(navAnchor, packageName)) },
            onBack = { navigate(SwitcherNav.back(navAnchor)) },
            onMacros = { macroOverlays?.openPanel() },
        )
        val runner = MacroRunner(
            scope = scope,
            recorder = ServiceLocator.macroRecorder(this),
            store = ServiceLocator.macroStore(this),
            engine = ServiceLocator.engine(),
            injector = InstrumentationInjector(),
            launchClone = { id -> ServiceLocator.repository(applicationContext).launch(id) },
            screenSize = { ScreenMetrics.realSize(this) },
            tapSlopPx = { ViewConfiguration.get(this).scaledTouchSlop.toFloat() },
            scheduledStartAllowed = ::scheduledStartAllowed,
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
        // "Exit CloneLab": the command itself, or a sticky restart / stale start after one. The service has
        // entered the foreground above (the contract), so it can now leave it and end.
        if (intent?.action == ACTION_EXIT || KeepAlivePrefs(this).exitedByUser) {
            leaveForExit()
            return START_NOT_STICKY
        }
        runningNotifications.requestRefresh(force = true) // also applies a changed setting
        watchdog?.cancel()
        watchdog = scope.launch {
            while (isActive) {
                try {
                    if (!tickMutex.withLock { tick() }) break
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

    /** Main thread. Ends everything this service owns, leaves the foreground and stops itself. */
    private fun leaveForExit() {
        watchdog?.cancel()
        switcherJob?.cancel()
        hideForExit()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf() // not stopSelf(startId): the exit must win over any newer start command
    }

    /** Main thread: cancels macro recording/playback and removes the pill and every macro window. */
    private fun hideForExit() {
        macroRunnerField?.let {
            it.cancelRecording()
            it.stop()
        }
        macroOverlays?.destroy()
        macroOverlays = null
        overlay?.hide()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onConfigurationChanged()
        macroOverlays?.onConfigurationChanged()
    }

    override fun onDestroy() {
        runnerRef = null
        instanceRef = null
        // Scope first, so a recording start that has not run yet cannot create its flag afterwards; then
        // the runner cancels an active recording and clears the flag.
        scope.cancel()
        runningNotifications.shutdown()
        macroRunnerField?.shutdown()
        macroRunnerField = null
        mainHandler.removeCallbacksAndMessages(null)
        // Cancelled coroutines may still have main-thread work queued: remove the windows right here.
        macroOverlays?.destroy()
        macroOverlays = null
        overlay?.hide()
        overlay = null
        alive = false
        super.onDestroy()
    }

    /** Phone in use (screen on, unlocked) and a way to bring the clone up; see [ScheduledStartPolicy]. */
    private fun scheduledStartAllowed(target: CloneId): Boolean {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val locked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            keyguard.isKeyguardLocked
        } else {
            @Suppress("DEPRECATION")
            keyguard.inKeyguardRestrictedInputMode()
        }
        val canDraw = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        return ScheduledStartPolicy.mayStart(
            interactive = power.isInteractive,
            keyguardLocked = locked,
            canDrawOverlays = canDraw,
            targetFocused = canDraw || ServiceLocator.engine().focusedClone() == target,
        )
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
            exitedByUser = KeepAlivePrefs(this@KeepAliveService).exitedByUser,
        )
        if (!wanted && !macroBusy) return@withContext false
        runningNotifications.requestRefresh()
        runner?.tick()
        if (records.isEmpty()) return@withContext true // only the switcher needs the service
        val engine = ServiceLocator.engine()
        if (!engine.awaitReady(READY_TIMEOUT_MS)) return@withContext true
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
            val manuallyStopped = ManualStops.contains(id)
            if (manuallyStopped) {
                alerted.remove(id)
                notifications.cancelStopped(record)
            }
            val action = WatchdogPolicy.decide(running = false, manuallyStopped = manuallyStopped, seenRunning = id in seenRunning)
            // Never relaunched from here: see WatchdogPolicy. The alert is posted once per stop.
            if (action == WatchdogAction.Notify && alerted.add(id)) notifications.stopped(record)
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
        val view: SwitcherView,
        /** The focused clone if the store knows it; what the macro windows follow. */
        val focused: CloneId?,
        val records: List<CloneRecord>?,
        val cloneIcons: Map<CloneId, Drawable>,
        val appIcons: Map<String, Drawable>,
    )

    /** The user tapped an app or the back row (main thread): remember it and refresh the pill at once. */
    private fun navigate(next: SwitcherNav) {
        switcherNav.set(next)
        switcherWake.trySend(Unit)
    }

    /**
     * Polls the focused clone and whether CloneLab itself is in front, and mirrors them into the pill.
     * Engine, store and icon work run on IO; window calls run on the main thread. The pill is removed when
     * the setting is off, the overlay permission is gone, the screen is off or the user exited. Icons are
     * rebuilt only when the records change. A tap in the pill wakes the loop early instead of waiting.
     */
    private suspend fun runSwitcher() {
        val debouncer = FocusDebouncer()
        val prefs = SwitcherPrefs(this)
        val keepAlivePrefs = KeepAlivePrefs(this)
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        var lastRecords: List<CloneRecord>? = null
        var cloneIcons: Map<CloneId, Drawable> = emptyMap()
        var appIcons: Map<String, Drawable> = emptyMap()
        var pushed: SwitcherView? = null
        var lastFocused: CloneId? = null
        try {
            while (true) {
                val allowed = prefs.enabled && !keepAlivePrefs.exitedByUser &&
                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) &&
                    power.isInteractive
                var view: SwitcherView = SwitcherView.Hidden
                var focusedKnown: CloneId? = null
                if (allowed) {
                    try {
                        val prepared = withContext(Dispatchers.IO) {
                            prepareSwitcher(debouncer, lastRecords, cloneIcons, appIcons)
                        }
                        view = prepared.view
                        focusedKnown = prepared.focused
                        lastRecords = prepared.records
                        cloneIcons = prepared.cloneIcons
                        appIcons = prepared.appIcons
                        // A different clone coming to the front is a cheap hint that something started.
                        if (focusedKnown != null && focusedKnown != lastFocused) runningNotifications.requestRefresh()
                        if (focusedKnown != null) lastFocused = focusedKnown
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Switcher poll failed", e)
                    }
                } else {
                    debouncer.reset()
                    switcherNav.set(SwitcherNav.NONE)
                }
                val shownClones = cloneIcons
                val shownApps = appIcons
                withContext(Dispatchers.Main.immediate) {
                    val appLabel = focusedKnown?.let { id ->
                        lastRecords?.firstOrNull { it.id.packageName == id.packageName }?.appLabel
                    } ?: focusedKnown?.packageName.orEmpty()
                    macroOverlays?.onFocusChanged(focusedKnown, appLabel)
                    val window = overlay ?: return@withContext
                    if (view == SwitcherView.Hidden) {
                        pushed = null
                        window.hide()
                    } else if (view != pushed || !window.isShown) {
                        window.show(view, shownClones, shownApps)
                        // A refused window is retried on the next pass.
                        pushed = if (window.isShown) view else null
                    }
                }
                withTimeoutOrNull(SWITCHER_POLL_MS) { switcherWake.receive() }
            }
        } finally {
            // Normally onDestroy has removed the window; this covers a job that ends on its own.
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { overlay?.hide() }
        }
    }

    private fun prepareSwitcher(
        debouncer: FocusDebouncer,
        lastRecords: List<CloneRecord>?,
        lastCloneIcons: Map<CloneId, Drawable>,
        lastAppIcons: Map<String, Drawable>,
    ): Prepared {
        val engine = ServiceLocator.engine()
        val focused = debouncer.onPoll(engine.focusedClone())
        val inCloneLab = CloneLabForeground.isForeground
        val records = ServiceLocator.store(applicationContext).all()
        var cloneIcons = lastCloneIcons
        var appIcons = lastAppIcons
        if (records != lastRecords || cloneIcons.isEmpty()) {
            AppVisuals.warmUp(applicationContext, engine, records)
            cloneIcons = records.associate { it.id to AppVisuals.icon(applicationContext, it) }
            appIcons = SwitcherModel.apps(records, emptySet())
                .associate { it.packageName to AppVisuals.appIcon(applicationContext, it.representative) }
        }
        val nav = switcherNav.get()
        // Only the focused app's clones are needed while its Clones level shows; any other level lists every app.
        val needAll = inCloneLab || nav.level != NavLevel.DEFAULT
        val running = records
            .filter { needAll || focused == null || it.id.packageName == focused.packageName }
            .filter { engine.isRunning(it.id) }
            .map { it.id }.toSet()
        val reduction = SwitcherModel.reduce(
            records, running, focused, inCloneLab, nav, AppVisuals::label, AppVisuals::appLabel,
        )
        // A tap that arrived meanwhile has replaced the snapshot: keep the tap.
        switcherNav.compareAndSet(nav, reduction.nav)
        navAnchor = focused?.takeIf { f -> records.any { it.id == f } }
        val focusedKnown = navAnchor
        return Prepared(reduction.view, focusedKnown, records, cloneIcons, appIcons)
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

        private const val ACTION_EXIT = "dev.clonelab.app.action.EXIT_SERVICE"

        /** True from onCreate to onDestroy; lets the exit sequence wait until the service is really gone. */
        @Volatile
        private var alive = false
        val isAlive: Boolean get() = alive

        @Volatile
        private var instanceRef: WeakReference<KeepAliveService>? = null

        /**
         * Ends a running service from inside the process, any thread. Unlike a start command this
         * does not make the service enter the foreground again first, so no notification can be
         * (re)posted on the way out. False when there is no running service.
         */
        fun exitNow(): Boolean {
            val service = instanceRef?.get() ?: return false
            service.mainHandler.post { service.leaveForExit() }
            return true
        }

        /** Exit step 2, any thread: cancels macros and removes the pill and every macro window right now. */
        fun prepareExit() {
            val service = instanceRef?.get() ?: return
            if (Looper.myLooper() == Looper.getMainLooper()) {
                service.hideForExit()
            } else {
                // Blocks until done so the next exit step starts with the windows gone.
                val latch = java.util.concurrent.CountDownLatch(1)
                service.mainHandler.post {
                    try {
                        service.hideForExit()
                    } finally {
                        latch.countDown()
                    }
                }
                latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
            }
        }

        fun intent(context: Context) = Intent(context, KeepAliveService::class.java)

        /** Delivered with startForegroundService; the service goes foreground, then leaves it and stops. */
        fun exitIntent(context: Context) = intent(context).setAction(ACTION_EXIT)
    }
}
