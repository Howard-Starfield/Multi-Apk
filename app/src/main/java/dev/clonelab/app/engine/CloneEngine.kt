package dev.clonelab.app.engine

import dev.clonelab.app.model.CloneError
import dev.clonelab.app.model.CloneId
import dev.clonelab.app.model.DeviceProfile
import dev.clonelab.app.model.LocationConfig

sealed interface EngineResult {
    data object Ok : EngineResult
    data class Error(val error: CloneError) : EngineResult
}

/** The only view of the virtualization engine the rest of the app has. Calls block. */
interface CloneEngine {
    /** Polls until the engine server answers, or [timeoutMs] passed. */
    fun awaitReady(timeoutMs: Long): Boolean

    /** Cheap, non-waiting check that the engine server is reachable right now. */
    fun isAvailable(): Boolean

    fun installFromInstalledApp(packageName: String, userId: Int): EngineResult

    /** @return Ok plus the installed package name through [onPackage]. */
    fun installFromApk(apkUri: String, userId: Int, onPackage: (String) -> Unit): EngineResult

    /** @throws IllegalStateException when the engine is unavailable: an empty set must mean "none used". */
    fun usedUserIds(packageName: String): Set<Int>

    /** Every virtual user id the engine knows, whatever package. @throws IllegalStateException when unavailable. */
    fun existingUserIds(): Set<Int>
    fun isInstalled(id: CloneId): Boolean
    fun isRunning(id: CloneId): Boolean

    /** The clone whose activity is resumed right now, or null when none is on screen (also briefly during transitions). */
    fun focusedClone(): CloneId?
    fun launch(id: CloneId): EngineResult
    fun stop(id: CloneId)
    fun clearData(id: CloneId)
    fun uninstall(id: CloneId)
    fun applyLocation(id: CloneId, location: LocationConfig?)
    fun applyDeviceProfile(id: CloneId, profile: DeviceProfile?)

    /** Path of the APK the engine installed for this clone, or null when unknown. */
    fun apkPath(id: CloneId): String?
}
