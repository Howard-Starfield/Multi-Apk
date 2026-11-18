package dev.clonelab.app.tools.export

import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

private const val BUFFER = 64 * 1024

/** One file of an .apks archive: its entry name and how to read it. */
class ZipSource(val name: String, val open: () -> InputStream)

/**
 * Entry names for an .apks archive of [paths] (base APK first): "base.apk", then each split under its own
 * file name. Names are unique ignoring case; a clash gets "_2", "_3" before the extension.
 */
fun zipEntryNames(paths: List<String>): List<String> {
    val used = mutableSetOf<String>()
    return paths.mapIndexed { index, path ->
        val base = if (index == 0) "base.apk" else path.substringAfterLast('/').ifBlank { "split$index.apk" }
        var name = base
        var n = 2
        while (!used.add(name.lowercase(Locale.ROOT))) {
            val dot = base.lastIndexOf('.')
            name = if (dot > 0) base.substring(0, dot) + "_" + n + base.substring(dot) else base + "_" + n
            n++
        }
        name
    }
}

/**
 * Writes [sources] as a zip to [out], uncompressed (APKs are compressed already), reporting each chunk's
 * size to [onBytes]. Leaves [out] open. Cancellable between chunks.
 */
suspend fun writeZip(sources: List<ZipSource>, out: OutputStream, onBytes: (Int) -> Unit = {}) {
    val zip = ZipOutputStream(out)
    zip.setLevel(0)
    for (source in sources) {
        zip.putNextEntry(ZipEntry(source.name))
        source.open().use { copyStream(it, zip, onBytes) }
        zip.closeEntry()
    }
    zip.finish()
}

/** Copies [input] to [output], reporting each chunk's size to [onBytes]. Cancellable between chunks. */
suspend fun copyStream(input: InputStream, output: OutputStream, onBytes: (Int) -> Unit = {}) {
    val buffer = ByteArray(BUFFER)
    while (true) {
        coroutineContext.ensureActive()
        val read = input.read(buffer)
        if (read < 0) return
        output.write(buffer, 0, read)
        onBytes(read)
    }
}
