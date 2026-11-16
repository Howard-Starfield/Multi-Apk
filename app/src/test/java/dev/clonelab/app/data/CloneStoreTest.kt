package dev.clonelab.app.data

import dev.clonelab.app.model.CloneId
import dev.clonelab.app.model.CloneRecord
import dev.clonelab.app.model.DeviceProfile
import dev.clonelab.app.model.IconKind
import dev.clonelab.app.model.LocationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CloneStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun file() = File(folder.root, "clones.json")

    private fun record(pkg: String, userId: Int, order: Int) =
        CloneRecord(id = CloneId(pkg, userId), appLabel = pkg, sortOrder = order, createdAt = 1L)

    private val full = CloneRecord(
        id = CloneId("com.example.app", 2),
        appLabel = "Example",
        customName = "Work",
        sortOrder = 7,
        iconKind = IconKind.IMAGE,
        iconColor = 0xFF112233.toInt(),
        iconImagePath = "/files/icons/com.example.app_2.png",
        keepAlive = true,
        location = LocationConfig(true, 48.8566, -122.5),
        deviceProfile = DeviceProfile(
            "Pixel", "google", "Google", "Pixel 8", "shiba", "shiba", "shiba", "tensor",
            "google/shiba/shiba:14/AP1A:user/release-keys",
            "0123456789abcdef", "490154203237518", "ABC123DEF456", "02:11:22:33:44:55",
        ),
        createdAt = 1_700_000_000_123L,
    )

    @Test
    fun roundTripsEveryField() {
        CloneStore(file()).put(full)
        assertEquals(listOf(full), CloneStore(file()).all())
        assertEquals(full, CloneStore(file()).get(full.id))
    }

    @Test
    fun roundTripsNullableFieldsAsNull() {
        val emptyProfile = DeviceProfile(null, null, null, null, null, null, null, null, null, null, null, null, null)
        val plain = record("a", 0, 0).copy(deviceProfile = emptyProfile)
        val store = CloneStore(file())
        store.put(plain)
        assertEquals(plain, store.get(plain.id))
        assertNull(store.get(plain.id)!!.location)
        assertNull(store.get(plain.id)!!.customName)
    }

    @Test
    fun corruptFileReadsAsEmptyAndRecovers() {
        file().writeText("{ not json")
        val store = CloneStore(file())
        assertTrue(store.all().isEmpty())
        store.put(record("a", 0, 0))
        assertEquals(1, store.all().size)
    }

    @Test
    fun reorderPersists() {
        val store = CloneStore(file())
        val a = record("a", 0, 0)
        val b = record("b", 0, 1)
        val c = record("c", 0, 2)
        listOf(a, b, c).forEach(store::put)

        store.reorder(listOf(c.id, a.id, b.id))

        assertEquals(listOf(c.id, a.id, b.id), CloneStore(file()).all().map { it.id })
    }

    @Test
    fun putReplacesSameId() {
        val store = CloneStore(file())
        store.put(record("a", 0, 0))
        store.put(record("a", 0, 0).copy(customName = "New"))
        assertEquals(listOf("New"), store.all().map { it.customName })
    }

    @Test
    fun removeDeletesOnlyThatClone() {
        val store = CloneStore(file())
        store.put(record("a", 0, 0))
        store.put(record("a", 1, 1))
        store.remove(CloneId("a", 0))
        assertEquals(listOf(CloneId("a", 1)), CloneStore(file()).all().map { it.id })
    }

    @Test
    fun oneBadRecordAmongGoodOnesIsSkipped() {
        val store = CloneStore(file())
        store.put(record("a", 0, 0))
        store.put(record("b", 0, 1))
        val json = org.json.JSONObject(file().readText())
        json.getJSONArray("clones").put(1, org.json.JSONObject().put("packageName", "broken"))
        file().writeText(json.toString())

        assertEquals(listOf("a"), CloneStore(file()).all().map { it.id.packageName })
        // A later write keeps the good records and drops only the bad one.
        CloneStore(file()).put(record("c", 0, 2))
        assertEquals(listOf("a", "c"), CloneStore(file()).all().map { it.id.packageName })
        assertTrue(!File(folder.root, "clones.json.corrupt").exists())
    }

    @Test
    fun unknownIconKindFallsBackToDefault() {
        val store = CloneStore(file())
        store.put(record("a", 0, 0).copy(iconKind = IconKind.TEXT))
        file().writeText(file().readText().replace("\"TEXT\"", "\"HOLOGRAM\""))
        assertEquals(IconKind.APP, CloneStore(file()).get(CloneId("a", 0))!!.iconKind)
    }

    @Test
    fun corruptFileIsPreservedAsCorrupt() {
        file().writeText("{ not json")
        val store = CloneStore(file())
        assertTrue(store.all().isEmpty())
        store.put(record("a", 0, 0))
        assertEquals("{ not json", File(folder.root, "clones.json.corrupt").readText())
        assertEquals(1, CloneStore(file()).all().size)
    }

    @Test
    fun recoversFromTmpWhenMainFileIsMissing() {
        CloneStore(file()).put(record("a", 0, 0))
        assertTrue(file().renameTo(File(folder.root, "clones.json.tmp")))

        assertEquals(listOf(CloneId("a", 0)), CloneStore(file()).all().map { it.id })
    }

    @Test
    fun iconVersionRoundTripsAndDefaultsToZero() {
        val store = CloneStore(file())
        store.put(record("a", 0, 0).copy(iconVersion = 5))
        assertEquals(5L, store.get(CloneId("a", 0))!!.iconVersion)
        val json = org.json.JSONObject(file().readText())
        json.getJSONArray("clones").getJSONObject(0).remove("iconVersion")
        file().writeText(json.toString())
        assertEquals(0L, CloneStore(file()).get(CloneId("a", 0))!!.iconVersion)
    }
}
