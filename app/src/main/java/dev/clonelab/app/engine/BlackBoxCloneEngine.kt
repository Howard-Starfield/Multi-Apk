package dev.clonelab.app.engine

import android.net.Uri
import android.os.SystemClock
import dev.clonelab.app.model.CloneError
import dev.clonelab.app.model.CloneException
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

    private companion object {
        const val POLL_MS = 200L
    }

    override fun isAvailable(): Boolean = safe(false) { core.isEngineAlive() }

    /** Polls for a live engine server; `waitForServicesAvailable` is not usable because it also succeeds without one. */
    override fun awaitReady(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            if (isAvailable()) return true
            if (SystemClock.elapsedRealtime() >= deadline) return false
            try {
                Thread.sleep(POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    override fun installFromInstalledApp(packageName: String, userId: Int): EngineResult =
        guarded { toResult(core.installPackageAsUser(packageName, userId)) }

    override fun installFromApk(apkUri: String, userId: Int, onPackage: (String) -> Unit): EngineResult =
        guarded {
            val result = core.installPackageAsUser(Uri.parse(apkUri), userId)
            val outcome = toResult(result)
            if (outcome is EngineResult.Ok) onPackage(result.packageName)
            outcome
        }

    override fun usedUserIds(packageName: String): Set<Int> {
        if (!isAvailable()) throw CloneException(CloneError.EngineNotRunning)
        val used = core.users.map { it.id }.filter { core.isInstalled(packageName, it) }.toSet()
        // An engine that died mid-query reads as "nothing installed"; never report that as a result.
        if (!isAvailable()) throw CloneException(CloneError.EngineNotRunning)
        return used
    }

    override fun existingUserIds(): Set<Int> {
        if (!isAvailable()) throw CloneException(CloneError.EngineNotRunning)
        val ids = core.users.map { it.id }.toSet()
        if (!isAvailable()) throw CloneException(CloneError.EngineNotRunning)
        return ids
    }

    override fun isInstalled(id: CloneId): Boolean = safe(false) { core.isInstalled(id.packageName, id.userId) }

    override fun isRunning(id: CloneId): Boolean = safe(false) { core.isPackageRunning(id.packageName, id.userId) }

    override fun focusedClone(): CloneId? = safe(null) { parseFocused(core.focusedClone) }

    override fun launch(id: CloneId): EngineResult = guarded {
        if (core.launchApk(id.packageName, id.userId)) EngineResult.Ok
        else EngineResult.Error(CloneError.NotLaunchable(id.packageName))
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

    override fun apkPath(id: CloneId): String? = safe(null) {
        core.getInstalledApplications(0, id.userId)
            .firstOrNull { it.packageName == id.packageName }
            ?.sourceDir
            ?.takeIf { it.isNotBlank() }
    }

    /** "<userId>:<packageName>" -> id; anything malformed is null. */
    private fun parseFocused(raw: String?): CloneId? {
        if (raw.isNullOrBlank()) return null
        val split = raw.indexOf(':')
        if (split <= 0 || split == raw.length - 1) return null
        val userId = raw.substring(0, split).trim().toIntOrNull() ?: return null
        val pkg = raw.substring(split + 1).trim()
        if (userId < 0 || pkg.isEmpty()) return null
        return CloneId(pkg, userId)
    }

    private fun toResult(result: InstallResult?): EngineResult = when {
        result == null -> EngineResult.Error(CloneError.NoResult)
        !result.success -> EngineResult.Error(CloneError.InstallFailed(result.msg))
        result.packageName.isNullOrBlank() -> EngineResult.Error(CloneError.NoPackageName)
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
            EngineResult.Error(CloneError.Engine(t.message ?: t.javaClass.simpleName))
        }

    private inline fun <T> safe(default: T, block: () -> T): T =
        try {
            block()
        } catch (t: Throwable) {
            default
        }
}
