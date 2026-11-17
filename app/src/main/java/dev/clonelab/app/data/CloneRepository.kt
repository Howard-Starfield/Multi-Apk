package dev.clonelab.app.data

import dev.clonelab.app.engine.CloneEngine
import dev.clonelab.app.engine.EngineResult
import dev.clonelab.app.keepalive.ManualStops
import dev.clonelab.app.model.CloneError
import dev.clonelab.app.model.CloneException
import dev.clonelab.app.model.CloneId
import dev.clonelab.app.model.CloneRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Raised for changes that must not run while the engine server is unreachable. */
class EngineUnavailableException : CloneException(CloneError.EngineNotRunning)

data class CloneItem(val record: CloneRecord, val installed: Boolean, val running: Boolean)

/** Whether the phone can give a clone number its own Google services, and whether this one has them. */
data class GoogleServicesStatus(val supported: Boolean, val enabled: Boolean)

/** Joins stored settings with live engine state. All engine work happens on [Dispatchers.IO]. */
class CloneRepository(
    private val engine: CloneEngine,
    private val store: CloneStore,
    private val clock: () -> Long,
) {
    private val _clones = MutableStateFlow<List<CloneItem>>(emptyList())
    val clones: StateFlow<List<CloneItem>> = _clones

    private val _engineAvailable = MutableStateFlow(true)

    /** False when the last refresh found the engine unreachable; [clones] then keeps its last known state. */
    val engineAvailable: StateFlow<Boolean> = _engineAvailable

    /** Serialises everything that changes clones, so user ids are never handed out twice. */
    private val mutex = Mutex()

    suspend fun refresh() = withContext(Dispatchers.IO) { _clones.value = loadItems() }

    /** Waits (up to [timeoutMs]) for the engine before refreshing, so a starting engine is not read as "broken". */
    suspend fun refreshWhenReady(timeoutMs: Long = READY_TIMEOUT_MS) = withContext(Dispatchers.IO) {
        engine.awaitReady(timeoutMs)
        _clones.value = loadItems()
    }

    suspend fun addFromInstalledApp(packageName: String, appLabel: String): Result<CloneRecord> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                attempt {
                    requireEngine()
                    // usedUserIds throws when the engine is unavailable, so "none used" is always real.
                    val userId = smallestFreeUserId(storedIds(packageName) + engine.usedUserIds(packageName))
                    finishAdd(engine.installFromInstalledApp(packageName, userId), packageName, userId, appLabel)
                }
            }
        }

    /**
     * The package of an APK is only known once it is installed, so the user id cannot be chosen per
     * package. Rule: the smallest id that no stored clone uses and for which the engine reports no
     * existing user, whatever the package. A possible orphan (installed in the engine but missing from
     * the store) is therefore never reused; gaps left by removals are only reused if the engine has
     * no user with that id. Correctness over gap reuse.
     */
    suspend fun addFromApk(apkUri: String, fallbackLabel: String): Result<CloneRecord> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                attempt {
                    requireEngine()
                    val taken = store.all().map { it.id.userId }.toSet() + engine.existingUserIds()
                    val userId = smallestFreeUserId(taken)
                    var installedPackage: String? = null
                    val result = engine.installFromApk(apkUri, userId) { installedPackage = it }
                    finishAdd(result, installedPackage, userId, fallbackLabel)
                }
            }
        }

    /**
     * Another clone of an app that already has one. An app installed on the phone is cloned from there;
     * otherwise (cloned from an APK file) the APK the engine already holds is installed again.
     * Fails without creating a record when the app has no clone, or no APK can be found.
     */
    suspend fun addAnotherClone(packageName: String, installedOnPhone: Boolean): Result<CloneRecord> {
        val existing = withContext(Dispatchers.IO) { store.all().filter { it.id.packageName == packageName } }
        val label = existing.firstOrNull()?.appLabel
            ?: return Result.failure(CloneException(CloneError.UnknownClone))
        if (installedOnPhone) return addFromInstalledApp(packageName, label)
        val apk: Result<String> = withContext(Dispatchers.IO) {
            attempt {
                requireEngine()
                val path = existing.firstNotNullOfOrNull { engine.apkPath(it.id) }
                if (path == null) Result.failure<String>(CloneException(CloneError.InstallFailed(null))) else Result.success(path)
            }
        }
        // Same form as Uri.fromFile(File(path)).toString().
        return apk.fold(
            onSuccess = { addFromApk("file://$it", label) },
            onFailure = { Result.failure(it) },
        )
    }

    suspend fun launch(id: CloneId): Result<Unit> = withContext(Dispatchers.IO) {
        val record = store.get(id) ?: return@withContext Result.failure(CloneException(CloneError.UnknownClone))
        ManualStops.remove(id)
        engine.applyLocation(id, record.location)
        engine.applyDeviceProfile(id, record.deviceProfile)
        val result = engine.launch(id)
        _clones.value = loadItems()
        when (result) {
            EngineResult.Ok -> Result.success(Unit)
            is EngineResult.Error -> Result.failure(CloneException(result.error))
        }
    }

    suspend fun stop(id: CloneId) = withContext(Dispatchers.IO) {
        ManualStops.add(id)
        engine.stop(id)
        _clones.value = loadItems()
    }

    suspend fun clearData(id: CloneId): Result<Unit> = withContext(Dispatchers.IO) {
        attempt {
            requireEngine()
            ManualStops.add(id)
            engine.clearData(id)
            _clones.value = loadItems()
            Result.success(Unit)
        }
    }

    /**
     * Uninstalls the clone and forgets it. The record is only deleted once the clone is verifiably gone
     * from a reachable engine; a record the engine does not have (broken) is deleted directly.
     */
    suspend fun remove(id: CloneId): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            attempt {
                requireEngine()
                if (engine.isInstalled(id)) {
                    engine.uninstall(id)
                    requireEngine()
                    if (engine.isInstalled(id)) {
                        return@attempt Result.failure(CloneException(CloneError.UninstallFailed))
                    }
                } else {
                    // "Not installed" is only trustworthy if the engine was still reachable for that answer.
                    requireEngine()
                }
                val record = store.get(id)
                store.remove(id)
                ManualStops.remove(id)
                record?.iconImagePath?.let { File(it).delete() }
                _clones.value = loadItems()
                Result.success(Unit)
            }
        }
    }

    /** Null when the engine cannot be asked right now. */
    suspend fun googleServicesStatus(userId: Int): GoogleServicesStatus? = withContext(Dispatchers.IO) {
        if (!engine.isAvailable()) return@withContext null
        val status = GoogleServicesStatus(engine.googleServicesSupported(), engine.hasGoogleServices(userId))
        if (engine.isAvailable()) status else null
    }

    /**
     * Gives clone number [userId] (every app that uses it) its own Google services, or takes them away.
     * Running clones of that number are stopped first, like a manual Stop: the marks stay until the user
     * opens them again (launch clears it), so keep-alive does not relaunch them over the user's screen.
     * Clones of other numbers are not touched. On failure the user is left without Google services
     * when enabling, and the engine's state is whatever it reports when disabling.
     */
    suspend fun setGoogleServices(userId: Int, enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            attempt {
                requireEngine()
                if (!engine.googleServicesSupported()) {
                    return@attempt Result.failure(CloneException(CloneError.NoGoogleServices))
                }
                store.all().map { it.id }.filter { it.userId == userId && engine.isRunning(it) }.forEach {
                    ManualStops.add(it)
                    engine.stop(it)
                }
                val result = if (enabled) engine.installGoogleServices(userId) else engine.removeGoogleServices(userId)
                requireEngine()
                _clones.value = loadItems()
                when {
                    result is EngineResult.Error -> {
                        // Enabling must not leave half a set behind.
                        if (enabled && engine.hasGoogleServices(userId)) engine.removeGoogleServices(userId)
                        Result.failure(CloneException(result.error))
                    }
                    engine.hasGoogleServices(userId) != enabled -> Result.failure(
                        CloneException(if (enabled) CloneError.InstallFailed(null) else CloneError.GoogleServicesRemoveFailed),
                    )
                    else -> Result.success(Unit)
                }
            }
        }
    }

    suspend fun update(id: CloneId, transform: (CloneRecord) -> CloneRecord) = withContext(Dispatchers.IO) {
        mutex.withLock {
            store.get(id)?.let { store.put(transform(it)) }
            _clones.value = loadItems()
        }
    }

    suspend fun reorder(idsInOrder: List<CloneId>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            store.reorder(idsInOrder)
            _clones.value = loadItems()
        }
    }

    private fun finishAdd(result: EngineResult, packageName: String?, userId: Int, label: String): Result<CloneRecord> {
        return when (result) {
            is EngineResult.Error -> Result.failure(CloneException(result.error))
            EngineResult.Ok -> {
                if (packageName == null) {
                    Result.failure(CloneException(CloneError.NoPackageName))
                } else {
                    val nextOrder = (store.all().maxOfOrNull { it.sortOrder } ?: -1) + 1
                    val record = CloneRecord(
                        id = CloneId(packageName, userId),
                        appLabel = label,
                        sortOrder = nextOrder,
                        keepAlive = true, // new clones stay alive by default; the data-class default stays false
                        createdAt = clock(),
                    )
                    try {
                        store.put(record)
                    } catch (e: Exception) {
                        // Do not leave an engine clone that no record points to.
                        runCatching { engine.uninstall(record.id) }
                        return Result.failure(CloneException(CloneError.SaveFailed(e.message), e))
                    }
                    _clones.value = loadItems()
                    Result.success(record)
                }
            }
        }
    }

    private fun storedIds(packageName: String): Set<Int> =
        store.all().filter { it.id.packageName == packageName }.map { it.id.userId }.toSet()

    private fun smallestFreeUserId(used: Set<Int>): Int = generateSequence(0) { it + 1 }.first { it !in used }

    private fun requireEngine() {
        if (!engine.isAvailable()) throw EngineUnavailableException()
    }

    private inline fun <T> attempt(block: () -> Result<T>): Result<T> =
        try {
            block()
        } catch (e: Exception) {
            Result.failure(e)
        }

    /** Live state, or the previous state (default: installed, not running) while the engine is unreachable. */
    private fun loadItems(): List<CloneItem> {
        val records = store.all()
        if (engine.isAvailable()) {
            val live = records.map { CloneItem(it, installed = engine.isInstalled(it.id), running = engine.isRunning(it.id)) }
            // The engine may have died mid-query, which reads as "nothing installed": re-check.
            if (engine.isAvailable()) {
                _engineAvailable.value = true
                return live
            }
        }
        _engineAvailable.value = false
        val previous = _clones.value.associateBy { it.record.id }
        return records.map { record ->
            previous[record.id]?.copy(record = record) ?: CloneItem(record, installed = true, running = false)
        }
    }

    private companion object {
        const val READY_TIMEOUT_MS = 10_000L
    }
}
