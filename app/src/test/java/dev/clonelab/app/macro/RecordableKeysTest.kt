package dev.clonelab.app.macro

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordableKeysTest {
    @Test
    fun navigationAndHardwareKeysAreRecordable() {
        val keys = listOf(
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SEARCH,
        )
        keys.forEach { assertTrue("key $it", RecordableKeys.isRecordable(it)) }
    }

    @Test
    fun lettersDigitsAndSymbolsAreNeverRecorded() {
        // KEYCODE_0..9 = 7..16, KEYCODE_A..Z = 29..54, then the symbol keys up to KEYCODE_PLUS (81)
        val typed = (KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) + (KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) +
            listOf(
                KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_MINUS,
                KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_AT, KeyEvent.KEYCODE_POUND, KeyEvent.KEYCODE_STAR,
                KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_SLASH, KeyEvent.KEYCODE_NUMPAD_0, KeyEvent.KEYCODE_NUMPAD_9,
                KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_SEMICOLON, KeyEvent.KEYCODE_APOSTROPHE,
            )
        typed.forEach { assertFalse("key $it", RecordableKeys.isRecordable(it)) }
        assertEquals(26, (KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z).count())
    }
}
