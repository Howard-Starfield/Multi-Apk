package dev.clonelab.app.ui.detail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dev.clonelab.app.model.CloneId
import java.io.File

/** Saves custom clone icons as 192 px PNGs in `filesDir/icons`. Blocks: call off the main thread. */
class IconImageStore(context: Context) {
    private val context = context.applicationContext
    private val dir get() = File(context.filesDir, "icons")

    /** @return the saved file's path, or null when the image could not be read. */
    fun save(source: Uri, id: CloneId): String? {
        val bitmap = decode(source) ?: return null
        val square = centerCropped(bitmap)
        val scaled = Bitmap.createScaledBitmap(square, SIZE, SIZE, true)
        dir.mkdirs()
        val target = File(dir, "${id.packageName}_${id.userId}.png")
        val temp = File(dir, target.name + ".tmp")
        return try {
            temp.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) return null
            }
            target.absolutePath
        } finally {
            temp.delete()
        }
    }

    fun delete(path: String?) {
        path?.let { File(it).delete() }
    }

    private fun decode(source: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(minOf(bounds.outWidth, bounds.outHeight))
        }
        context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, options) }
    } catch (e: Exception) {
        null
    }

    private fun sampleSize(shortSide: Int): Int {
        var sample = 1
        while (shortSide / (sample * 2) >= SIZE) sample *= 2
        return sample
    }

    private fun centerCropped(bitmap: Bitmap): Bitmap {
        val side = minOf(bitmap.width, bitmap.height)
        return Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
    }

    private companion object {
        const val SIZE = 192
    }
}
