package dev.clonelab.app.macro

import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Contents of the flag file that tells a clone process to record. */
data class RecordingFlag(val session: String, val packageName: String, val userId: Int)

/**
 * Where the main process and the clone processes meet. Both sides build this from the HOST files
 * directory, so the paths agree: `<base>/macro/recording.json` (the flag) and
 * `<base>/macro/<session>.jsonl` (one raw event per line).
 */
class RecordingFiles(baseDir: File) {
    val dir = File(baseDir, "macro")
    val flagFile = File(dir, "recording.json")
    private val flagTemp = File(dir, "recording.json.tmp")

    fun sessionFile(session: String) = File(dir, "$session.jsonl")

    /** Written through a temp file and a rename so a clone never reads half a flag. */
    fun writeFlag(flag: RecordingFlag) {
        dir.mkdirs()
        val json = JSONObject()
            .put("session", flag.session).put("packageName", flag.packageName).put("userId", flag.userId)
        flagTemp.writeText(json.toString())
        if (!flagTemp.renameTo(flagFile)) {
            flagFile.delete()
            if (!flagTemp.renameTo(flagFile)) throw IOException("Could not write ${flagFile.name}")
        }
    }

    /** @return null when there is no flag or it is unreadable. */
    fun readFlag(): RecordingFlag? = try {
        val o = JSONObject(flagFile.readText())
        RecordingFlag(o.getString("session"), o.getString("packageName"), o.getInt("userId"))
    } catch (e: Exception) {
        null
    }

    fun deleteFlag() {
        flagFile.delete()
        flagTemp.delete()
    }

    /** Removes the flag and every session file. */
    fun deleteAll() {
        deleteFlag()
        dir.listFiles()?.filter { it.name.endsWith(".jsonl") }?.forEach { it.delete() }
    }
}
