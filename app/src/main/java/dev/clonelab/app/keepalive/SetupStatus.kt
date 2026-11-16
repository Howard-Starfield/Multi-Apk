package dev.clonelab.app.keepalive

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/** The settings that let clones run (and the switcher show) in the background, in the order they are asked for. */
enum class SetupStep { NOTIFICATIONS, BATTERY, OVERLAY, AUTO_START }

data class SetupStatus(
    val notifications: Boolean,
    val battery: Boolean,
    val overlay: Boolean,
    val autoStart: Boolean,
) {
    /** Steps still to do, in the order the setup flow walks through them. */
    fun missing(): List<SetupStep> = buildList {
        if (!notifications) add(SetupStep.NOTIFICATIONS)
        if (!battery) add(SetupStep.BATTERY)
        if (!overlay) add(SetupStep.OVERLAY)
        if (!autoStart) add(SetupStep.AUTO_START)
    }

    val complete: Boolean get() = missing().isEmpty()

    companion object {
        /** Auto-start cannot be detected: it counts as done once visited, or when this maker has no such page. */
        fun autoStartDone(visited: Boolean, hasVendorPage: Boolean): Boolean = visited || !hasVendorPage
    }
}

object SetupStatusReader {
    fun read(context: Context): SetupStatus {
        val app = context.applicationContext
        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryIgnored = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            power.isIgnoringBatteryOptimizations(app.packageName)
        val canDraw = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(app)
        return SetupStatus(
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
