package dev.clonelab.app.engine

import dev.clonelab.app.model.CloneId
import dev.clonelab.app.model.DeviceProfile
import dev.clonelab.app.model.LocationConfig

sealed interface EngineResult {
    data object Ok : EngineResult
    data class Error(val message: String) : EngineResult
}

/** The only view of the virtualization engine the rest of the app has. Calls block. */
interface CloneEngine {
    fun awaitReady(timeoutMs: Long): Boolean
    fun installFromInstalledApp(packageName: String, userId: Int): EngineResult

    /** @return Ok plus the installed package name through [onPackage]. */
    fun installFromApk(apkUri: String, userId: Int, onPackage: (String) -> Unit): EngineResult
    fun usedUserIds(packageName: String): Set<Int>
    fun isInstalled(id: CloneId): Boolean
    fun isRunning(id: CloneId): Boolean
    fun launch(id: CloneId): EngineResult
    fun stop(id: CloneId)
    fun clearData(id: CloneId)
    fun uninstall(id: CloneId)
    fun applyLocation(id: CloneId, location: LocationConfig?)
    fun applyDeviceProfile(id: CloneId, profile: DeviceProfile?)
}
