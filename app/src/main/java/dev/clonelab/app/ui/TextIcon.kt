package dev.clonelab.app.ui

/** What a text icon shows for a clone: the first letter of its name and its clone number. */
object TextIcon {
    private const val FALLBACK_LETTER = "?"

    /** First letter or digit of [name], upper-cased; works for any script. */
    fun letter(name: String): String {
        val trimmed = name.trim()
        var index = 0
        while (index < trimmed.length) {
            val codePoint = trimmed.codePointAt(index)
            if (Character.isLetterOrDigit(codePoint)) {
                return String(Character.toChars(codePoint)).uppercase()
            }
            index += Character.charCount(codePoint)
        }
        return FALLBACK_LETTER
    }

    fun text(name: String, number: Int): String = letter(name) + number
}
