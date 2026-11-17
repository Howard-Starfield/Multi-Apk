package dev.clonelab.app.ui.selection

import dev.clonelab.app.R
import dev.clonelab.app.model.CloneId
import dev.clonelab.app.ui.UserMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionTest {

    @Test
    fun toggleAddsAndRemoves() {
        val one = Selection<Int>().toggle(1)
        assertTrue(one.isActive)
        assertTrue(1 in one)
        val none = one.toggle(1)
        assertFalse(none.isActive)
        assertEquals(0, none.size)
    }

    @Test
    fun selectAllSelectsEverything() {
        val all = Selection<Int>().toggle(2).selectAll(listOf(1, 2, 3))
        assertEquals(setOf(1, 2, 3), all.selected)
        assertEquals(3, all.size)
    }

    @Test
    fun retainOnlyDropsMissingIds() {
        val selection = Selection(setOf(1, 2, 3)).retainOnly(listOf(2, 3, 4))
        assertEquals(setOf(2, 3), selection.selected)
        assertEquals(Selection<Int>(), Selection(setOf(1)).retainOnly(emptyList()))
    }

    @Test
    fun afterBatchKeepsOnlyTheFailed() {
        val selection = Selection(setOf(1, 2, 3)).afterBatch(listOf(2))
        assertEquals(setOf(2), selection.selected)
        assertFalse(Selection(setOf(1, 2)).afterBatch(emptyList()).isActive)
    }

    @Test
    fun clearEmpties() {
        assertFalse(Selection(setOf(1, 2)).clear().isActive)
    }

    @Test
    fun removeAllContinuesPastFailuresAndExceptions() = runBlocking {
        val progress = mutableListOf<Pair<Int, Int>>()
        val result = removeAll(listOf(1, 2, 3, 4), { done, total -> progress += done to total }) {
            when (it) {
                2 -> false
                3 -> throw IllegalStateException("boom")
                else -> true
            }
        }
        assertEquals(listOf(1, 4), result.removed)
        assertEquals(listOf(2, 3), result.failed)
        assertEquals(listOf(1 to 4, 2 to 4, 3 to 4, 4 to 4), progress)
    }

    @Test
    fun removeAllOfNothingIsEmpty() = runBlocking {
        val result = removeAll(emptyList<Int>()) { true }
        assertTrue(result.removed.isEmpty() && result.failed.isEmpty())
    }

    @Test
    fun summaryMessages() {
        assertNull(BatchResult<Int>(emptyList(), emptyList()).toMessage())
        assertEquals(UserMessage(R.string.selection_uninstalled_one), BatchResult(listOf(1), emptyList<Int>()).toMessage())
        assertEquals(
            UserMessage(R.string.selection_uninstalled_many, listOf(3)),
            BatchResult(listOf(1, 2, 3), emptyList<Int>()).toMessage(),
        )
        assertEquals(
            UserMessage(R.string.selection_uninstalled_partial, listOf(2, 1)),
            BatchResult(listOf(1, 2), listOf(3)).toMessage(),
        )
    }

    @Test
    fun stateRemovesSelectedClonesKeepsFailedAndFreezesWhileRemoving() = runBlocking {
        val state = SelectionState<String>()
        val a0 = CloneId("a", 0)
        val a1 = CloneId("a", 1)
        val b0 = CloneId("b", 0)
        val byApp = mapOf("a" to listOf(a0, a1), "b" to listOf(b0))
        state.selectAll(listOf("a", "b"))

        val result = state.removeSelected({ byApp.getValue(it) }, { it.packageName }) { it != a1 }

        assertEquals(listOf(a0, b0), result!!.removed)
        assertEquals(listOf(a1), result.failed)
        // App "a" still has a clone that could not go; "b" is gone from the selection.
        assertEquals(setOf("a"), state.selection.value.selected)
        assertFalse(state.removing.value)
        state.toggle("a")
        assertFalse(state.selection.value.isActive)
    }

    @Test
    fun stateWithNothingSelectedDoesNothing() = runBlocking {
        val state = SelectionState<CloneId>()
        assertNull(state.removeSelected({ listOf(it) }, { it }) { true })
    }
}
