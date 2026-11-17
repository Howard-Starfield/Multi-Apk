package dev.clonelab.app.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** Downloads a release APK into [dir]. Cancel by cancelling the calling coroutine. */
class UpdateDownloader(private val dir: File) {

    /** An earlier, complete download of this release (same size), or null. */
    fun existing(release: ReleaseInfo): File? {
        val file = File(dir, release.fileName)
        return file.takeIf { it.isFile && release.apkSize > 0 && it.length() == release.apkSize }
    }

    /** @param onProgress called on the IO thread with (bytes so far, total bytes or -1). */
    suspend fun download(release: ReleaseInfo, onProgress: (Long, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            if (!dir.isDirectory && !dir.mkdirs()) throw UpdateException(UpdateException.Reason.STORAGE)
            val target = File(dir, release.fileName)
            val partial = File(dir, release.fileName + ".part")
            try {
                partial.delete()
                target.delete()
                fetch(release, partial, onProgress)
                if (release.apkSize > 0 && partial.length() != release.apkSize) {
                    throw UpdateException(UpdateException.Reason.SIZE_MISMATCH)
                }
                if (!partial.renameTo(target)) throw UpdateException(UpdateException.Reason.STORAGE)
                target
            } catch (e: Throwable) {
                // Cancellation or any failure: never leave a half file that could be mistaken for a download.
                partial.delete()
                if (e is CancellationException || e is UpdateException) throw e
                throw UpdateException(UpdateException.Reason.NETWORK, e)
            }
        }

    private suspend fun fetch(release: ReleaseInfo, into: File, onProgress: (Long, Long) -> Unit) {
        val connection = open(release.apkUrl)
        try {
            val total = if (release.apkSize > 0) release.apkSize else connection.contentLengthLong
            var done = 0L
            onProgress(0, total)
            connection.inputStream.use { input ->
                into.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        try {
                            output.write(buffer, 0, read)
                        } catch (e: IOException) {
                            throw UpdateException(UpdateException.Reason.STORAGE, e)
                        }
                        done += read
                        onProgress(done, total)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * GET with redirects followed by hand: GitHub answers with a redirect to another host, and every
     * hop is checked against [UpdateUrls.isAllowedDownload] before a connection is made to it.
     */
    private suspend fun open(startUrl: String): HttpURLConnection {
        var url = startUrl
        repeat(MAX_REDIRECTS + 1) {
            coroutineContext.ensureActive()
            if (!UpdateUrls.isAllowedDownload(url)) throw UpdateException(UpdateException.Reason.UNTRUSTED_URL)
            val connection = try {
                (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "CloneLab")
                    setRequestProperty("Accept", "application/octet-stream")
                }
            } catch (e: IOException) {
                throw UpdateException(UpdateException.Reason.NETWORK, e)
            }
            try {
                when (val code = connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> return connection
                    301, 302, 303, 307, 308 -> {
                        val location = connection.getHeaderField("Location")
                            ?: throw UpdateException(UpdateException.Reason.SERVER)
                        url = URL(URL(url), location).toString()
                        connection.disconnect()
                    }
                    else -> throw UpdateException(
                        if (code >= 400) UpdateException.Reason.SERVER else UpdateException.Reason.NETWORK,
                    )
                }
            } catch (e: IOException) {
                connection.disconnect()
                throw UpdateException(UpdateException.Reason.NETWORK, e)
            } catch (e: UpdateException) {
                connection.disconnect()
                throw e
            }
        }
        throw UpdateException(UpdateException.Reason.SERVER)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
        const val BUFFER_SIZE = 32 * 1024
        const val MAX_REDIRECTS = 5
    }
}
