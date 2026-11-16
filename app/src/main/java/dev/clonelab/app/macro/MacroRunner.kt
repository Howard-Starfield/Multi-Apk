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
    data class Recording(val target: CloneId, val startedAt: Long) : MacroRunState
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
) {
    private val lock = Any()
    private val _state = MutableStateFlow<MacroRunState>(MacroRunState.Idle)
    val state: StateFlow<MacroRunState> = _state

    private val _results = MutableSharedFlow<PlayResult>(extraBufferCapacity = 4)

    /** One value per finished run (also when it ended on its own), for toasts. */
    val results: SharedFlow<PlayResult> = _results

    private var recordingStart: Job? = null
    private var playJob: Job? = null

    private val injectorThread: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "macro-player").apply { isDaemon = true }
    }
    private val injectorDispatcher: CoroutineDispatcher = injectorThread.asCoroutineDispatcher()

    init {
        scope.launch(Dispatchers.IO) { recorder.cleanStale() }
    }

    /** Call when the owner goes away. */
    fun shutdown() {
        injectorThread.shutdownNow()
    }

    fun startRecording(target: CloneId) {
        synchronized(lock) {
            if (_state.value != MacroRunState.Idle) return
            _state.value = MacroRunState.Recording(target, clock())
            recordingStart = scope.launch(Dispatchers.IO) {
                try {
                    recorder.start(target)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not start recording", e)
                    synchronized(lock) { if (_state.value is MacroRunState.Recording) _state.value = MacroRunState.Idle }
                }
            }
        }
    }

    /** @return the classified steps; not saved. Empty when nothing was being recorded. */
    suspend fun stopRecording(): List<MacroStep> {
        val pending = synchronized(lock) {
            if (_state.value !is MacroRunState.Recording) return emptyList()
            _state.value = MacroRunState.Idle
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
     */
    fun play(macro: Macro, target: CloneId) {
        synchronized(lock) {
            if (_state.value != MacroRunState.Idle) return
            _state.value = MacroRunState.Playing(macro, target, 0, 0)
            playJob = scope.launch { run(macro, target) }
        }
    }

    fun stop() {
        playJob?.cancel()
    }

    private suspend fun run(macro: Macro, target: CloneId) {
        var result: PlayResult = PlayResult.Stopped
        try {
            result = withContext(Dispatchers.IO) { ensureFocused(target) } ?: playFocused(macro, target)
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
        if (engine.focusedClone() == target) return null
        launchClone(target).onFailure { return PlayResult.Refused(it.message ?: "The app could not be launched") }
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
     * `lastScheduledRunAt` is stored before the run starts, so a failing run cannot repeat in a loop.
     */
    fun tick() {
        if (_state.value != MacroRunState.Idle) return
        val now = clock()
        val zone = zone()
        val due = store.all().firstOrNull { MacroScheduler.due(it, now, zone) } ?: return
        val userId = due.scheduleUserId ?: return
        val updated = due.copy(lastScheduledRunAt = now)
        store.put(updated)
        play(updated, CloneId(updated.packageName, userId))
    }

    private companion object {
        const val TAG = "MacroRunner"
        const val FOCUS_WAIT_MS = 8_000L
        const val FOCUS_POLL_MS = 250L
        const val SETTLE_AFTER_LAUNCH_MS = 600L
        const val FOCUS_RECHECKS = 4
        const val FOCUS_RECHECK_MS = 150L
    }
}
