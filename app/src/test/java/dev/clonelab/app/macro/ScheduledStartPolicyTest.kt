package dev.clonelab.app.macro

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledStartPolicyTest {
    private fun may(interactive: Boolean = true, locked: Boolean = false, draw: Boolean = true, focused: Boolean = false) =
        ScheduledStartPolicy.mayStart(interactive, locked, draw, focused)

    @Test
    fun startsWhenThePhoneIsInUseAndTheCloneCanBeBroughtUp() {
        assertTrue(may())
    }

    @Test
    fun neverWithTheScreenOffOrLocked() {
        assertFalse(may(interactive = false))
        assertFalse(may(locked = true))
        assertFalse(may(interactive = false, locked = true, focused = true))
    }

    @Test
    fun withoutTheOverlayPermissionOnlyWhenTheCloneIsAlreadyInFront() {
        assertFalse(may(draw = false, focused = false))
        assertTrue(may(draw = false, focused = true))
    }
}
