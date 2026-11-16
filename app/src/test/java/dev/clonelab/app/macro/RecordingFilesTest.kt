package dev.clonelab.app.macro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun aFlagIsFreshUntilItIsOlderThanFifteenSeconds() {
        val now = 1_000_000L
        assertTrue(RecordingFiles.isFresh(now, now))
        assertTrue(RecordingFiles.isFresh(now - 15_000, now))
        assertFalse(RecordingFiles.isFresh(now - 15_001, now))
        assertFalse(RecordingFiles.isFresh(now - 3_600_000, now))
    }

    @Test
    fun aHeartbeatEveryFiveSecondsKeepsTheFlagFresh() {
        assertTrue(RecordingFiles.HEARTBEAT_MS * 2 < RecordingFiles.STALE_AFTER_MS)
    }

    @Test
    fun touchFlagRefreshesTheModificationTimeAndKeepsTheContent() {
        val files = RecordingFiles(tmp.root)
        val flag = RecordingFlag("s1", "com.example", 2)
        files.writeFlag(flag)
        files.flagFile.setLastModified(1_000L)
        files.touchFlag(flag)
        assertTrue(files.flagFile.lastModified() > 1_000L)
        assertEquals(flag, files.readFlag())
    }

    @Test
    fun touchFlagRewritesAFlagThatIsGone() {
        val files = RecordingFiles(tmp.root)
        val flag = RecordingFlag("s1", "com.example", 0)
        files.touchFlag(flag)
        assertNotNull(files.readFlag())
    }

    @Test
    fun recorderHeartbeatOnlyWhileASessionRuns() {
        val recorder = MacroRecorder(tmp.root)
        val files = RecordingFiles(tmp.root)
        recorder.heartbeat() // nothing running: must not create a flag
        assertFalse(files.flagFile.exists())
        recorder.start(dev.clonelab.app.model.CloneId("com.example", 1))
        files.flagFile.setLastModified(1_000L)
        recorder.heartbeat()
        assertTrue(files.flagFile.lastModified() > 1_000L)
        recorder.cancel()
        recorder.heartbeat() // after cancel: must not bring the flag back
        assertFalse(files.flagFile.exists())
    }
}
