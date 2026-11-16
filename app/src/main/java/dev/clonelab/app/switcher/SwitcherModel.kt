package dev.clonelab.app.switcher

import dev.clonelab.app.model.CloneId
import dev.clonelab.app.model.CloneRecord

enum class SwitcherState { FOCUSED, RUNNING, IDLE }

data class SwitcherItem(val id: CloneId, val number: Int, val label: String, val state: SwitcherState)

object SwitcherModel {
    /**
     * Entries of the pill: every record of the focused clone's package, in the store's sort order.
     * Empty when nothing is focused (or the focused clone is unknown). [labelOf] lets the caller use
     * APK-derived labels; the default is the stored display name.
     */
    fun items(
        records: List<CloneRecord>,
        focused: CloneId?,
        running: Set<CloneId>,
        labelOf: (CloneRecord) -> String = { it.displayName },
    ): List<SwitcherItem> {
        if (focused == null) return emptyList()
        val samePackage = records
            .filter { it.id.packageName == focused.packageName }
            .sortedWith(compareBy({ it.sortOrder }, { it.createdAt }))
        if (samePackage.none { it.id == focused }) return emptyList()
        return samePackage.map { record ->
            val state = when {
                record.id == focused -> SwitcherState.FOCUSED
                record.id in running -> SwitcherState.RUNNING
                else -> SwitcherState.IDLE
            }
            SwitcherItem(record.id, record.number, labelOf(record), state)
        }
    }
}

/**
 * Smooths the focus signal: a new focused clone shows at once, but the pill only hides after
 * [missesToHide] consecutive polls without a focused clone (the engine reports null briefly while
 * a clone switches activities).
 */
class FocusDebouncer(private val missesToHide: Int = 2) {
    private var current: CloneId? = null
    private var misses = 0

    /** @return the clone the pill should be shown for, or null to hide it. */
    fun onPoll(focused: CloneId?): CloneId? {
        if (focused != null) {
            current = focused
            misses = 0
        } else if (current != null && ++misses >= missesToHide) {
            current = null
            misses = 0
        }
        return current
    }

    fun reset() {
        current = null
        misses = 0
    }
}

/** Window geometry rules of the pill, kept free of Android types so they can be tested. */
object SwitcherGeometry {
    const val EDGE_LEFT = 0
    const val EDGE_RIGHT = 1

    /** The screen edge nearest to the pill's horizontal centre. */
    fun nearestEdge(centerX: Float, screenWidth: Int): Int =
        if (centerX < screenWidth / 2f) EDGE_LEFT else EDGE_RIGHT

    fun xFor(edge: Int, viewWidth: Int, screenWidth: Int): Int =
        if (edge == EDGE_LEFT) 0 else (screenWidth - viewWidth).coerceAtLeast(0)

    /** Keeps the whole view on screen vertically; a view taller than the screen sits at the top. */
    fun clampY(y: Int, viewHeight: Int, screenHeight: Int): Int =
        y.coerceIn(0, (screenHeight - viewHeight).coerceAtLeast(0))

    /** Rows shown at once: all of them up to [maxRows]; clones are laid out [columns] per row. */
    fun visibleRows(count: Int, columns: Int = 2, maxRows: Int = 5): Int {
        if (count <= 0) return 0
        return minOf((count + columns - 1) / columns, maxRows)
    }
}
