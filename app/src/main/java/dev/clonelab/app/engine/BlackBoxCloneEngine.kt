package dev.clonelab.app.engine

import android.net.Uri
import dev.clonelab.app.model.CloneId
import dev.clonelab.app.model.DeviceProfile
import dev.clonelab.app.model.LocationConfig
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.entity.device.BDeviceProfile
import top.niunaijun.blackbox.entity.location.BLocation
import top.niunaijun.blackbox.entity.pm.InstallResult
import top.niunaijun.blackbox.fake.frameworks.BDeviceManager
import top.niunaijun.blackbox.fake.frameworks.BLocationManager

/** [CloneEngine] backed by the vendored BlackBox engine. Every call is guarded against engine failures. */
class BlackBoxCloneEngine : CloneEngine {

    private val core get() = BlackBoxCore.get()

    override fun awaitReady(timeoutMs: Long): Boolean = safe(false) { core.waitForServicesAvailable(timeoutMs) }

    override fun installFromInstalledApp(packageName: String, userId: Int): EngineResult =
        guarded { toResult(core.installPackageAsUser(packageName, userId)) }

    override fun installFromApk(apkUri: String, userId: Int, onPackage: (String) -> Unit): EngineResult =
        guarded {
            val result = core.installPackageAsUser(Uri.parse(apkUri), userId)
            val outcome = toResult(result)
            if (outcome is EngineResult.Ok) onPackage(result.packageName)
            outcome
        }

    override fun usedUserIds(packageName: String): Set<Int> = safe(emptySet()) {
        core.users.map { it.id }.filter { core.isInstalled(packageName, it) }.toSet()
    }

    override fun isInstalled(id: CloneId): Boolean = safe(false) { core.isInstalled(id.packageName, id.userId) }

    override fun isRunning(id: CloneId): Boolean = safe(false) { core.isPackageRunning(id.packageName, id.userId) }

    override fun launch(id: CloneId): EngineResult = guarded {
        if (core.launchApk(id.packageName, id.userId)) EngineResult.Ok
        else EngineResult.Error("${id.packageName} could not be launched. It may have no launchable activity.")
    }

    override fun stop(id: CloneId) = safe(Unit) { core.stopPackage(id.packageName, id.userId) }

    override fun clearData(id: CloneId) = safe(Unit) { core.clearPackage(id.packageName, id.userId) }

    override fun uninstall(id: CloneId) = safe(Unit) { core.uninstallPackageAsUser(id.packageName, id.userId) }

    override fun applyLocation(id: CloneId, location: LocationConfig?) = safe(Unit) {
        val manager = BLocationManager.get()
        if (location != null && location.enabled) {
            manager.setLocation(id.userId, id.packageName, BLocation(location.latitude, location.longitude))
            manager.setPattern(id.userId, id.packageName, BLocationManager.OWN_MODE)
        } else {
            manager.setPattern(id.userId, id.packageName, BLocationManager.CLOSE_MODE)
        }
    }

    override fun applyDeviceProfile(id: CloneId, profile: DeviceProfile?) = safe(Unit) {
        val manager = BDeviceManager.get()
        if (profile == null) {
            manager.clearProfile(id.userId, id.packageName)
        } else {
            manager.setProfile(id.userId, id.packageName, profile.toEngine())
        }
    }

    private fun toResult(result: InstallResult?): EngineResult = when {
        result == null -> EngineResult.Error("The engine returned no result.")
        !result.success -> EngineResult.Error(result.msg ?: "Install failed.")
        result.packageName.isNullOrBlank() -> EngineResult.Error("The engine did not report a package name.")
        else -> EngineResult.Ok
    }

    private fun DeviceProfile.toEngine() = BDeviceProfile().also {
        it.brand = brand
        it.manufacturer = manufacturer
        it.model = model
        it.device = device
        it.product = product
        it.board = board
        it.hardware = hardware
        it.fingerprint = fingerprint
        it.androidId = androidId
        it.imei = imei
        it.serial = serial
        it.wifiMac = wifiMac
    }

    private inline fun guarded(block: () -> EngineResult): EngineResult =
        try {
            block()
        } catch (t: Throwable) {
            EngineResult.Error(t.message ?: t.javaClass.simpleName)
        }

    private inline fun <T> safe(default: T, block: () -> T): T =
        try {
            block()
        } catch (t: Throwable) {
            default
        }
}
