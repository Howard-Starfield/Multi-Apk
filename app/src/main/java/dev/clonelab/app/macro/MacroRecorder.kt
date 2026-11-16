package dev.clonelab.app.macro

import dev.clonelab.app.model.CloneId
import java.io.File
import java.util.UUID

/**
 * Main-process side of recording: sets and clears the flag file the clone's [RecorderHook] watches,
 * and turns the session file it wrote into steps. Methods block briefly on file work: call them from a
 * background thread.
 */
class MacroRecorder(baseDir: File) {
    private val files = RecordingFiles(baseDir)
    private var session: String? = null
    private var target: CloneId? = null

    val isRecording: Boolean @Synchronized get() = session != null

    /** Starts a session for [target] and returns its id. Any earlier session is discarded. */
    @Synchronized
    fun start(target: CloneId): String {
        discard()
        val id = UUID.randomUUID().toString()
        files.sessionFile(id).apply { parentFile?.mkdirs() }.writeText("")
        files.writeFlag(RecordingFlag(id, target.packageName, target.userId))
        session = id
        this.target = target
        return id
    }

    /**
     * Ends the session, gives the clone's writer up to [FLUSH_WAIT_MS] to finish, and returns the
     * classified steps (empty when no session was running). The session file is deleted.
     */
    @Synchronized
    fun stop(tapSlopPx: Float): List<MacroStep> {
        val id = session ?: return emptyList()
        session = null
        target = null
        files.deleteFlag()
        val file = files.sessionFile(id)
        try {
            awaitFlush(file)
            val events = if (file.exists()) file.readLines().mapNotNull(::parseRawLine) else emptyList()
            return GestureClassifier.classify(events, tapSlopPx)
        } finally {
            file.delete()
        }
    }

    @Synchronized
    fun cancel() = discard()

    /** Touches the flag so clones keep treating it as live; a no-op when no session is running. */
    @Synchronized
    fun heartbeat() {
        val id = session ?: return
        val current = target ?: return
        files.touchFlag(RecordingFlag(id, current.packageName, current.userId))
    }

    /** Removes flag and session files left behind by an earlier run of the app (or a crash or reboot). */
    @Synchronized
    fun cleanStale() {
        if (session == null) files.deleteAll()
    }

    private fun discard() {
        files.deleteFlag()
        session?.let { files.sessionFile(it).delete() }
        session = null
        target = null
    }

    /** Waits until the file stops growing, at most [FLUSH_WAIT_MS] in total. */
    private fun awaitFlush(file: File) {
        var size = file.length()
        var waited = 0L
        while (waited < FLUSH_WAIT_MS) {
            Thread.sleep(FLUSH_STEP_MS)
            waited += FLUSH_STEP_MS
            val now = file.length()
            if (now == size) return
            size = now
        }
    }

    private companion object {
        const val FLUSH_WAIT_MS = 300L
        const val FLUSH_STEP_MS = 100L
    }
}
