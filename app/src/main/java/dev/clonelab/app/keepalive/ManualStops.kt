package dev.clonelab.app.keepalive

import android.content.Context
import dev.clonelab.app.model.CloneId

/** Where [ManualStopSet] keeps its entries between process lifetimes. */
interface ManualStopBacking {
    fun load(): Set<String>
    fun save(entries: Set<String>)
}

/** Pure logic of the manual-stop set; testable with an in-memory [ManualStopBacking]. */
class ManualStopSet(private val backing: ManualStopBacking) {
    private val entries: MutableSet<String> = backing.load().toMutableSet()

    @Synchronized
    fun add(id: CloneId) {
        if (entries.add(key(id))) backing.save(entries.toSet())
    }

    @Synchronized
    fun remove(id: CloneId) {
        if (entries.remove(key(id))) backing.save(entries.toSet())
    }

    @Synchronized
    fun contains(id: CloneId): Boolean = key(id) in entries

    @Synchronized
    fun clearAll() {
        if (entries.isNotEmpty()) {
            entries.clear()
            backing.save(emptySet())
        }
    }

    private fun key(id: CloneId) = "${id.userId}|${id.packageName}"
}

class MemoryManualStopBacking : ManualStopBacking {
    private var saved: Set<String> = emptySet()
    override fun load() = saved
    override fun save(entries: Set<String>) {
        saved = entries
    }
}

private class PrefsManualStopBacking(context: Context) : ManualStopBacking {
    private val prefs = context.applicationContext.getSharedPreferences("manual_stops", Context.MODE_PRIVATE)
    override fun load(): Set<String> = prefs.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    // commit(): callers are on background threads, and the process may be killed right after a Stop.
    override fun save(entries: Set<String>) {
        prefs.edit().putStringSet(KEY, entries).commit()
    }

    private companion object {
        const val KEY = "stopped"
    }
}

/**
 * Clones the user stopped on purpose (Stop, Clear data). The watchdog leaves them alone until the user
 * launches the clone again or removes it. The set is persisted because [KeepAliveService] is sticky and
 * would otherwise restart them after process death. Main process only: call [install] before use there.
 * Until then (and in unit tests) it is held in memory.
 */
object ManualStops {
    @Volatile
    private var set = ManualStopSet(MemoryManualStopBacking())
    private var installed = false

    /** Switches to the SharedPreferences-backed set, once. */
    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true
        set = ManualStopSet(PrefsManualStopBacking(context))
    }

    fun add(id: CloneId) = set.add(id)

    fun remove(id: CloneId) = set.remove(id)

    fun contains(id: CloneId): Boolean = set.contains(id)

    fun clearAll() = set.clearAll()
}
