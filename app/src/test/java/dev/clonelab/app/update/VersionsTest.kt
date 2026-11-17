package dev.clonelab.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {

    @Test
    fun parsesWithAndWithoutPrefixAndSuffix() {
        assertEquals(listOf(0, 1, 0), Versions.parse("v0.1.0"))
        assertEquals(listOf(0, 1, 0), Versions.parse("0.1.0"))
        assertEquals(listOf(0, 1, 0), Versions.parse("0.1.0-beta"))
        assertEquals(listOf(1, 2, 3), Versions.parse(" V1.2.3+build.5 "))
        assertEquals(listOf(2), Versions.parse("2"))
    }

    @Test
    fun malformedIsNull() {
        for (bad in listOf(null, "", "v", "abc", "1..2", "1.a.0", "1.2.3.4.5", "-1.0", ".1", "1.", "99999999999.0")) {
            assertNull("[$bad]", Versions.parse(bad))
        }
    }

    @Test
    fun newerMeansStrictlyGreater() {
        assertTrue(Versions.isNewer("v0.1.1", "0.1.0"))
        assertTrue(Versions.isNewer("0.2.0", "0.1.9"))
        assertTrue(Versions.isNewer("1.0.0", "0.99.99"))
        assertTrue(Versions.isNewer("0.10.0", "0.9.0"))
        assertFalse(Versions.isNewer("v0.1.0", "0.1.0"))
        assertFalse(Versions.isNewer("0.0.9", "0.1.0"))
    }

    @Test
    fun missingPartsCountAsZero() {
        assertFalse(Versions.isNewer("1.0", "1.0.0"))
        assertFalse(Versions.isNewer("1.0.0", "1.0"))
        assertTrue(Versions.isNewer("1.0.1", "1.0"))
    }

    @Test
    fun suffixIsIgnoredSoPrereleaseOfSameNumberIsNotNewer() {
        assertFalse(Versions.isNewer("0.1.0-beta", "0.1.0"))
        assertTrue(Versions.isNewer("0.2.0-beta", "0.1.0"))
    }

    @Test
    fun malformedTagIsNeverNewer() {
        assertFalse(Versions.isNewer("latest", "0.1.0"))
        assertFalse(Versions.isNewer(null, "0.1.0"))
        assertFalse(Versions.isNewer("v9.9.9", "garbage"))
    }
}
