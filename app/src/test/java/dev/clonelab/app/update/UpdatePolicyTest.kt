package dev.clonelab.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    private val day = UpdatePolicy.AUTO_CHECK_INTERVAL_MS

    private fun release(tag: String) = ReleaseInfo(tag, "", "", "", "a.apk", 1, "https://github.com/a.apk")

    @Test
    fun autoCheckAtMostOncePerDay() {
        assertTrue(UpdatePolicy.shouldAutoCheck(0, 1_000_000))
        assertFalse(UpdatePolicy.shouldAutoCheck(1_000_000, 1_000_000 + day - 1))
        assertTrue(UpdatePolicy.shouldAutoCheck(1_000_000, 1_000_000 + day))
    }

    @Test
    fun clockGoingBackwardsIsDue() {
        assertTrue(UpdatePolicy.shouldAutoCheck(5_000_000, 1_000))
    }

    @Test
    fun dismissedBannerStaysHiddenForThatVersionOnly() {
        assertTrue(UpdatePolicy.showBanner(release("v0.2.0"), null))
        assertFalse(UpdatePolicy.showBanner(release("v0.2.0"), "v0.2.0"))
        assertTrue(UpdatePolicy.showBanner(release("v0.3.0"), "v0.2.0"))
        assertFalse(UpdatePolicy.showBanner(null, null))
    }

    @Test
    fun onlyOurOwnHigherBuildIsAccepted() {
        assertTrue(UpdatePolicy.isNewerBuild("dev.clonelab.app", 402, "dev.clonelab.app", 401))
        assertFalse(UpdatePolicy.isNewerBuild("dev.clonelab.app", 401, "dev.clonelab.app", 401))
        assertFalse(UpdatePolicy.isNewerBuild("dev.clonelab.app", 400, "dev.clonelab.app", 401))
        assertFalse(UpdatePolicy.isNewerBuild("com.evil", 999, "dev.clonelab.app", 401))
        assertFalse(UpdatePolicy.isNewerBuild(null, 999, "dev.clonelab.app", 401))
    }

    @Test
    fun notesAreCutAtTheLimitAndFileNamesAreSafe() {
        val long = release("v1.0.0").copy(notes = "x".repeat(5000))
        val preview = long.notesPreview()
        assertEquals(ReleaseInfo.NOTES_LIMIT + 1, preview.length)
        assertTrue(preview.endsWith("…"))
        assertEquals("short", release("v1").copy(notes = " short \r\n").notesPreview())
        assertEquals("CloneLab-1.0.0-rc_1_.apk", release("v1.0.0-rc/1\\").fileName)
    }
}
