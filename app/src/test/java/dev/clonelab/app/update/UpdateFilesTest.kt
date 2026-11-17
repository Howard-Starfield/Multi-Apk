package dev.clonelab.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpdateFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(dir: File, name: String) = File(dir, name).also { it.writeText("x") }

    @Test
    fun keepsOnlyFinishedDownloadsOfNewerVersions() {
        val dir = tmp.newFolder("updates")
        val installed = file(dir, "CloneLab-0.1.0.apk")
        val older = file(dir, "CloneLab-0.0.9.apk")
        val newer = file(dir, "CloneLab-0.2.0.apk")
        val partial = file(dir, "CloneLab-0.3.0.apk.part")
        val junk = file(dir, "something.txt")

        assertEquals(4, UpdateFiles.cleanUp(dir, "0.1.0"))

        assertTrue(newer.exists())
        assertFalse(installed.exists())
        assertFalse(older.exists())
        assertFalse(partial.exists())
        assertFalse(junk.exists())
    }

    @Test
    fun missingDirectoryIsFine() {
        assertEquals(0, UpdateFiles.cleanUp(File(tmp.root, "nope"), "0.1.0"))
        assertEquals(0, UpdateFiles.cleanUp(null, "0.1.0"))
    }
}
