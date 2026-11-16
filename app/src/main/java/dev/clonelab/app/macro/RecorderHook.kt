package dev.clonelab.app.macro

import android.app.Activity
import android.util.Log
import android.view.Window
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * Runs inside a clone process. Wraps the window callback of every activity that resumes in a
 * [RecorderWindowCallback] (once per window), and appends the raw events it reports to the session
 * file while the main process has set the recording flag for that clone.
 *
 * [hostFilesDir] must be the HOST app's files directory as seen before the engine started
 * redirecting paths (see CloneLabApp). This class knows nothing about the engine.
 *
 * The guest's input thread never touches the disk: whether recording is on is a volatile value that a
 * daemon poller thread refreshes from the flag file, and the writer thread does the appending.
 */
class RecorderHook(hostFilesDir: File) {
    private val files = RecordingFiles(hostFilesDir)
    private val wrapped: MutableSet<Window> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    private val writer by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "macro-recorder").apply { isDaemon = true } }
    }

    /** The flag as of the last poll, when it is fresh; null otherwise. Written by the poller only. */
    @Volatile
    private var current: RecordingFlag? = null

    /** Flag file identity, so the file is only re-read when it changes. Poller thread only. */
    private var stampModified = -1L
    private var stampLength = -1L
    private var parsed: RecordingFlag? = null

    private val pollerStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Call from the main thread when [activity] resumes. Never throws. */
    fun install(activity: Activity, packageName: String, userId: Int) {
        try {
            startPoller()
            val window = activity.window ?: return
            if (!wrapped.add(window)) return
            val original = window.callback ?: return
            if (original is RecorderWindowCallback) return
            window.callback = RecorderWindowCallback(original, this, packageName, userId)
        } catch (e: Exception) {
            Log.w(TAG, "Could not hook the window", e)
        }
    }

    /**
     * @return the session to write to when recording is on for this clone right now, else null. Reads a
     * volatile only: safe on the input thread.
     */
    fun activeSession(packageName: String, userId: Int): String? =
        current?.takeIf { it.packageName == packageName && it.userId == userId }?.session

    /**
     * Started with the first hooked window, not at class load, so a clone that never shows an activity
     * costs nothing. A daemon thread (it never keeps the process alive), one stat of a small file per
     * round, no wake locks and no alarms: when the process is cached the system simply stops running it.
     */
    private fun startPoller() {
        if (!pollerStarted.compareAndSet(false, true)) return
        Thread({
            while (true) {
                try {
                    refresh()
                    Thread.sleep(POLL_MS)
                } catch (e: InterruptedException) {
                    return@Thread
                } catch (e: Exception) {
                    Log.w(TAG, "Recording poll failed", e)
                    try {
                        Thread.sleep(POLL_MS)
                    } catch (ie: InterruptedException) {
                        return@Thread
                    }
                }
            }
        }, "macro-recorder-poll").apply { isDaemon = true }.start()
    }

    private fun refresh() {
        val flagFile = files.flagFile
        val modified = flagFile.lastModified() // 0 when the file does not exist
        if (modified == 0L || !RecordingFiles.isFresh(modified, System.currentTimeMillis())) {
            current = null
            stampModified = -1L
            stampLength = -1L
            parsed = null
            return
        }
        val length = flagFile.length()
        if (modified != stampModified || length != stampLength) {
            parsed = files.readFlag()
            stampModified = modified
            stampLength = length
        }
        current = parsed
    }

    /**
     * Queues one line for the single writer thread, which keeps the order. The session file belongs to
     * the main process: it is only appended to while it exists (so a late event cannot bring a deleted
     * session back) and only up to [RecordingFiles.MAX_SESSION_BYTES].
     */
    fun append(session: String, line: String) {
        writer.execute {
            try {
                val file = files.sessionFile(session)
                if (!file.exists() || file.length() >= RecordingFiles.MAX_SESSION_BYTES) return@execute
                FileOutputStream(file, true).use { it.write((line + "\n").toByteArray()) }
            } catch (e: Exception) {
                Log.w(TAG, "Could not record an event", e)
            }
        }
    }

    private companion object {
        const val TAG = "RecorderHook"
        const val POLL_MS = 250L
    }
}
