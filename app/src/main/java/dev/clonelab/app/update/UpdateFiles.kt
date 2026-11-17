package dev.clonelab.app.update

import android.content.Context
import java.io.File

/** Where downloaded updates live, and removing the ones that are no longer needed. */
object UpdateFiles {

    /** `cache/updates/`; the only directory the update FileProvider exposes. */
    fun dir(context: Context) = File(context.cacheDir, "updates")

    /** World-readable copies for the installer on API < 24, which has no content:// grant for installs. */
    fun legacyDir(context: Context): File? = context.getExternalFilesDir("updates")

    /**
     * Deletes everything in [dir] except a finished download of a version newer than [installedVersion]
     * (`CloneLab-<version>.apk`). Partial files and files of unreadable names always go.
     * @return how many files were deleted
     */
    fun cleanUp(dir: File?, installedVersion: String): Int {
        val files = dir?.listFiles() ?: return 0
        var deleted = 0
        for (file in files) {
            val version = FILE_NAME.matchEntire(file.name)?.groupValues?.get(1)
            val keep = file.isFile && version != null && Versions.isNewer(version, installedVersion)
            if (!keep && file.delete()) deleted++
        }
        return deleted
    }

    private val FILE_NAME = Regex("CloneLab-(.+)\\.apk")
}
