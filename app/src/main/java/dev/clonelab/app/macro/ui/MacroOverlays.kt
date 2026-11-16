package dev.clonelab.app.macro.ui

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import dev.clonelab.app.R
import dev.clonelab.app.ServiceLocator
import dev.clonelab.app.keepalive.KeepAliveController
import dev.clonelab.app.macro.Macro
import dev.clonelab.app.macro.MacroRunState
import dev.clonelab.app.macro.MacroRunner
import dev.clonelab.app.macro.PlayResult
import dev.clonelab.app.ui.toUserMessage
import dev.clonelab.app.macro.ScreenMetrics
import dev.clonelab.app.model.CloneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** What the pill shows next to its Macros button. */
enum class MacroBusy { NONE, RECORDING, PLAYING }

/**
 * Coordinator of every macro window (panel, editor, recording bar, playing bar). Owned by
 * KeepAliveService, which feeds it the focused clone and tears it down in onDestroy. Exactly one
 * of the four is visible at a time; which one is a pure function of the runner's state, the focused
 * clone and [ui] (what the user opened), recomputed by [render].
 *
 * Everything here runs on the main thread. Store access is done on IO.
 */
class MacroOverlays(
    private val context: Context,
    private val scope: CoroutineScope,
    private val runnerProvider: () -> MacroRunner?,
    private val onBusyChanged: (MacroBusy) -> Unit,
) {
    private sealed interface Ui {
        data object None : Ui
        data class Panel(val packageName: String) : Ui
        data class Countdown(val target: CloneId) : Ui
        data class Editor(val draft: MacroDraft, val original: Macro?) : Ui
    }

    private enum class Surface { NONE, PANEL, EDITOR, RECORDING, PLAYING }

    private val host = OverlayHost(context)
    private val barPosition = BarPosition()
    private val store get() = ServiceLocator.macroStore(context)

    private var focused: CloneId? = null
    private var appLabel = ""
    private var ui: Ui = Ui.None
    private var countdown = 0

    /** The editor a test run came from; it comes back when the run ends. */
    private var testReturn: Ui.Editor? = null
    private var saving = false
    private var destroyed = false
    private var lastBusy = MacroBusy.NONE

    private var panel: MacroPanel? = null
    private var editor: MacroEditor? = null
    private var recordingBar: RecordingBar? = null
    private var playingBar: PlayingBar? = null

    private var countdownJob: Job? = null
    private var stopJob: Job? = null

    /** Starts watching the runner. Safe to call once. */
    fun start() {
        val runner = runnerProvider() ?: return
        scope.launch(Dispatchers.Main.immediate) { runner.state.collect { render() } }
        scope.launch(Dispatchers.Main.immediate) { runner.results.collect { onResult(it) } }
    }

    /** The clone in front (or null) and the app's label. Call from the main thread on every poll. */
    fun onFocusChanged(id: CloneId?, label: String) {
        if (destroyed) return
        if (id == focused && label == appLabel) return
        focused = id
        appLabel = label
        when (val current = ui) {
            is Ui.Panel -> if (id == null || id.packageName != current.packageName) ui = Ui.None
            is Ui.Countdown -> if (id != current.target) {
                countdownJob?.cancel()
                ui = Ui.None
            }
            else -> Unit
        }
        render()
    }

    /** The pill's Macros button. */
    fun openPanel() {
        if (destroyed) return
        val runner = runnerProvider()
        val target = focused
        if (runner == null || !canDrawOverlays()) {
            toast(context.getString(R.string.macros_need_overlay))
            return
        }
        if (target == null || runner.state.value != MacroRunState.Idle) return
        ui = Ui.Panel(target.packageName)
        render()
    }

    fun onConfigurationChanged() {
        if (destroyed) return
        panel?.onConfigurationChanged()
        editor?.onConfigurationChanged()
        recordingBar?.reposition()
        playingBar?.reposition()
    }

    /** Removes every window. After this the object ignores calls. */
    fun destroy() {
        destroyed = true
        countdownJob?.cancel()
        stopJob?.cancel()
        closeExcept(Surface.NONE)
        ui = Ui.None
        testReturn = null
    }

    private fun canDrawOverlays() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    private fun toast(text: String) {
        Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show()
    }

    // ---- rendering ----

    private fun render() {
        if (destroyed) return
        val state = runnerProvider()?.state?.value ?: MacroRunState.Idle
        val f = focused
        val current = ui
        if (state is MacroRunState.Recording && current is Ui.Countdown) ui = Ui.None
        val want = when {
            state is MacroRunState.Recording -> Surface.RECORDING
            state is MacroRunState.Playing -> Surface.PLAYING
            f == null -> Surface.NONE
            current is Ui.Countdown -> Surface.RECORDING
            current is Ui.Panel -> Surface.PANEL
            current is Ui.Editor && current.draft.packageName == f.packageName -> Surface.EDITOR
            else -> Surface.NONE
        }
        closeExcept(want)
        when (want) {
            Surface.PANEL -> showPanel()
            Surface.EDITOR -> showEditor()
            Surface.RECORDING -> showRecordingBar(state)
            Surface.PLAYING -> showPlayingBar(state as MacroRunState.Playing)
            Surface.NONE -> Unit
        }
        val busy = when (state) {
            is MacroRunState.Recording -> MacroBusy.RECORDING
            is MacroRunState.Playing -> MacroBusy.PLAYING
            MacroRunState.Idle -> MacroBusy.NONE
        }
        if (busy != lastBusy) {
            lastBusy = busy
            onBusyChanged(busy)
        }
    }

    private fun closeExcept(keep: Surface) {
        if (keep != Surface.PANEL) {
            panel?.hide()
            panel = null
        }
        if (keep != Surface.EDITOR) {
            editor?.hide()
            editor = null
        }
        if (keep != Surface.RECORDING) {
            recordingBar?.hide()
            recordingBar = null
        }
        if (keep != Surface.PLAYING) {
            playingBar?.hide()
            playingBar = null
        }
    }

    private fun showPanel() {
        if (panel != null) return
        val current = ui as? Ui.Panel ?: return
        val p = MacroPanel(host, panelListener)
        if (!p.show(context.getString(R.string.macro_panel_title, appLabel))) {
            ui = Ui.None // refused by the window manager; do not retry in a loop
            return
        }
        panel = p
        loadMacros(p, current.packageName)
    }

    private fun loadMacros(target: MacroPanel, packageName: String) {
        scope.launch(Dispatchers.Main) {
            val macros = withContext(Dispatchers.IO) { store.forPackage(packageName) }
            if (panel === target) target.setMacros(macros)
        }
    }

    private fun showEditor() {
        if (editor != null) return
        val current = ui as? Ui.Editor ?: return
        val number = (focused?.userId ?: current.original?.scheduleUserId ?: 0) + 1
        val e = MacroEditor(
            host = host,
            initial = current.draft,
            baseline = current.original?.let(MacroDraft::fromMacro),
            cloneNumber = number,
            listener = editorListener,
        )
        if (!e.show()) {
            ui = Ui.None
            return
        }
        editor = e
    }

    private fun showRecordingBar(state: MacroRunState) {
        var bar = recordingBar
        if (bar == null) {
            bar = RecordingBar(host, barPosition, onStop = ::stopRecording, onCancel = ::cancelRecording)
            recordingBar = bar
        }
        when {
            state is MacroRunState.Recording -> bar.showRecording(state.startedAt)
            else -> bar.showCountdown(countdown)
        }
        if (!bar.isShown) recordingBar = null // refused; the next state change tries again
    }

    private fun showPlayingBar(state: MacroRunState.Playing) {
        var bar = playingBar
        if (bar == null) {
            bar = PlayingBar(host, barPosition, onStop = { runnerProvider()?.stop() })
            playingBar = bar
        }
        bar.show(state.macro.name, state.repeatIndex, state.macro.repeatCount)
        if (!bar.isShown) playingBar = null
    }

    // ---- actions ----

    private val panelListener = object : MacroPanel.Listener {
        override fun onPlay(macro: Macro) {
            val target = focused ?: return
            val runner = runnerProvider() ?: return
            ui = Ui.None
            runner.play(macro, target)
            render()
        }

        override fun onEdit(macro: Macro) {
            ui = Ui.Editor(MacroDraft.fromMacro(macro), macro)
            render()
        }

        override fun onDelete(macro: Macro) {
            val shown = panel ?: return
            val packageName = macro.packageName
            scope.launch(Dispatchers.Main) {
                withContext(Dispatchers.IO) { store.remove(macro.id) }
                KeepAliveController.sync(context)
                if (panel === shown) loadMacros(shown, packageName)
            }
        }

        override fun onRecord() {
            focused?.let(::startCountdown)
        }

        override fun onClose() {
            ui = Ui.None
            render()
        }
    }

    private fun startCountdown(target: CloneId) {
        ui = Ui.Countdown(target)
        countdown = COUNTDOWN_FROM
        countdownJob?.cancel()
        countdownJob = scope.launch(Dispatchers.Main) {
            for (n in COUNTDOWN_FROM downTo 1) {
                countdown = n
                render()
                delay(1_000)
            }
            ui = Ui.None
            val runner = runnerProvider()
            if (runner != null && focused == target) runner.startRecording(target)
            render()
        }
    }

    private fun cancelRecording() {
        if (ui is Ui.Countdown) {
            countdownJob?.cancel()
            ui = Ui.None
            render()
            return
        }
        runnerProvider()?.cancelRecording()
    }

    private fun stopRecording() {
        val runner = runnerProvider() ?: return
        val recording = runner.state.value as? MacroRunState.Recording ?: return
        if (stopJob?.isActive == true) return
        recordingBar?.disableButtons()
        stopJob = scope.launch(Dispatchers.Main) {
            val steps = runner.stopRecording()
            if (steps.isEmpty()) {
                toast(context.getString(R.string.macro_nothing_recorded))
                return@launch
            }
            val packageName = recording.target.packageName
            val number = withContext(Dispatchers.IO) { store.forPackage(packageName).size } + 1
            val draft = MacroDraft.recorded(
                id = UUID.randomUUID().toString(),
                name = context.getString(R.string.macro_default_name, number),
                packageName = packageName,
                screenSize = ScreenMetrics.realSize(context),
                steps = steps,
                createdAt = System.currentTimeMillis(),
            )
            ui = Ui.Editor(draft, null)
            render()
        }
    }

    private val editorListener = object : MacroEditor.Listener {
        override fun onChanged(draft: MacroDraft) {
            val current = ui as? Ui.Editor ?: return
            ui = current.copy(draft = draft)
        }

        override fun onSave(draft: MacroDraft) {
            val current = ui as? Ui.Editor ?: return
            when (draft.problem()) {
                DraftProblem.BLANK_NAME -> return toast(context.getString(R.string.macro_name_required))
                DraftProblem.NO_STEPS -> return toast(context.getString(R.string.macro_needs_steps))
                null -> Unit
            }
            if (saving) return
            saving = true
            // A scheduled macro targets the clone that is in front when it is saved.
            val userId = focused?.userId ?: current.original?.scheduleUserId
            val macro = draft.toMacro(userId, System.currentTimeMillis(), current.original)
            scope.launch(Dispatchers.Main) {
                try {
                    withContext(Dispatchers.IO) { store.put(macro) }
                    KeepAliveController.sync(context)
                    ui = Ui.None
                    render()
                    toast(context.getString(R.string.macro_saved))
                } finally {
                    saving = false
                }
            }
        }

        override fun onTest(draft: MacroDraft) {
            val current = ui as? Ui.Editor ?: return
            if (draft.steps.isEmpty()) return toast(context.getString(R.string.macro_needs_steps))
            val runner = runnerProvider() ?: return
            val target = focused ?: return
            if (runner.state.value != MacroRunState.Idle) return
            testReturn = current.copy(draft = draft)
            ui = Ui.None
            runner.play(draft.toTestMacro(), target)
            render()
        }

        override fun onDiscard() {
            ui = Ui.None
            render()
        }
    }

    private fun onResult(result: PlayResult) {
        when (result) {
            PlayResult.FocusLost -> toast(context.getString(R.string.macro_stopped_focus_lost))
            is PlayResult.Refused -> toast(
                result.detail?.let { context.getString(R.string.macro_could_not_run, it) }
                    ?: context.getString(R.string.macro_could_not_run_unknown),
            )
            is PlayResult.LaunchFailed -> toast(
                context.getString(R.string.macro_could_not_run, result.cause.toUserMessage(R.string.error_launch_failed).resolve(context)),
            )
            PlayResult.Completed, PlayResult.Stopped -> Unit
        }
        testReturn?.let {
            testReturn = null
            ui = it
        }
        render()
    }

    private companion object {
        const val COUNTDOWN_FROM = 3
    }
}
