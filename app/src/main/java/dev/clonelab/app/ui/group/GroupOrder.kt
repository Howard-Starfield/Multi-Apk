package dev.clonelab.app.ui.group

import dev.clonelab.app.model.CloneId

/**
 * The full clone order after the clones of [packageName] were rearranged to [groupOrder]: the app's
 * clones are refilled into the positions its clones already held, so other apps keep their place.
 * An order that is not a permutation of the app's current clones changes nothing.
 */
fun mergeGroupOrder(all: List<CloneId>, packageName: String, groupOrder: List<CloneId>): List<CloneId> {
    val current = all.filter { it.packageName == packageName }
    if (groupOrder.size != current.size || groupOrder.toSet() != current.toSet()) return all
    val next = groupOrder.iterator()
    return all.map { if (it.packageName == packageName) next.next() else it }
}
