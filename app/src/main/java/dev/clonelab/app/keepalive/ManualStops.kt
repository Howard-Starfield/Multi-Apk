package dev.clonelab.app.keepalive

import dev.clonelab.app.model.CloneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Clones the user stopped on purpose (Stop, Clear data). The watchdog leaves them alone until the user
 * launches them again. Memory only: it is main-process state and does not survive process death.
 */
object ManualStops {
    private val stopped: MutableSet<CloneId> = ConcurrentHashMap.newKeySet()

    fun add(id: CloneId) {
        stopped.add(id)
    }

    fun remove(id: CloneId) {
        stopped.remove(id)
    }

    fun contains(id: CloneId): Boolean = id in stopped

    fun clearAll() = stopped.clear()
}
