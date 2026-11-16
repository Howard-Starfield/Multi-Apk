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
 */
class RecorderHook(hostFilesDir: File) {
    private val files = RecordingFiles(hostFilesDir)
    private val wrapped: MutableSet<Window> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    private val writer by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "macro-recorder").apply { isDaemon = true } }
    }

    /** Flag file identity and parse result; the file is only re-read when it changes. */
    private data class Stamp(val lastModified: Long, val length: Long)
    private var stamp: Stamp? = null
    private var cachedFlag: RecordingFlag? = null

    /** Call from the main thread when [activity] resumes. Never throws. */
    fun install(activity: Activity, packageName: String, userId: Int) {
        try {
            val window = activity.window ?: return
            if (!wrapped.add(window)) return
            val original = window.callback ?: return
            if (original is RecorderWindowCallback) return
            window.callback = RecorderWindowCallback(original, this, packageName, userId)
        } catch (e: Exception) {
            Log.w(TAG, "Could not hook the window", e)
        }
    }

    /** @return the session to write to when recording is on for this clone right now, else null. */
    @Synchronized
    fun activeSession(packageName: String, userId: Int): String? {
        val flagFile = files.flagFile
        if (!flagFile.exists()) {
            stamp = null
            cachedFlag = null
            return null
        }
        val now = Stamp(flagFile.lastModified(), flagFile.length())
        if (now != stamp) {
            cachedFlag = files.readFlag()
            stamp = now
        }
        return cachedFlag?.takeIf { it.packageName == packageName && it.userId == userId }?.session
    }

    /** Queues one line for the single writer thread, which keeps the order. */
    fun append(session: String, line: String) {
        writer.execute {
            try {
                files.dir.mkdirs()
                FileOutputStream(files.sessionFile(session), true).use { it.write((line + "\n").toByteArray()) }
            } catch (e: Exception) {
                Log.w(TAG, "Could not record an event", e)
            }
        }
    }

    private companion object {
        const val TAG = "RecorderHook"
    }
}
