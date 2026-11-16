package dev.clonelab.app.macro

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Persists macros as one JSON file, replaced atomically on every write. Main process only.
 * Robustness follows CloneStore: a bad record is skipped, an unparseable file is moved aside to
 * `<name>.corrupt`, and a missing file is recovered from the `.tmp` of an interrupted write.
 */
class MacroStore(private val file: File) {
    private val lock = Any()

    fun all(): List<Macro> = synchronized(lock) { read() }

    fun forPackage(packageName: String): List<Macro> = all().filter { it.packageName == packageName }

    fun get(id: String): Macro? = all().firstOrNull { it.id == id }

    fun put(macro: Macro) = synchronized(lock) {
        write(read().filterNot { it.id == macro.id } + macro)
    }

    fun remove(id: String) = synchronized(lock) {
        write(read().filterNot { it.id == id })
    }

    private val tempFile get() = File(file.absolutePath + ".tmp")

    private fun read(): List<Macro> {
        if (file.exists()) {
            parseFile(file)?.let { return it }
            moveAside()
        }
        return tempFile.takeIf { it.exists() }?.let(::parseFile) ?: emptyList()
    }

    /** @return the macros, or null when the file as a whole cannot be parsed. */
    private fun parseFile(source: File): List<Macro>? {
        val array = try {
            JSONObject(source.readText()).getJSONArray(KEY_MACROS)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot parse ${source.name}", e)
            return null
        }
        return (0 until array.length()).mapNotNull { index ->
            try {
                macroFromJson(array.getJSONObject(index))
            } catch (e: Exception) {
                Log.w(TAG, "Skipping unreadable macro record #$index", e)
                null
            }
        }.sortedBy { it.createdAt }
    }

    private fun moveAside() {
        try {
            var target = File(file.absolutePath + ".corrupt")
            var n = 1
            while (target.exists()) target = File(file.absolutePath + ".corrupt.${n++}")
            if (!file.renameTo(target)) {
                file.copyTo(target)
                file.delete()
            }
            Log.w(TAG, "Moved unreadable ${file.name} to ${target.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Could not move ${file.name} aside", e)
        }
    }

    private fun write(macros: List<Macro>) {
        val json = JSONObject().put(KEY_MACROS, JSONArray(macros.map(::macroToJson)))
        file.absoluteFile.parentFile?.mkdirs()
        val temp = tempFile
        temp.writeText(json.toString())
        if (!temp.renameTo(file)) {
            // Some file systems refuse to rename over an existing file.
            file.delete()
            if (!temp.renameTo(file)) throw IOException("Could not replace ${file.name}")
        }
    }

    private fun macroToJson(m: Macro) = JSONObject()
        .put("id", m.id)
        .put("name", m.name)
        .put("packageName", m.packageName)
        .put("screenWidth", m.screenWidth)
        .put("screenHeight", m.screenHeight)
        .put("steps", JSONArray(m.steps.map(::stepToJson)))
        .put("repeatCount", m.repeatCount)
        .put("repeatDelayMs", m.repeatDelayMs)
        .put("schedule", scheduleToJson(m.schedule))
        .put("scheduleUserId", m.scheduleUserId ?: JSONObject.NULL)
        .put("lastScheduledRunAt", m.lastScheduledRunAt)
        .put("createdAt", m.createdAt)

    private fun macroFromJson(o: JSONObject): Macro {
        val steps = o.getJSONArray("steps")
        return Macro(
            id = o.getString("id"),
            name = o.getString("name"),
            packageName = o.getString("packageName"),
            screenWidth = o.getInt("screenWidth"),
            screenHeight = o.getInt("screenHeight"),
            steps = (0 until steps.length()).map { stepFromJson(steps.getJSONObject(it)) },
            repeatCount = o.getInt("repeatCount"),
            repeatDelayMs = o.getLong("repeatDelayMs"),
            schedule = scheduleFromJson(o.getJSONObject("schedule")),
            scheduleUserId = if (o.isNull("scheduleUserId")) null else o.getInt("scheduleUserId"),
            lastScheduledRunAt = o.getLong("lastScheduledRunAt"),
            createdAt = o.getLong("createdAt"),
        )
    }

    private fun stepToJson(s: MacroStep) = JSONObject()
        .put("delayBeforeMs", s.delayBeforeMs)
        .put("action", actionToJson(s.action))

    private fun stepFromJson(o: JSONObject) =
        MacroStep(o.getLong("delayBeforeMs"), actionFromJson(o.getJSONObject("action")))

    private fun actionToJson(a: MacroAction): JSONObject = when (a) {
        is MacroAction.Tap -> JSONObject().put("type", "tap")
            .put("x", a.x.toDouble()).put("y", a.y.toDouble()).put("holdMs", a.holdMs)
        is MacroAction.Swipe -> JSONObject().put("type", "swipe").put(
            "points",
            JSONArray(a.points.map {
                JSONObject().put("t", it.tMs).put("x", it.x.toDouble()).put("y", it.y.toDouble())
            }),
        )
        is MacroAction.Key -> JSONObject().put("type", "key").put("keyCode", a.keyCode)
    }

    private fun actionFromJson(o: JSONObject): MacroAction = when (val type = o.getString("type")) {
        "tap" -> MacroAction.Tap(o.getDouble("x").toFloat(), o.getDouble("y").toFloat(), o.getLong("holdMs"))
        "swipe" -> {
            val points = o.getJSONArray("points")
            MacroAction.Swipe((0 until points.length()).map {
                val p = points.getJSONObject(it)
                SwipePoint(p.getLong("t"), p.getDouble("x").toFloat(), p.getDouble("y").toFloat())
            })
        }
        "key" -> MacroAction.Key(o.getInt("keyCode"))
        else -> throw IllegalArgumentException("Unknown action type $type")
    }

    private fun scheduleToJson(s: MacroSchedule): JSONObject = when (s) {
        MacroSchedule.Off -> JSONObject().put("type", "off")
        is MacroSchedule.EveryMinutes -> JSONObject().put("type", "every").put("minutes", s.minutes)
        is MacroSchedule.DailyAt -> JSONObject().put("type", "daily").put("hour", s.hour).put("minute", s.minute)
    }

    private fun scheduleFromJson(o: JSONObject): MacroSchedule = when (val type = o.getString("type")) {
        "off" -> MacroSchedule.Off
        "every" -> MacroSchedule.EveryMinutes(o.getInt("minutes"))
        "daily" -> MacroSchedule.DailyAt(o.getInt("hour"), o.getInt("minute"))
        else -> throw IllegalArgumentException("Unknown schedule type $type")
    }

    private companion object {
        const val TAG = "MacroStore"
        const val KEY_MACROS = "macros"
    }
}
