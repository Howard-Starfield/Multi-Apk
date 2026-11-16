package dev.clonelab.app.keepalive

import android.content.Intent

/** Where phone makers hide their "auto start" screens. Component names are best effort and vary by ROM version. */
object VendorSettings {
    data class Target(val packageName: String, val className: String)

    private val xiaomi = listOf(
        Target("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
    )
    private val huawei = listOf(
        Target("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        Target("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
        Target("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
    )
    private val oppo = listOf(
        Target("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        Target("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        Target("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
    )
    private val oneplus = listOf(
        Target("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
    )
    private val vivo = listOf(
        Target("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        Target("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        Target("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
    )
    private val samsung = listOf(
        Target("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        Target("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.battery.BatteryActivity"),
    )

    /** Targets in priority order; empty for an unknown manufacturer. */
    fun autoStartTargets(manufacturer: String): List<Target> {
        val name = manufacturer.trim().lowercase()
        return when {
            name in setOf("xiaomi", "redmi", "poco") -> xiaomi
            name in setOf("huawei", "honor") -> huawei
            name == "oneplus" -> oneplus + oppo
            name in setOf("oppo", "realme") -> oppo + oneplus
            name in setOf("vivo", "iqoo") -> vivo
            name == "samsung" -> samsung
            else -> emptyList()
        }
    }

    fun autoStartIntents(manufacturer: String): List<Intent> = autoStartTargets(manufacturer).map {
        Intent().apply {
            setClassName(it.packageName, it.className)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
