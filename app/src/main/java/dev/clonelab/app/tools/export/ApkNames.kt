package dev.clonelab.app.tools.export

/** File names and MIME types for exported installers. */
object ApkNames {
    const val APK_MIME = "application/vnd.android.package-archive"

    /** An .apks archive is a plain zip of the base and split APKs. */
    const val APKS_MIME = "application/zip"

    private const val FALLBACK = "app"

    /** Most file systems allow 255 bytes per name; this leaves room for the version and extension. */
    private const val MAX_LABEL_BYTES = 90
    private const val MAX_VERSION_BYTES = 30

    /**
     * "<label>_<version>.<extension>", e.g. "Signal_7.20.2.apk". The label keeps letters of any script;
     * if nothing is left of it the package name is used instead.
     */
    fun fileName(label: String, versionName: String?, packageName: String, extension: String): String {
        val name = sanitize(label, MAX_LABEL_BYTES)
            .ifEmpty { sanitize(packageName, MAX_LABEL_BYTES) }
            .ifEmpty { FALLBACK }
        val version = sanitize(versionName.orEmpty(), MAX_VERSION_BYTES)
        return if (version.isEmpty()) "$name.$extension" else "${name}_$version.$extension"
    }

    /**
     * Keeps letters, digits, combining marks and a few safe punctuation characters, replaces everything
     * else with "_", cuts the result at [maxBytes] of UTF-8 without splitting a character, and trims
     * separators from both ends.
     */
    fun sanitize(text: String, maxBytes: Int): String {
        val out = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val kept = if (isKept(cp)) cp else '_'.code
            val size = utf8Size(kept)
            if (bytes + size > maxBytes) break
            bytes += size
            out.appendCodePoint(kept)
        }
        return out.toString()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("_+"), "_")
            .trim { it == ' ' || it == '.' || it == '_' || it == '-' }
    }

    private fun isKept(cp: Int): Boolean {
        if (cp in 0xFE00 until 0xFE10) return false // variation selectors: invisible, and confuse some file managers
        if (Character.isLetterOrDigit(cp)) return true
        return when (cp) {
            ' '.code, '('.code, ')'.code, '+'.code, '-'.code, '.'.code, '_'.code -> true
            else -> Character.getType(cp).toByte().let {
                it == Character.NON_SPACING_MARK || it == Character.COMBINING_SPACING_MARK
            }
        }
    }

    private fun utf8Size(cp: Int): Int = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
    }
}
