package dev.clonelab.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed interface CheckResult {
    data class UpToDate(val version: String) : CheckResult
    data class Available(val release: ReleaseInfo, val rawJson: String) : CheckResult

    /** Offline, rate limited, server trouble or an unreadable answer. */
    object Failed : CheckResult
}

/** Asks GitHub for the latest release. Never throws; every problem is [CheckResult.Failed]. */
class UpdateChecker(private val installedVersion: String) {

    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        val body = fetch() ?: return@withContext CheckResult.Failed
        interpret(body, installedVersion)
    }

    private fun fetch(): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(UpdateUrls.LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "CloneLab")
            }
            // 403 (rate limit), 404 (no release), 5xx and anything but 200 all mean "could not check".
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        private const val TIMEOUT_MS = 10_000

        /** Pure part: what a response body means for the installed version. */
        fun interpret(json: String, installedVersion: String): CheckResult =
            when (val parsed = ReleaseParser.parse(json)) {
                ParsedRelease.Malformed -> CheckResult.Failed
                ParsedRelease.Ignored -> CheckResult.UpToDate(installedVersion)
                is ParsedRelease.Usable ->
                    if (Versions.isNewer(parsed.info.tag, installedVersion)) {
                        CheckResult.Available(parsed.info, json)
                    } else {
                        CheckResult.UpToDate(installedVersion)
                    }
            }
    }
}
