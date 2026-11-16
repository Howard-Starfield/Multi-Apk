package dev.clonelab.app.macro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MacroStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun file() = File(folder.root, "macros.json")

    private fun macro(id: String, pkg: String = "com.example", createdAt: Long = 1L) = Macro(
        id = id, name = "Macro $id", packageName = pkg, screenWidth = 1080, screenHeight = 2400,
        steps = listOf(MacroStep(0, MacroAction.Key(4))), createdAt = createdAt,
    )

    private val full = Macro(
        id = "11111111-1111-1111-1111-111111111111",
        name = "Everything",
        packageName = "com.example.app",
        screenWidth = 1080,
        screenHeight = 2400,
        steps = listOf(
            MacroStep(0, MacroAction.Tap(10.5f, 20.25f, 700)),
            MacroStep(300, MacroAction.Swipe(listOf(SwipePoint(0, 1.1f, 2.2f), SwipePoint(120, 300.3f, 400.4f)))),
            MacroStep(50, MacroAction.Key(66)),
        ),
        repeatCount = 0,
        repeatDelayMs = 2500,
        schedule = MacroSchedule.DailyAt(7, 30),
        scheduleUserId = 3,
        lastScheduledRunAt = 1_700_000_000_000L,
        createdAt = 1_690_000_000_000L,
    )

    @Test
    fun roundTripsEveryActionAndScheduleType() {
        val store = MacroStore(file())
        val schedules = listOf(
            MacroSchedule.Off, MacroSchedule.EveryMinutes(15), MacroSchedule.DailyAt(23, 59),
        )
        store.put(full)
        schedules.forEachIndexed { i, schedule ->
            store.put(full.copy(id = "m$i", schedule = schedule, scheduleUserId = if (i == 0) null else i, createdAt = 10L + i))
        }
        val reread = MacroStore(file()).all()
        assertEquals(4, reread.size)
        assertEquals(full, reread.first { it.id == full.id })
        schedules.forEachIndexed { i, schedule ->
            val m = reread.first { it.id == "m$i" }
            assertEquals(schedule, m.schedule)
            assertEquals(if (i == 0) null else i, m.scheduleUserId)
        }
    }

    @Test
    fun putReplacesTheSameIdAndGetFindsIt() {
        val store = MacroStore(file())
        store.put(macro("a"))
        store.put(macro("a").copy(name = "Renamed"))
        assertEquals(1, store.all().size)
        assertEquals("Renamed", store.get("a")?.name)
        assertNull(store.get("zzz"))
    }

    @Test
    fun forPackageFilters() {
        val store = MacroStore(file())
        store.put(macro("a", "com.one"))
        store.put(macro("b", "com.two"))
        store.put(macro("c", "com.one"))
        assertEquals(setOf("a", "c"), store.forPackage("com.one").map { it.id }.toSet())
    }

    @Test
    fun removeDeletesOnlyThatMacro() {
        val store = MacroStore(file())
        store.put(macro("a"))
        store.put(macro("b"))
        store.remove("a")
        assertEquals(listOf("b"), store.all().map { it.id })
        store.remove("missing")
        assertEquals(1, store.all().size)
    }

    @Test
    fun badRecordsAreSkippedAndTheRestSurvive() {
        val store = MacroStore(file())
        store.put(macro("good"))
        val text = file().readText()
        val broken = text.replace("\"macros\":[", "\"macros\":[{\"id\":\"bad\"},{\"id\":\"bad2\",\"name\":\"x\",\"packageName\":\"p\"," +
            "\"screenWidth\":1,\"screenHeight\":1,\"steps\":[{\"delayBeforeMs\":0,\"action\":{\"type\":\"warp\"}}]," +
            "\"repeatCount\":1,\"repeatDelayMs\":1,\"schedule\":{\"type\":\"off\"},\"scheduleUserId\":null," +
            "\"lastScheduledRunAt\":0,\"createdAt\":1},")
        file().writeText(broken)
        assertEquals(listOf("good"), MacroStore(file()).all().map { it.id })
    }

    @Test
    fun invalidValuesInARecordSkipThatRecord() {
        val store = MacroStore(file())
        store.put(macro("good"))
        store.put(full)
        file().writeText(file().readText().replace("\"hour\":7", "\"hour\":99"))
        assertEquals(listOf("good"), MacroStore(file()).all().map { it.id })
    }

    @Test
    fun unparseableFileIsMovedAsideAndTheStoreStartsEmpty() {
        file().writeText("{ not json")
        val store = MacroStore(file())
        assertTrue(store.all().isEmpty())
        assertTrue(File(folder.root, "macros.json.corrupt").exists())
        store.put(macro("a"))
        assertEquals(1, MacroStore(file()).all().size)
    }

    @Test
    fun missingFileIsRecoveredFromTheInterruptedTemp() {
        val store = MacroStore(file())
        store.put(macro("a"))
        file().renameTo(File(folder.root, "macros.json.tmp"))
        assertEquals(listOf("a"), MacroStore(file()).all().map { it.id })
    }
}
