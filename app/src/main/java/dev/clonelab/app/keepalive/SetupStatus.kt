package dev.clonelab.app.keepalive

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * What clones need from the phone, in the order it is asked for: the runtime permissions they use
 * through CloneLab, then the settings that let them run (and the switcher show) in the background.
 */
enum class SetupStep { APP_PERMISSIONS, NOTIFICATIONS, BATTERY, OVERLAY, AUTO_START }

/** Which sentence the Home banner shows. */
enum class SetupBanner { PERMISSIONS_ONLY, BACKGROUND_ONLY, BOTH }

data class SetupStatus(
    val appPermissions: Boolean,
    val notifications: Boolean,
    val battery: Boolean,
    val overlay: Boolean,
    val autoStart: Boolean,
) {
    /** Steps still to do, in the order the setup flow walks through them. */
    fun missing(): List<SetupStep> = buildList {
        if (!appPermissions) add(SetupStep.APP_PERMISSIONS)
        if (!notifications) add(SetupStep.NOTIFICATIONS)
        if (!battery) add(SetupStep.BATTERY)
        if (!overlay) add(SetupStep.OVERLAY)
        if (!autoStart) add(SetupStep.AUTO_START)
    }

    val complete: Boolean get() = missing().isEmpty()

    /** The banner text to show, or null when nothing is missing. */
    fun banner(): SetupBanner? {
        val missing = missing()
        if (missing.isEmpty()) return null
        val background = missing.any { it != SetupStep.APP_PERMISSIONS }
        return when {
            !background -> SetupBanner.PERMISSIONS_ONLY
            appPermissions -> SetupBanner.BACKGROUND_ONLY
            else -> SetupBanner.BOTH
        }
    }

    companion object {
        /**
         * Clones run inside CloneLab, so they only get storage, camera and microphone if CloneLab itself
         * holds these runtime permissions. All of them are declared in the merged manifest.
         */
        val CLONE_PERMISSIONS = listOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )

        /** Auto-start cannot be detected: it counts as done once visited, or when this maker has no such page. */
        fun autoStartDone(visited: Boolean, hasVendorPage: Boolean): Boolean = visited || !hasVendorPage
    }
}

object SetupStatusReader {
    /** The [SetupStatus.CLONE_PERMISSIONS] not granted yet; always empty below Android 6 (granted at install). */
    fun missingClonePermissions(context: Context): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptyList()
        return SetupStatus.CLONE_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    fun read(context: Context): SetupStatus {
        val app = context.applicationContext
        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryIgnored = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            power.isIgnoringBatteryOptimizations(app.packageName)
        val canDraw = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(app)
        return SetupStatus(
            appPermissions = missingClonePermissions(app).isEmpty(),
            notifications = NotificationManagerCompat.from(app).areNotificationsEnabled(),
            battery = batteryIgnored,
            overlay = canDraw,
            autoStart = SetupStatus.autoStartDone(
                visited = KeepAlivePrefs(app).autoStartVisited,
                hasVendorPage = VendorSettings.autoStartTargets(Build.MANUFACTURER).isNotEmpty(),
            ),
        )
    }
}
