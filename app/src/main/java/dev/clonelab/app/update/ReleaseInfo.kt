package dev.clonelab.app.update

/** The newest GitHub release as far as the updater cares. [apkSize] is the asset's size in bytes. */
data class ReleaseInfo(
    val tag: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val apkName: String,
    val apkSize: Long,
    val apkUrl: String,
) {
    /** The version without a leading `v`, e.g. "0.2.0". */
    val version: String get() = tag.trim().removePrefix("v").removePrefix("V")

    /** Safe as a file name: only letters, digits, dot, dash, underscore. */
    val fileName: String get() = "CloneLab-" + version.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".apk"

    /** The first [limit] characters of the release notes, with an ellipsis when cut. */
    fun notesPreview(limit: Int = NOTES_LIMIT): String {
        val text = notes.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (text.length <= limit) return text
        var end = limit
        if (Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end).trimEnd() + "…"
    }

    companion object {
        const val NOTES_LIMIT = 1500
    }
}
