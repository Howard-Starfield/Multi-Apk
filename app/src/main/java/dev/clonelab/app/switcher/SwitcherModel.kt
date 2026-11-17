package dev.clonelab.app.switcher

import dev.clonelab.app.model.CloneId
import dev.clonelab.app.data.CloneItem
import dev.clonelab.app.model.CloneRecord
import dev.clonelab.app.ui.home.groupByApp

enum class SwitcherState { FOCUSED, RUNNING, IDLE }

/** One cell of the pill's grid: a cloned app (Apps level) or a clone (Clones level). */
sealed interface SwitcherCell

data class SwitcherItem(val id: CloneId, val number: Int, val label: String, val state: SwitcherState) : SwitcherCell

/** One cloned app at the Apps level; [representative] is the clone whose app icon is shown. */
data class SwitcherAppItem(
    val packageName: String,
    val label: String,
    val count: Int,
    val anyRunning: Boolean,
    val representative: CloneRecord,
) : SwitcherCell

/** What the pill shows. */
sealed interface SwitcherView {
    data object Hidden : SwitcherView

    /** One entry per cloned app. */
    data class Apps(val apps: List<SwitcherAppItem>) : SwitcherView

    /** The clones of [packageName]. [showMacros]: a clone of this very app is focused, so Macros apply to it. */
    data class Clones(val packageName: String, val items: List<SwitcherItem>, val showMacros: Boolean) : SwitcherView
}

enum class NavLevel {
    /** No choice made: Clones of the focused clone's app, or Apps inside CloneLab. */
    DEFAULT,
    APPS,
    APP,
}

/**
 * What the user chose in the pill. It only counts while the context it was made in lasts: [anchor] is the
 * focused clone at that time (null inside CloneLab); when the focus moves, the choice is dropped.
 */
data class SwitcherNav(val anchor: CloneId? = null, val level: NavLevel = NavLevel.DEFAULT, val packageName: String? = null) {
    companion object {
        val NONE = SwitcherNav()

        fun drill(anchor: CloneId?, packageName: String) = SwitcherNav(anchor, NavLevel.APP, packageName)

        fun back(anchor: CloneId?) = SwitcherNav(anchor, NavLevel.APPS)
    }
}

data class SwitcherReduction(val view: SwitcherView, val nav: SwitcherNav)

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
        if (records.none { it.id == focused }) return emptyList()
        return clonesOf(focused.packageName, records, focused, running, labelOf)
    }

    private fun clonesOf(
        packageName: String,
        records: List<CloneRecord>,
        focused: CloneId?,
        running: Set<CloneId>,
        labelOf: (CloneRecord) -> String,
    ): List<SwitcherItem> = records
        .filter { it.id.packageName == packageName }
        .sortedWith(compareBy({ it.sortOrder }, { it.createdAt }))
        .map { record ->
            val state = when {
                record.id == focused -> SwitcherState.FOCUSED
                record.id in running -> SwitcherState.RUNNING
                else -> SwitcherState.IDLE
            }
            SwitcherItem(record.id, record.number, labelOf(record), state)
        }

    /** One entry per app, grouped and ordered like the Home screen ([groupByApp]). */
    fun apps(
        records: List<CloneRecord>,
        running: Set<CloneId>,
        appLabelOf: (packageName: String, stored: String) -> String = { _, stored -> stored },
    ): List<SwitcherAppItem> =
        groupByApp(records.map { CloneItem(it, installed = true, running = it.id in running) }).map {
            SwitcherAppItem(it.packageName, appLabelOf(it.packageName, it.label), it.cloneCount, it.anyRunning, it.representative)
        }

    /**
     * Decides what the pill shows and the navigation state to keep.
     *
     * Hidden when there are no clones, or when neither a clone is focused nor CloneLab is in front. A focused
     * clone wins over "CloneLab in front". The choice in [nav] is dropped when the focused clone changed since
     * it was made or the pill was hidden in between; an app that lost its last clone falls back to Apps.
     */
    fun reduce(
        records: List<CloneRecord>,
        running: Set<CloneId>,
        focused: CloneId?,
        inCloneLab: Boolean,
        nav: SwitcherNav,
        labelOf: (CloneRecord) -> String = { it.displayName },
        appLabelOf: (packageName: String, stored: String) -> String = { _, stored -> stored },
    ): SwitcherReduction {
        val anchor = focused?.takeIf { f -> records.any { it.id == f } }
        if (records.isEmpty() || (anchor == null && !inCloneLab)) return SwitcherReduction(SwitcherView.Hidden, SwitcherNav.NONE)
        val current = if (nav.anchor == anchor) nav else SwitcherNav(anchor)
        fun clones(packageName: String) = SwitcherView.Clones(
            packageName,
            clonesOf(packageName, records, anchor, running, labelOf),
            showMacros = anchor != null && anchor.packageName == packageName,
        )
        val apps = { SwitcherView.Apps(apps(records, running, appLabelOf)) }
        return when (current.level) {
            NavLevel.APPS -> SwitcherReduction(apps(), current)
            NavLevel.APP -> {
                val pkg = current.packageName
                if (pkg != null && records.any { it.id.packageName == pkg }) {
                    SwitcherReduction(clones(pkg), current)
                } else {
                    SwitcherReduction(apps(), SwitcherNav.back(anchor))
                }
            }
            NavLevel.DEFAULT ->
                if (anchor != null) SwitcherReduction(clones(anchor.packageName), current) else SwitcherReduction(apps(), current)
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
