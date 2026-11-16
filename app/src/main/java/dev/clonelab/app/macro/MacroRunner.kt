package dev.clonelab.app.macro

import android.util.Log
import dev.clonelab.app.engine.CloneEngine
import dev.clonelab.app.model.CloneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

sealed interface MacroRunState {
    data object Idle : MacroRunState
    /** [screenSize] is the display size when the recording started: what the macro's coordinates refer to. */
    data class Recording(val target: CloneId, val startedAt: Long, val screenSize: Pair<Int, Int>) : MacroRunState
    data class Playing(val macro: Macro, val target: CloneId, val repeatIndex: Int, val stepIndex: Int) : MacroRunState
}

/**
 * The single entry point the UI uses for recording and playing macros. Main process only, owned by
 * KeepAliveService (which supplies its scope). Only one thing happens at a time: a call that does not
 * fit the current state is ignored.
 */
class MacroRunner(
    private val scope: CoroutineScope,
    private val recorder: MacroRecorder,
    private val store: MacroStore,
    private val engine: CloneEngine,
    private val injector: InputInjector,
    /** Launches (brings to the front) a clone; the repository's launch in production. */
    private val launchClone: suspend (CloneId) -> Result<Unit>,
    private val screenSize: () -> Pair<Int, Int>,
    private val tapSlopPx: () -> Float,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> TimeZone = TimeZone::getDefault,
    /** Whether a scheduled run may start now (screen on, unlocked, overlay permission); see [ScheduledStartPolicy]. */
    private val scheduledStartAllowed: (CloneId) -> Boolean = { true },
) {
    private val lock = Any()
    private val _state = MutableStateFlow<MacroRunState>(MacroRunState.Idle)
    val state: StateFlow<MacroRunState> = _state

    private val _results = MutableSharedFlow<PlayResult>(extraBufferCapacity = 4)

    /** One value per finished run (also when it ended on its own), for toasts. */
    val results: SharedFlow<PlayResult> = _results

    private var recordingStart: Job? = null
    private var heartbeat: Job? = null
    private var playJob: Job? = null

    private val injectorThread: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "macro-player").apply { isDaemon = true }
    }
    private val injectorDispatcher: CoroutineDispatcher = injectorThread.asCoroutineDispatcher()

    init {
        scope.launch(Dispatchers.IO) { recorder.cleanStale() }
    }

    /**
     * Call when the owner goes away, after its scope was cancelled. An active recording is cancelled so
     * its flag does not outlive the service (the heartbeat would stop anyway; this is the quick path).
     */
    fun shutdown() {
        injectorThread.shutdownNow()
        val recording = synchronized(lock) {
            val was = _state.value is MacroRunState.Recording
            if (was) _state.value = MacroRunState.Idle
            was
        }
        if (recording) {
            try {
                recorder.cancel()
            } catch (e: Exception) {
                Log.w(TAG, "Could not cancel the recording on shutdown", e)
            }
        }
    }

    fun startRecording(target: CloneId) {
        synchronized(lock) {
            if (_state.value != MacroRunState.Idle) return
            _state.value = MacroRunState.Recording(target, clock(), screenSize())
            val start = scope.launch(Dispatchers.IO) {
                try {
                    recorder.start(target)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not start recording", e)
                    synchronized(lock) { if (_state.value is MacroRunState.Recording) _state.value = MacroRunState.Idle }
                }
            }
            recordingStart = start
            // Keeps the flag fresh: clones ignore a flag that has not been touched for ~15 s, which is
            // what ends a recording whose main process died. Separate from `start` because stop joins that.
            heartbeat = scope.launch(Dispatchers.IO) {
                start.join()
                while (true) {
                    delay(RecordingFiles.HEARTBEAT_MS)
                    try {
                        recorder.heartbeat()
                    } catch (e: Exception) {
                        Log.w(TAG, "Recording heartbeat failed", e)
                    }
                }
            }
        }
    }

    /** @return the classified steps; not saved. Empty when nothing was being recorded. */
    suspend fun stopRecording(): List<MacroStep> {
        val pending = synchronized(lock) {
            if (_state.value !is MacroRunState.Recording) return emptyList()
            _state.value = MacroRunState.Idle
            heartbeat?.cancel()
            recordingStart
        }
        return withContext(Dispatchers.IO) {
            pending?.join()
            try {
                recorder.stop(tapSlopPx())
            } catch (e: Exception) {
                Log.w(TAG, "Could not read the recording", e)
                emptyList()
            }
        }
    }

    fun cancelRecording() {
        val pending = synchronized(lock) {
            if (_state.value !is MacroRunState.Recording) return
            _state.value = MacroRunState.Idle
            heartbeat?.cancel()
            recordingStart
        }
        scope.launch(Dispatchers.IO) {
            pending?.join()
            recorder.cancel()
        }
    }

    /**
     * Brings [target] to the front if it is not there (waiting up to 8 s for it), then plays. The result
     * arrives on [results]; [state] is Playing meanwhile and Idle again afterwards.
     * @return false when something else is going on and nothing was started.
     */
    fun play(macro: Macro, target: CloneId): Boolean = synchronized(lock) {
        if (_state.value != MacroRunState.Idle) return false
        _state.value = MacroRunState.Playing(macro, target, 0, 0)
        playJob = scope.launch { run(macro, target) }
        true
    }

    fun stop() {
        playJob?.cancel()
    }

    private suspend fun run(macro: Macro, target: CloneId) {
        var result: PlayResult = PlayResult.Stopped
        try {
            val (width, height) = screenSize()
            result = MacroPlayer.orientationMismatch(macro, width, height)
                ?: withContext(Dispatchers.IO) { ensureFocused(target) }
                ?: playFocused(macro, target)
        } catch (e: CancellationException) {
            result = PlayResult.Stopped
        } catch (e: Exception) {
            Log.w(TAG, "Macro run failed", e)
            result = PlayResult.Refused(e.message ?: e.javaClass.simpleName)
        } finally {
            synchronized(lock) { _state.value = MacroRunState.Idle }
            _results.tryEmit(result)
        }
    }

    private suspend fun playFocused(macro: Macro, target: CloneId): PlayResult {
        val player = MacroPlayer(injector = injector, isStillFocused = { focusHeld(target) })
        val (width, height) = screenSize()
        return withContext(injectorDispatcher) {
            player.play(macro, width, height) { repeatIndex, stepIndex ->
                _state.value = MacroRunState.Playing(macro, target, repeatIndex, stepIndex)
            }
        }
    }

    /** @return null when [target] is in front, else the result to finish with. */
    private suspend fun ensureFocused(target: CloneId): PlayResult? {
        if (engine.focusedClone() == target) {
            // The panel or editor that started this run is still closing; a step fired now would
            // land on that window instead of the clone.
            delay(SETTLE_BEFORE_FIRST_STEP_MS)
            return null
        }
        launchClone(target).onFailure { return PlayResult.LaunchFailed(it) }
        val deadline = System.nanoTime() + FOCUS_WAIT_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            if (engine.focusedClone() == target) {
                delay(SETTLE_AFTER_LAUNCH_MS)
                return null
            }
            delay(FOCUS_POLL_MS)
        }
        return PlayResult.FocusLost
    }

    /**
     * focusedClone() is briefly null while activities change, which a tap often causes; only a clone
     * that stays away for [FOCUS_RECHECKS] polls counts as lost. Runs on the player thread.
     */
    private fun focusHeld(target: CloneId): Boolean {
        repeat(FOCUS_RECHECKS) { attempt ->
            if (engine.focusedClone() == target) return true
            if (attempt < FOCUS_RECHECKS - 1) Thread.sleep(FOCUS_RECHECK_MS)
        }
        return false
    }

    /**
     * Called about every 30 s by the service. Starts the first due macro when nothing else is going on.
     * The Playing state is claimed first; only when the run really started is `lastScheduledRunAt` stored
     * (through [MacroStore.markRun], which leaves an edit saved meanwhile alone), so a failing run cannot
     * repeat in a loop and a run that could not start is not lost. While the phone is not in use (see
     * [ScheduledStartPolicy]) nothing starts and the macro stays due for a later tick.
     */
    fun tick() {
        if (_state.value != MacroRunState.Idle) return
        val now = clock()
        val zone = zone()
        val due = store.all().firstOrNull { MacroScheduler.due(it, now, zone) } ?: return
        val userId = due.scheduleUserId ?: return
        val target = CloneId(due.packageName, userId)
        if (!scheduledStartAllowed(target)) return
        if (!play(due, target)) return
        try {
            store.markRun(due.id, now)
        } catch (e: Exception) {
            Log.w(TAG, "Could not store the run time of ${due.id}", e)
        }
    }

    private companion object {
        const val TAG = "MacroRunner"
        const val FOCUS_WAIT_MS = 8_000L
        const val FOCUS_POLL_MS = 250L
        const val SETTLE_AFTER_LAUNCH_MS = 600L
        const val SETTLE_BEFORE_FIRST_STEP_MS = 450L
        const val FOCUS_RECHECKS = 4
        const val FOCUS_RECHECK_MS = 150L
    }
}
