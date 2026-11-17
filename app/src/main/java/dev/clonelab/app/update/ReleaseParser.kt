package dev.clonelab.app.update

import org.json.JSONException
import org.json.JSONObject

/** What the body of GitHub's "latest release" call turned out to be. */
sealed interface ParsedRelease {
    data class Usable(val info: ReleaseInfo) : ParsedRelease

    /** A valid release that must not be offered: draft, prerelease, or without an `.apk` asset. */
    object Ignored : ParsedRelease

    /** Not JSON, or without a tag. */
    object Malformed : ParsedRelease
}

object ReleaseParser {

    fun parse(json: String): ParsedRelease {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            return ParsedRelease.Malformed
        }
        val tag = root.text("tag_name")
        if (tag.isBlank()) return ParsedRelease.Malformed
        if (root.optBoolean("draft", false) || root.optBoolean("prerelease", false)) return ParsedRelease.Ignored
        val assets = root.optJSONArray("assets") ?: return ParsedRelease.Ignored
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.text("name")
            val url = asset.text("browser_download_url")
            if (!name.endsWith(".apk", ignoreCase = true) || url.isBlank()) continue
            return ParsedRelease.Usable(
                ReleaseInfo(
                    tag = tag,
                    title = root.text("name"),
                    notes = root.text("body"),
                    pageUrl = root.text("html_url"),
                    apkName = name,
                    apkSize = asset.optLong("size", -1L),
                    apkUrl = url,
                ),
            )
        }
        return ParsedRelease.Ignored
    }

    /** A string field, "" when missing or JSON null (org.json would otherwise return the text "null"). */
    private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key, "")
}
