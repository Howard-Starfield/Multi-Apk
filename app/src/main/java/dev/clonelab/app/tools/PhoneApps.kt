package dev.clonelab.app.tools

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.io.File

/** Reads the apps installed on the phone. Blocking: call it off the main thread. */
class PhoneApps(
    private val packageManager: PackageManager,
    /** CloneLab itself, which is never listed. */
    private val ownPackage: String,
) {
    fun load(): List<PhoneApp> = packageManager.getInstalledPackages(0).mapNotNull(::toApp)

    fun isInstalled(packageName: String): Boolean = try {
        packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun toApp(info: PackageInfo): PhoneApp? {
        val appInfo = info.applicationInfo ?: return null
        if (info.packageName == ownPackage) return null
        val base = appInfo.sourceDir?.takeUnless { it.isBlank() } ?: return null
        val paths = listOf(base) + appInfo.splitSourceDirs.orEmpty().filter { !it.isNullOrBlank() }
        // An updated system app counts as a user app: it was installed from a store like any other.
        val system = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
            appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
        val label = appInfo.loadLabel(packageManager).toString().takeUnless { it.isBlank() } ?: info.packageName
        return PhoneApp(
            packageName = info.packageName,
            label = label,
            versionName = info.versionName.orEmpty(),
            installTime = info.firstInstallTime,
            apkPaths = paths,
            apkBytes = paths.sumOf { File(it).length() },
            system = system,
        )
    }
}
