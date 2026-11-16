package dev.clonelab.app.data

import dev.clonelab.app.engine.CloneEngine
import dev.clonelab.app.engine.EngineResult
import dev.clonelab.app.model.CloneId
import dev.clonelab.app.model.CloneRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class CloneItem(val record: CloneRecord, val installed: Boolean, val running: Boolean)

/** Joins stored settings with live engine state. All engine work happens on [Dispatchers.IO]. */
class CloneRepository(
    private val engine: CloneEngine,
    private val store: CloneStore,
    private val clock: () -> Long,
) {
    private val _clones = MutableStateFlow<List<CloneItem>>(emptyList())
    val clones: StateFlow<List<CloneItem>> = _clones

    /** Serialises everything that changes clones, so user ids are never handed out twice. */
    private val mutex = Mutex()

    suspend fun refresh() = withContext(Dispatchers.IO) { _clones.value = loadItems() }

    suspend fun addFromInstalledApp(packageName: String, appLabel: String): Result<CloneRecord> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val userId = smallestFreeUserId(storedIds(packageName) + engine.usedUserIds(packageName))
                finishAdd(engine.installFromInstalledApp(packageName, userId), packageName, userId, appLabel)
            }
        }

    /**
     * The package of an APK is only known once it is installed, so the user id cannot be
     * chosen per package here. It is the smallest id no stored clone uses at all, which is
     * therefore free for whichever package the APK turns out to be.
     */
    suspend fun addFromApk(apkUri: String, fallbackLabel: String): Result<CloneRecord> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val userId = smallestFreeUserId(store.all().map { it.id.userId }.toSet())
                var installedPackage: String? = null
                val result = engine.installFromApk(apkUri, userId) { installedPackage = it }
                finishAdd(result, installedPackage, userId, fallbackLabel)
            }
        }

    suspend fun launch(id: CloneId): Result<Unit> = withContext(Dispatchers.IO) {
        val record = store.get(id) ?: return@withContext Result.failure(IllegalStateException("Unknown clone"))
        engine.applyLocation(id, record.location)
        engine.applyDeviceProfile(id, record.deviceProfile)
        val result = engine.launch(id)
        _clones.value = loadItems()
        when (result) {
            EngineResult.Ok -> Result.success(Unit)
            is EngineResult.Error -> Result.failure(IllegalStateException(result.message))
        }
    }

    suspend fun stop(id: CloneId) = withContext(Dispatchers.IO) {
        engine.stop(id)
        _clones.value = loadItems()
    }

    suspend fun clearData(id: CloneId) = withContext(Dispatchers.IO) {
        engine.clearData(id)
        _clones.value = loadItems()
    }

    suspend fun remove(id: CloneId) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val record = store.get(id)
            engine.uninstall(id)
            store.remove(id)
            record?.iconImagePath?.let { File(it).delete() }
            _clones.value = loadItems()
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

    private fun finishAdd(result: EngineResult, packageName: String?, userId: Int, label: String): Result<CloneRecord> =
        when (result) {
            is EngineResult.Error -> Result.failure(IllegalStateException(result.message))
            EngineResult.Ok -> {
                if (packageName == null) {
                    Result.failure(IllegalStateException("The engine did not report a package name."))
                } else {
                    val nextOrder = (store.all().maxOfOrNull { it.sortOrder } ?: -1) + 1
                    val record = CloneRecord(
                        id = CloneId(packageName, userId),
                        appLabel = label,
                        sortOrder = nextOrder,
                        createdAt = clock(),
                    )
                    store.put(record)
                    _clones.value = loadItems()
                    Result.success(record)
                }
            }
        }

    private fun storedIds(packageName: String): Set<Int> =
        store.all().filter { it.id.packageName == packageName }.map { it.id.userId }.toSet()

    private fun smallestFreeUserId(used: Set<Int>): Int = generateSequence(0) { it + 1 }.first { it !in used }

    private fun loadItems(): List<CloneItem> = store.all().map {
        CloneItem(it, installed = engine.isInstalled(it.id), running = engine.isRunning(it.id))
    }
}
