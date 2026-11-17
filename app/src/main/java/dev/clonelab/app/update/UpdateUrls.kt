package dev.clonelab.app.update

import java.net.URI

/** Which URLs an update may be fetched from: https on GitHub's own hosts only. */
object UpdateUrls {
    const val LATEST_RELEASE_API = "https://api.github.com/repos/Howard-Starfield/Multi-Apk/releases/latest"

    fun isAllowedDownload(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = try {
            URI(url.trim())
        } catch (e: Exception) {
            return false
        }
        if (uri.scheme != "https" || uri.rawUserInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        val host = uri.host?.lowercase() ?: return false
        return host == "github.com" || host.endsWith(".githubusercontent.com")
    }
}
