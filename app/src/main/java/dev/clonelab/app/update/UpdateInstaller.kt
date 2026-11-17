package dev.clonelab.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.clonelab.app.BuildConfig
import java.io.File

/** The Android side of an update: checking the downloaded file and building the intents. Blocking calls; use off the main thread. */
class UpdateInstaller(context: Context) {
    private val context = context.applicationContext

    /**
     * The downloaded file must be CloneLab itself with a higher versionCode than the installed one.
     * (The signature is checked by the system installer, which refuses an update signed with another key.)
     */
    @Suppress("DEPRECATION")
    fun isNewerCloneLab(apk: File): Boolean {
        val info = context.packageManager.getPackageArchiveInfo(apk.path, 0) ?: return false
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        return UpdatePolicy.isNewerBuild(info.packageName, code, context.packageName, BuildConfig.VERSION_CODE.toLong())
    }

    /** True on Android 8+ when the user still has to allow CloneLab to install apps. */
    fun needsInstallPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()

    /** The settings page where "Allow from this source" is switched for CloneLab. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /**
     * The intent that opens the system installer on [apk]. API 24+: a content:// uri from the update
     * FileProvider with a read grant. Below: a file:// uri to a world-readable copy in external app storage.
     * @throws java.io.IOException if the legacy copy cannot be made
     */
    fun installIntent(apk: File): Intent {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        } else {
            Uri.fromFile(readableCopy(apk))
        }
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun readableCopy(apk: File): File {
        val dir = UpdateFiles.legacyDir(context)?.also { it.mkdirs() }
            ?: throw java.io.IOException("no external storage")
        val copy = File(dir, apk.name)
        apk.copyTo(copy, overwrite = true)
        copy.setReadable(true, false)
        return copy
    }

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}
