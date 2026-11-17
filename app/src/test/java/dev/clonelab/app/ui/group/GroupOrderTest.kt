package dev.clonelab.app.ui.group

import dev.clonelab.app.model.CloneId
import org.junit.Assert.assertEquals
import org.junit.Test

class GroupOrderTest {
    private fun id(pkg: String, user: Int) = CloneId(pkg, user)

    @Test
    fun reordersWithinTheAppsOwnSlotsAndLeavesOthersInPlace() {
        val all = listOf(id("a", 0), id("b", 0), id("a", 1), id("c", 0), id("a", 2))
        val merged = mergeGroupOrder(all, "a", listOf(id("a", 2), id("a", 0), id("a", 1)))
        assertEquals(listOf(id("a", 2), id("b", 0), id("a", 0), id("c", 0), id("a", 1)), merged)
    }

    @Test
    fun unchangedOrderIsIdentity() {
        val all = listOf(id("a", 0), id("b", 0), id("a", 1))
        assertEquals(all, mergeGroupOrder(all, "a", listOf(id("a", 0), id("a", 1))))
    }

    @Test
    fun anOrderThatIsNotAPermutationChangesNothing() {
        val all = listOf(id("a", 0), id("b", 0), id("a", 1))
        assertEquals(all, mergeGroupOrder(all, "a", listOf(id("a", 1))))
        assertEquals(all, mergeGroupOrder(all, "a", listOf(id("a", 1), id("b", 0))))
    }
}
