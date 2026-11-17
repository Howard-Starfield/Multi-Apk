package dev.clonelab.app.ui.selection

import dev.clonelab.app.R
import dev.clonelab.app.model.CloneId
import dev.clonelab.app.ui.UserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** "Uninstalled 3 clones" or "Uninstalled 2, 1 could not be removed"; null when nothing was attempted. */
fun <K> BatchResult<K>.toMessage(): UserMessage? = when {
    removed.isEmpty() && failed.isEmpty() -> null
    failed.isNotEmpty() -> UserMessage(R.string.selection_uninstalled_partial, listOf(removed.size, failed.size))
    removed.size == 1 -> UserMessage(R.string.selection_uninstalled_one)
    else -> UserMessage(R.string.selection_uninstalled_many, listOf(removed.size))
}

/**
 * The selection of one grid screen plus the "uninstalling" flag, kept in the screen's ViewModel so both
 * survive rotation. [K] is what a tile stands for (a clone, or an app on Home).
 */
class SelectionState<K> {
    private val _selection = MutableStateFlow(Selection<K>())
    private val _removing = MutableStateFlow(false)

    val selection: StateFlow<Selection<K>> = _selection

    /** True while a batch removal runs; the selection is frozen meanwhile. */
    val removing: StateFlow<Boolean> = _removing

    fun toggle(key: K) {
        if (!_removing.value) _selection.update { it.toggle(key) }
    }

    fun selectAll(all: Collection<K>) {
        if (!_removing.value) _selection.update { it.selectAll(all) }
    }

    fun clear() {
        if (!_removing.value) _selection.update { it.clear() }
    }

    fun retainOnly(existing: Collection<K>) {
        _selection.update { it.retainOnly(existing) }
    }

    /**
     * Removes every clone behind the selected keys ([cloneIds] maps a key to them) one by one with
     * [remove]. Keys with a clone that could not be removed stay selected ([keyOf] maps a clone back).
     * @return what happened, or null if busy or nothing was selected
     */
    suspend fun removeSelected(
        cloneIds: (K) -> List<CloneId>,
        keyOf: (CloneId) -> K,
        remove: suspend (CloneId) -> Boolean,
    ): BatchResult<CloneId>? {
        if (_removing.value) return null
        val targets = _selection.value.selected.flatMap(cloneIds)
        if (targets.isEmpty()) return null
        _removing.value = true
        try {
            val result = removeAll(targets, remove = remove)
            _selection.update { it.afterBatch(result.failed.map(keyOf)) }
            return result
        } finally {
            _removing.value = false
        }
    }
}
