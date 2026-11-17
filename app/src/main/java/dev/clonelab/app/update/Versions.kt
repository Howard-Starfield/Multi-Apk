package dev.clonelab.app.update

/** Release tags such as `v0.1.0`, `0.1.0` or `0.1.0-beta` as numeric parts, compared against the installed version. */
object Versions {
    private const val MAX_PARTS = 4

    /** The numeric parts of [tag] (a leading `v` and any `-suffix` / `+build` are ignored), or null if malformed. */
    fun parse(tag: String?): List<Int>? {
        var text = tag?.trim() ?: return null
        if (text.startsWith("v") || text.startsWith("V")) text = text.substring(1)
        text = text.substringBefore('-').substringBefore('+')
        val parts = text.split('.')
        if (parts.size > MAX_PARTS) return null
        return parts.map { part ->
            if (part.isEmpty() || !part.all { it in '0'..'9' }) return null
            part.toIntOrNull() ?: return null
        }
    }

    /** 0 when equal; missing trailing parts count as zero (`1.0` equals `1.0.0`). */
    fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val diff = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    /** Strictly newer. A malformed [tag] or [installed] is never newer. */
    fun isNewer(tag: String?, installed: String?): Boolean {
        val candidate = parse(tag) ?: return false
        val current = parse(installed) ?: return false
        return compare(candidate, current) > 0
    }
}
