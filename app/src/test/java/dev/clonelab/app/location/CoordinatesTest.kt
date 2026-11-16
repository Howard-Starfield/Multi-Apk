package dev.clonelab.app.location

import org.junit.Assert.assertEquals
import org.junit.Test

class CoordinatesTest {
    private fun invalid(problem: CoordinateProblem) = CoordinateResult.Invalid(problem)

    @Test
    fun parsesDecimalFields() {
        assertEquals(CoordinateResult.Valid(48.8584, 2.2945), Coordinates.parse("48.8584", "2.2945"))
        assertEquals(CoordinateResult.Valid(-33.86, -151.2), Coordinates.parse(" -33.86 ", " -151.2 "))
    }

    @Test
    fun parsesPastedPairInEitherField() {
        val expected = CoordinateResult.Valid(48.8584, 2.2945)
        assertEquals(expected, Coordinates.parse("48.8584, 2.2945", ""))
        assertEquals(expected, Coordinates.parsePair("48.8584,2.2945"))
        assertEquals(expected, Coordinates.parsePair("48.8584 2.2945"))
        assertEquals(expected, Coordinates.parsePair("48.8584; 2.2945"))
    }

    @Test
    fun acceptsBoundaries() {
        assertEquals(CoordinateResult.Valid(90.0, 180.0), Coordinates.parse("90", "180"))
        assertEquals(CoordinateResult.Valid(-90.0, -180.0), Coordinates.parse("-90", "-180"))
    }

    @Test
    fun rejectsOutOfRange() {
        assertEquals(invalid(CoordinateProblem.LATITUDE_RANGE), Coordinates.parse("90.0001", "10"))
        assertEquals(invalid(CoordinateProblem.LATITUDE_RANGE), Coordinates.parse("-91", "10"))
        assertEquals(invalid(CoordinateProblem.LONGITUDE_RANGE), Coordinates.parse("10", "180.5"))
        assertEquals(invalid(CoordinateProblem.LONGITUDE_RANGE), Coordinates.parsePair("10, -181"))
    }

    @Test
    fun rejectsOriginBecauseTheEngineTreatsItAsEmpty() {
        assertEquals(invalid(CoordinateProblem.ORIGIN), Coordinates.parse("0", "0"))
        assertEquals(invalid(CoordinateProblem.ORIGIN), Coordinates.parsePair("0, 0"))
        assertEquals(invalid(CoordinateProblem.ORIGIN), Coordinates.parsePair("-0.0, 0.0"))
        assertEquals(CoordinateResult.Valid(0.0, 1.0), Coordinates.parse("0", "1"))
    }

    @Test
    fun rejectsEmptyAndGarbage() {
        assertEquals(invalid(CoordinateProblem.EMPTY), Coordinates.parse("", ""))
        assertEquals(invalid(CoordinateProblem.EMPTY), Coordinates.parse("  ", "   "))
        assertEquals(invalid(CoordinateProblem.NOT_A_NUMBER), Coordinates.parse("abc", "10"))
        assertEquals(invalid(CoordinateProblem.NOT_A_NUMBER), Coordinates.parse("NaN", "10"))
        assertEquals(invalid(CoordinateProblem.NOT_A_NUMBER), Coordinates.parse("10", "Infinity"))
        assertEquals(invalid(CoordinateProblem.NOT_A_NUMBER), Coordinates.parsePair("1, 2, 3"))
        assertEquals(invalid(CoordinateProblem.NOT_A_NUMBER), Coordinates.parsePair("12.5"))
    }
}
