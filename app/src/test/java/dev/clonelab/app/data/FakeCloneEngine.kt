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

    /** When false the fake behaves like a dead engine server: queries read as empty, usedUserIds throws. */
    var available = true

    override fun awaitReady(timeoutMs: Long) = available
    override fun isAvailable() = available

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

    override fun usedUserIds(packageName: String): Set<Int> {
        check(available) { "engine unavailable" }
        return installed.filter { it.packageName == packageName }.map { it.userId }.toSet()
    }

    /** Users the engine has beyond the installed clones, e.g. an orphan left by an earlier failure. */
    val extraUsers = mutableSetOf<Int>()
    override fun existingUserIds(): Set<Int> {
        check(available) { "engine unavailable" }
        return installed.map { it.userId }.toSet() + extraUsers
    }

    /** Ids whose uninstall silently does nothing. */
    val stuck = mutableSetOf<CloneId>()

    override fun isInstalled(id: CloneId) = available && id in installed
    override fun isRunning(id: CloneId) = available && id in running

    var focused: CloneId? = null
    override fun focusedClone(): CloneId? = focused

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
        if (id in stuck) return
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
