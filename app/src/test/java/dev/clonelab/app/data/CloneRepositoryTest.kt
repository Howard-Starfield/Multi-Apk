package dev.clonelab.app.data

import dev.clonelab.app.keepalive.ManualStops
import dev.clonelab.app.model.CloneId
import dev.clonelab.app.model.DeviceProfile
import dev.clonelab.app.model.IconKind
import dev.clonelab.app.model.LocationConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CloneRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val engine = FakeCloneEngine()
    private lateinit var store: CloneStore
    private lateinit var repository: CloneRepository

    private fun setUp() {
        store = CloneStore(File(folder.root, "clones.json"))
        repository = CloneRepository(engine, store) { 42L }
    }

    @Test
    fun firstCloneGetsUserIdZeroAndSecondGetsOne() = runTest {
        setUp()
        val first = repository.addFromInstalledApp("com.a", "A").getOrThrow()
        val second = repository.addFromInstalledApp("com.a", "A").getOrThrow()
        assertEquals(0, first.id.userId)
        assertEquals(1, second.id.userId)
        assertEquals(listOf(0, 1), repository.clones.value.map { it.record.sortOrder })
        assertEquals(42L, first.createdAt)
    }

    @Test
    fun gapIsReusedAfterRemoval() = runTest {
        setUp()
        repository.addFromInstalledApp("com.a", "A")
        val second = repository.addFromInstalledApp("com.a", "A").getOrThrow()
        repository.addFromInstalledApp("com.a", "A")
        repository.remove(second.id)
        val reused = repository.addFromInstalledApp("com.a", "A").getOrThrow()
        assertEquals(1, reused.id.userId)
    }

    @Test
    fun differentPackagesBothGetUserIdZero() = runTest {
        setUp()
        val a = repository.addFromInstalledApp("com.a", "A").getOrThrow()
        val b = repository.addFromInstalledApp("com.b", "B").getOrThrow()
        assertEquals(0, a.id.userId)
        assertEquals(0, b.id.userId)
    }

    @Test
    fun engineInstallErrorCreatesNoRecord() = runTest {
        setUp()
        engine.installError = "unsupported ABI"
        val result = repository.addFromInstalledApp("com.a", "A")
        assertTrue(result.isFailure)
        assertEquals("unsupported ABI", result.exceptionOrNull()?.message)
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun apkCloneUsesReportedPackage() = runTest {
        setUp()
        val record = repository.addFromApk("content://apk", "My APK").getOrThrow()
        assertEquals(CloneId("com.example.fromapk", 0), record.id)
        assertEquals("My APK", record.appLabel)
    }

    @Test
    fun stopAndClearDataMarkManualStopAndLaunchClearsIt() = runTest {
        ManualStops.clearAll()
        setUp()
        val id = repository.addFromInstalledApp("com.a", "A").getOrThrow().id
        repository.launch(id)
        assertFalse(ManualStops.contains(id))

        repository.stop(id)
        assertTrue(ManualStops.contains(id))
        repository.launch(id)
        assertFalse(ManualStops.contains(id))

        repository.clearData(id)
        assertTrue(ManualStops.contains(id))
        repository.launch(id)
        assertFalse(ManualStops.contains(id))
        ManualStops.clearAll()
    }

    @Test
    fun launchAppliesLocationAndProfileBeforeLaunching() = runTest {
        setUp()
        val profile = DeviceProfile("P", null, null, null, null, null, null, null, null, null, null, null, null)
        val record = repository.addFromInstalledApp("com.a", "A").getOrThrow()
        repository.update(record.id) {
            it.copy(location = LocationConfig(true, 1.5, 2.5), deviceProfile = profile)
        }

        assertTrue(repository.launch(record.id).isSuccess)

        assertEquals(
            listOf(
                "applyLocation:LocationConfig(enabled=true, latitude=1.5, longitude=2.5)",
                "applyDeviceProfile:P",
                "launch",
            ),
            engine.calls,
        )
        assertTrue(repository.clones.value.single().running)
    }

    @Test
    fun recordMissingInEngineIsReportedNotInstalled() = runTest {
        setUp()
        val record = repository.addFromInstalledApp("com.a", "A").getOrThrow()
        engine.installed.clear()
        repository.refresh()
        assertFalse(repository.clones.value.single { it.record.id == record.id }.installed)
    }

    @Test
    fun removeUninstallsAndDeletesRecordAndIcon() = runTest {
        setUp()
        val record = repository.addFromInstalledApp("com.a", "A").getOrThrow()
        val icon = folder.newFile("icon.png")
        repository.update(record.id) { it.copy(iconImagePath = icon.absolutePath) }

        repository.remove(record.id)

        assertEquals(listOf("uninstall"), engine.calls)
        assertTrue(store.all().isEmpty())
        assertTrue(repository.clones.value.isEmpty())
        assertFalse(icon.exists())
    }

    @Test
    fun updatePersistsNameAndIconFields() = runTest {
        setUp()
        val record = repository.addFromInstalledApp("com.a", "A").getOrThrow()
        repository.update(record.id) {
            it.copy(customName = "Work", iconKind = IconKind.TEXT, iconColor = 0xFF8250DF.toInt())
        }
        repository.update(record.id) { it.copy(iconKind = IconKind.IMAGE, iconImagePath = "/icons/a.png") }

        val stored = CloneStore(File(folder.root, "clones.json")).get(record.id)!!
        assertEquals("Work", stored.customName)
        assertEquals("Work", stored.displayName)
        assertEquals(IconKind.IMAGE, stored.iconKind)
        assertEquals(0xFF8250DF.toInt(), stored.iconColor)
        assertEquals("/icons/a.png", stored.iconImagePath)
        assertEquals(stored, repository.clones.value.single().record)
    }

    @Test
    fun blankNameResetsToDefaultAndKeepsOrder() = runTest {
        setUp()
        val first = repository.addFromInstalledApp("com.a", "A").getOrThrow()
        val second = repository.addFromInstalledApp("com.a", "A").getOrThrow()
        repository.update(first.id) { it.copy(customName = "Work") }
        repository.update(first.id) { it.copy(customName = null) }
        repository.reorder(listOf(second.id, first.id))

        assertEquals("A 1", store.get(first.id)!!.displayName)
        assertEquals(listOf(second.id, first.id), repository.clones.value.map { it.record.id })
    }

    // --- engine availability ---

    @Test
    fun unavailableEngineKeepsPreviousStateOnRefresh() = runTest {
        setUp()
        val id = repository.addFromInstalledApp("com.a", "A").getOrThrow().id
        repository.launch(id)
        assertTrue(repository.clones.value.single().running)

        engine.available = false
        repository.refresh()

        val item = repository.clones.value.single()
        assertTrue(item.installed)
        assertTrue(item.running)
        assertFalse(repository.engineAvailable.value)

        engine.available = true
        repository.refresh()
        assertTrue(repository.engineAvailable.value)
    }

    @Test
    fun unavailableEngineDefaultsUnknownClonesToInstalled() = runTest {
        setUp()
        repository.addFromInstalledApp("com.a", "A")
        val fresh = CloneRepository(engine, store) { 42L }
        engine.available = false
        fresh.refresh()
        assertTrue(fresh.clones.value.single().installed)
        assertFalse(fresh.clones.value.single().running)
    }

    @Test
    fun addFailsWhileEngineUnavailable() = runTest {
        setUp()
        engine.available = false
        assertTrue(repository.addFromInstalledApp("com.a", "A").isFailure)
        assertTrue(repository.addFromApk("content://apk", "x").isFailure)
        assertTrue(store.all().isEmpty())
        assertTrue(engine.installed.isEmpty())
    }

    @Test
    fun removeAndClearDataFailWhileEngineUnavailableAndKeepRecord() = runTest {
        setUp()
        val id = repository.addFromInstalledApp("com.a", "A").getOrThrow().id
        engine.available = false

        assertTrue(repository.remove(id).isFailure)
        assertTrue(repository.clearData(id).isFailure)

        assertEquals(1, store.all().size)
        assertFalse(engine.calls.contains("uninstall"))
        assertFalse(engine.calls.contains("clearData"))
    }

    // --- remove / add safety ---

    @Test
    fun removeKeepsRecordWhenCloneIsStillInEngine() = runTest {
        setUp()
        val id = repository.addFromInstalledApp("com.a", "A").getOrThrow().id
        engine.stuck += id

        val result = repository.remove(id)

        assertTrue(result.isFailure)
        assertEquals(1, store.all().size)
    }

    @Test
    fun brokenRecordIsRemovedWithoutUninstall() = runTest {
        setUp()
        val id = repository.addFromInstalledApp("com.a", "A").getOrThrow().id
        engine.installed.clear()

        assertTrue(repository.remove(id).isSuccess)
        assertTrue(store.all().isEmpty())
        assertFalse(engine.calls.contains("uninstall"))
    }

    @Test
    fun removeClearsManualStop() = runTest {
        ManualStops.clearAll()
        setUp()
        val id = repository.addFromInstalledApp("com.a", "A").getOrThrow().id
        repository.stop(id)
        repository.remove(id)
        assertFalse(ManualStops.contains(id))
    }

    @Test
    fun storeFailureAfterInstallUninstallsAndFails() = runTest {
        // A directory where the temp file must go makes every write throw.
        File(folder.root, "clones.json.tmp").mkdirs()
        store = CloneStore(File(folder.root, "clones.json"))
        repository = CloneRepository(engine, store) { 42L }

        val result = repository.addFromInstalledApp("com.a", "A")

        assertTrue(result.isFailure)
        assertTrue(engine.installed.isEmpty())
        assertEquals(listOf("uninstall"), engine.calls)
    }

    @Test
    fun apkCloneNeverReusesAnIdTheEngineAlreadyKnows() = runTest {
        setUp()
        engine.extraUsers += 0
        val record = repository.addFromApk("content://apk", "x").getOrThrow()
        assertEquals(1, record.id.userId)
    }
}
