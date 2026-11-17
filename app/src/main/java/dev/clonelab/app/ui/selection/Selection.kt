package dev.clonelab.app.ui.selection

import kotlinx.coroutines.CancellationException

/**
 * What is selected on a grid. Selection mode is simply "something is selected": deselecting the last
 * tile leaves it. Immutable; every change returns a new value.
 */
data class Selection<K>(val selected: Set<K> = emptySet()) {
    val size: Int get() = selected.size
    val isActive: Boolean get() = selected.isNotEmpty()

    operator fun contains(key: K): Boolean = key in selected

    fun toggle(key: K): Selection<K> = Selection(if (key in selected) selected - key else selected + key)

    fun selectAll(all: Collection<K>): Selection<K> = Selection(LinkedHashSet(all))

    /** Drops keys that no longer exist, e.g. clones removed from elsewhere. */
    fun retainOnly(existing: Collection<K>): Selection<K> {
        val keep = existing.toSet()
        val kept = selected.filterTo(LinkedHashSet()) { it in keep }
        return if (kept.size == selected.size) this else Selection(kept)
    }

    /** After a batch removal only the keys that could not be removed stay selected. */
    fun afterBatch(failed: Collection<K>): Selection<K> = retainOnly(failed)

    fun clear(): Selection<K> = Selection()
}

/** Outcome of removing a list of items one by one. */
data class BatchResult<K>(val removed: List<K>, val failed: List<K>)

/**
 * Runs [remove] for every item in turn and carries on after a failure (false or an exception).
 * [onProgress] gets (items handled so far, total) after each one.
 */
suspend fun <K> removeAll(
    items: List<K>,
    onProgress: (Int, Int) -> Unit = { _, _ -> },
    remove: suspend (K) -> Boolean,
): BatchResult<K> {
    val removed = mutableListOf<K>()
    val failed = mutableListOf<K>()
    items.forEachIndexed { index, item ->
        val ok = try {
            remove(item)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        (if (ok) removed else failed) += item
        onProgress(index + 1, items.size)
    }
    return BatchResult(removed, failed)
}
