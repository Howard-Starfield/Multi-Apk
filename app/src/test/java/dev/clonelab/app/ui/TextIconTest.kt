package dev.clonelab.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TextIconTest {
    @Test
    fun usesFirstLetterUpperCased() {
        assertEquals("W", TextIcon.letter("whatsapp 2"))
        assertEquals("W2", TextIcon.text("whatsapp 2", 2))
    }

    @Test
    fun skipsLeadingSpacesAndSymbols() {
        assertEquals("T", TextIcon.letter("  - Telegram"))
    }

    @Test
    fun emptyOrSymbolOnlyNameFallsBack() {
        assertEquals("?", TextIcon.letter(""))
        assertEquals("?", TextIcon.letter("   "))
        assertEquals("?", TextIcon.letter("!!!"))
        assertEquals("?3", TextIcon.text("", 3))
    }

    @Test
    fun keepsNonLatinLetters() {
        assertEquals("微", TextIcon.letter("微信 1"))
        assertEquals("Я", TextIcon.letter("яндекс"))
    }

    @Test
    fun keepsLettersOutsideTheBasicPlane() {
        assertEquals("𝐀", TextIcon.letter("𝐀bc"))
    }

    @Test
    fun digitCanBeTheLetter() {
        assertEquals("9", TextIcon.letter("9GAG"))
    }
}
