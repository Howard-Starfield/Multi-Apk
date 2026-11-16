package dev.clonelab.app.data

import dev.clonelab.app.engine.CloneEngine
import dev.clonelab.app.engine.EngineResult
import dev.clonelab.app.model.CloneId
import dev.clonelab.app.model.DeviceProfile
import dev.clonelab.app.model.LocationConfig

/** In-memory engine that records the order of the calls it receives. */
class FakeCloneEngine : CloneEngine {
    val installed = mutableSetOf<CloneId>()
    val running = mutableSetOf<CloneId>()
    val calls = mutableListOf<String>()
    var installError: String? = null
    var apkPackage = "com.example.fromapk"

    override fun awaitReady(timeoutMs: Long) = true

    override fun installFromInstalledApp(packageName: String, userId: Int): EngineResult {
        installError?.let { return EngineResult.Error(it) }
        installed += CloneId(packageName, userId)
        return EngineResult.Ok
    }

    override fun installFromApk(apkUri: String, userId: Int, onPackage: (String) -> Unit): EngineResult {
        installError?.let { return EngineResult.Error(it) }
        installed += CloneId(apkPackage, userId)
        onPackage(apkPackage)
        return EngineResult.Ok
    }

    override fun usedUserIds(packageName: String) =
        installed.filter { it.packageName == packageName }.map { it.userId }.toSet()

    override fun isInstalled(id: CloneId) = id in installed
    override fun isRunning(id: CloneId) = id in running

    override fun launch(id: CloneId): EngineResult {
        calls += "launch"
        running += id
        return EngineResult.Ok
    }

    override fun stop(id: CloneId) {
        running -= id
    }

    override fun clearData(id: CloneId) {
        calls += "clearData"
    }

    override fun uninstall(id: CloneId) {
        calls += "uninstall"
        installed -= id
        running -= id
    }

    override fun applyLocation(id: CloneId, location: LocationConfig?) {
        calls += "applyLocation:$location"
    }

    override fun applyDeviceProfile(id: CloneId, profile: DeviceProfile?) {
        calls += "applyDeviceProfile:${profile?.presetName}"
    }

    val apkPaths = mutableMapOf<CloneId, String>()
    override fun apkPath(id: CloneId): String? = apkPaths[id]
}
