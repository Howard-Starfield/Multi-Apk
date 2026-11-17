package dev.clonelab.app.data

import dev.clonelab.app.keepalive.ManualStops
import dev.clonelab.app.model.CloneError
import dev.clonelab.app.model.CloneException
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
    fun newClonesAreKeptAliveByDefaultAndStoredThatWay() = runTest {
        setUp()
        val fromApp = repository.addFromInstalledApp("com.a", "A").getOrThrow()
        val fromApk = repository.addFromApk("content://apk", "x").getOrThrow()
        assertTrue(fromApp.keepAlive)
        assertTrue(fromApk.keepAlive)
        assertTrue(store.get(fromApp.id)!!.keepAlive)
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
        assertEquals(CloneError.InstallFailed("unsupported ABI"), (result.exceptionOrNull() as CloneException).error)
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

    // --- another clone of the same app ---

    @Test
    fun anotherCloneOfAnInstalledAppUsesThePhoneApp() = runTest {
        setUp()
        val first = repository.addFromInstalledApp("com.a", "A").getOrThrow()
        engine.apkPaths[first.id] = "/data/base.apk"

        val second = repository.addAnotherClone("com.a", installedOnPhone = true).getOrThrow()

        assertEquals(CloneId("com.a", 1), second.id)
        assertEquals("A", second.appLabel)
        assertEquals(2, store.all().size)
    }

    @Test
    fun anotherCloneOfAnApkOnlyAppReusesTheEnginesApk() = runTest {
        setUp()
        val first = repository.addFromApk("content://apk", "Karaoke").getOrThrow()
        engine.apkPaths[first.id] = "/data/app/base.apk"

        val second = repository.addAnotherClone(first.id.packageName, installedOnPhone = false).getOrThrow()

        assertEquals(first.id.packageName, second.id.packageName)
        assertEquals(1, second.id.userId)
        assertEquals("Karaoke", second.appLabel)
        assertTrue(second.keepAlive)
        assertEquals(2, store.all().size)
    }

    @Test
    fun anApkOnlyAppWithoutAKnownApkPathCreatesNothing() = runTest {
        setUp()
        val first = repository.addFromApk("content://apk", "Karaoke").getOrThrow()

        val result = repository.addAnotherClone(first.id.packageName, installedOnPhone = false)

        assertTrue(result.isFailure)
        assertEquals(1, store.all().size)
        assertEquals(setOf(first.id), engine.installed)
    }

    @Test
    fun anotherCloneOfAnUnknownAppFails() = runTest {
        setUp()
        val result = repository.addAnotherClone("com.none", installedOnPhone = true)
        assertEquals(CloneError.UnknownClone, (result.exceptionOrNull() as CloneException).error)
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun failedInstallOfAnotherCloneCreatesNoRecord() = runTest {
        setUp()
        val first = repository.addFromApk("content://apk", "K").getOrThrow()
        engine.apkPaths[first.id] = "/data/base.apk"
        engine.installError = "boom"

        assertTrue(repository.addAnotherClone(first.id.packageName, installedOnPhone = false).isFailure)
        assertTrue(repository.addAnotherClone(first.id.packageName, installedOnPhone = true).isFailure)

        assertEquals(1, store.all().size)
    }

    @Test
    fun anotherCloneNeedsAReachableEngine() = runTest {
        setUp()
        val first = repository.addFromApk("content://apk", "K").getOrThrow()
        engine.apkPaths[first.id] = "/data/base.apk"
        engine.available = false

        val result = repository.addAnotherClone(first.id.packageName, installedOnPhone = false)

        assertTrue(result.exceptionOrNull() is EngineUnavailableException)
        assertEquals(1, store.all().size)
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
        assertEquals(CloneError.EngineNotRunning, (repository.addFromInstalledApp("com.a", "A").exceptionOrNull() as CloneException).error)
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

    // --- Google services per clone number (user id) ---

    /** com.a#0 and com.b#0 share clone number 1; com.a#1 is clone number 2. */
    private suspend fun threeClones(): Triple<CloneId, CloneId, CloneId> {
        val a0 = repository.addFromInstalledApp("com.a", "A").getOrThrow().id
        val b0 = repository.addFromInstalledApp("com.b", "B").getOrThrow().id
        val a1 = repository.addFromInstalledApp("com.a", "A").getOrThrow().id
        assertEquals(listOf(0, 0, 1), listOf(a0, b0, a1).map { it.userId })
        return Triple(a0, b0, a1)
    }

    @Test
    fun googleServicesFailOnAPhoneWithoutPlayServices() = runTest {
        ManualStops.clearAll()
        setUp()
        val (a0, _, _) = threeClones()
        engine.running += a0
        engine.googleSupported = false

        val result = repository.setGoogleServices(0, true)

        assertEquals(CloneError.NoGoogleServices, (result.exceptionOrNull() as CloneException).error)
        assertTrue(a0 in engine.running)
        assertTrue(engine.stopped.isEmpty())
        assertFalse(engine.calls.any { it.startsWith("installGoogle") })
    }

    @Test
    fun googleServicesFailWhenTheEngineIsDown() = runTest {
        setUp()
        threeClones()
        engine.available = false

        val result = repository.setGoogleServices(0, true)

        assertEquals(CloneError.EngineNotRunning, (result.exceptionOrNull() as CloneException).error)
        assertTrue(engine.googleUsers.isEmpty())
        assertEquals(null, repository.googleServicesStatus(0))
    }

    @Test
    fun enablingGoogleServicesStopsRunningClonesOfThatNumberOnly() = runTest {
        ManualStops.clearAll()
        setUp()
        val (a0, b0, a1) = threeClones()
        engine.running += listOf(a0, b0, a1)

        assertTrue(repository.setGoogleServices(0, true).isSuccess)

        assertEquals(setOf(0), engine.googleUsers)
        assertEquals(setOf(a0, b0), engine.stopped.toSet())
        assertEquals(setOf(a1), engine.running)
        assertEquals(GoogleServicesStatus(supported = true, enabled = true), repository.googleServicesStatus(0))
        assertEquals(GoogleServicesStatus(supported = true, enabled = false), repository.googleServicesStatus(1))
        // Stopped before the change, never reinstalled by hand: the watchdog must leave them alone ...
        assertTrue(ManualStops.contains(a0))
        assertTrue(ManualStops.contains(b0))
        assertFalse(ManualStops.contains(a1))
        // ... until the user opens one again.
        repository.launch(a0)
        assertFalse(ManualStops.contains(a0))
        assertTrue(ManualStops.contains(b0))
        ManualStops.clearAll()
    }

    @Test
    fun stopHappensBeforeTheEngineChange() = runTest {
        ManualStops.clearAll()
        setUp()
        val (a0, _, _) = threeClones()
        engine.running += a0
        var stoppedBeforeInstall = false
        val spy = object : dev.clonelab.app.engine.CloneEngine by engine {
            override fun installGoogleServices(userId: Int): dev.clonelab.app.engine.EngineResult {
                stoppedBeforeInstall = a0 in engine.stopped && ManualStops.contains(a0)
                return engine.installGoogleServices(userId)
            }
        }
        val spied = CloneRepository(spy, store) { 42L }

        assertTrue(spied.setGoogleServices(0, true).isSuccess)

        assertTrue(stoppedBeforeInstall)
        ManualStops.clearAll()
    }

    @Test
    fun disablingGoogleServicesRemovesThemForThatNumberOnly() = runTest {
        ManualStops.clearAll()
        setUp()
        val (a0, _, a1) = threeClones()
        engine.googleUsers += setOf(0, 1)
        engine.running += listOf(a0, a1)

        assertTrue(repository.setGoogleServices(0, false).isSuccess)

        assertEquals(setOf(1), engine.googleUsers)
        assertEquals(listOf(a0), engine.stopped)
        assertTrue(a1 in engine.running)
        ManualStops.clearAll()
    }

    @Test
    fun installFailureLeavesGoogleServicesOffAndReportsTheReason() = runTest {
        ManualStops.clearAll()
        setUp()
        threeClones()
        engine.googleInstallError = "no space"

        val error = (repository.setGoogleServices(0, true).exceptionOrNull() as CloneException).error

        assertEquals(CloneError.InstallFailed("no space"), error)
        assertTrue(engine.googleUsers.isEmpty())
        assertFalse(repository.googleServicesStatus(0)!!.enabled)
        ManualStops.clearAll()
    }

    @Test
    fun removalThatDoesNotTakeEffectIsAFailureAndStaysOn() = runTest {
        ManualStops.clearAll()
        setUp()
        threeClones()
        engine.googleUsers += 0
        engine.googleRemoveStuck = true

        val error = (repository.setGoogleServices(0, false).exceptionOrNull() as CloneException).error

        assertEquals(CloneError.GoogleServicesRemoveFailed, error)
        assertTrue(repository.googleServicesStatus(0)!!.enabled)
        ManualStops.clearAll()
    }

    @Test
    fun googleServicesStatusReportsAnUnsupportedPhone() = runTest {
        setUp()
        engine.googleSupported = false
        assertEquals(GoogleServicesStatus(supported = false, enabled = false), repository.googleServicesStatus(0))
    }
}
