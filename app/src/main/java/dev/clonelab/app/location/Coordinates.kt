package dev.clonelab.app.location

enum class CoordinateProblem { EMPTY, NOT_A_NUMBER, LATITUDE_RANGE, LONGITUDE_RANGE, ORIGIN }

sealed interface CoordinateResult {
    data class Valid(val latitude: Double, val longitude: Double) : CoordinateResult
    data class Invalid(val problem: CoordinateProblem) : CoordinateResult
}

/** Parses and validates a fake location typed or pasted by the user. */
object Coordinates {
    private val separators = Regex("[,;\\s]+")

    /** Two separate fields. If [longitude] is blank, [latitude] may hold a pasted "lat, lng" pair. */
    fun parse(latitude: String, longitude: String): CoordinateResult =
        if (longitude.isBlank()) parsePair(latitude) else validate(latitude.trim(), longitude.trim())

    /** A pasted pair such as `48.8584, 2.2945` (comma, semicolon or space separated). */
    fun parsePair(text: String): CoordinateResult {
        val parts = text.trim().split(separators).filter { it.isNotEmpty() }
        return when {
            parts.isEmpty() -> CoordinateResult.Invalid(CoordinateProblem.EMPTY)
            parts.size != 2 -> CoordinateResult.Invalid(CoordinateProblem.NOT_A_NUMBER)
            else -> validate(parts[0], parts[1])
        }
    }

    private fun validate(latText: String, lngText: String): CoordinateResult {
        if (latText.isEmpty() || lngText.isEmpty()) return CoordinateResult.Invalid(CoordinateProblem.EMPTY)
        val lat = number(latText)
        val lng = number(lngText)
        return when {
            lat == null || lng == null -> CoordinateResult.Invalid(CoordinateProblem.NOT_A_NUMBER)
            lat < -90.0 || lat > 90.0 -> CoordinateResult.Invalid(CoordinateProblem.LATITUDE_RANGE)
            lng < -180.0 || lng > 180.0 -> CoordinateResult.Invalid(CoordinateProblem.LONGITUDE_RANGE)
            // The engine treats (0, 0) as "no location", so it cannot be used.
            lat == 0.0 && lng == 0.0 -> CoordinateResult.Invalid(CoordinateProblem.ORIGIN)
            else -> CoordinateResult.Valid(lat, lng)
        }
    }

    private fun number(text: String): Double? = text.toDoubleOrNull()?.takeIf { it.isFinite() }
}
