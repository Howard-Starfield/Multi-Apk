package dev.clonelab.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAppTest {
    private fun app(pkg: String, label: String, installed: Long = 0, system: Boolean = false, files: Int = 1) =
        PhoneApp(pkg, label, "1.0", installed, List(files) { "/data/app/$pkg/$it.apk" }, 10, system)

    @Test
    fun filterMatchesLabelOrPackageIgnoringCaseAndHidesSystemApps() {
        val apps = listOf(app("org.signal", "Signal"), app("com.android.phone", "Phone", system = true), app("x.maps", "Maps"))
        assertEquals(listOf("org.signal"), filterApps(apps, " SIG ", showSystem = false).map { it.packageName })
        assertEquals(listOf("x.maps"), filterApps(apps, "x.MA", showSystem = false).map { it.packageName })
        assertEquals(2, filterApps(apps, "", showSystem = false).size)
        assertEquals(3, filterApps(apps, "", showSystem = true).size)
    }

    @Test
    fun sortsByNameThenPackage() {
        val apps = listOf(app("b", "beta"), app("c", "Alpha"), app("a", "alpha"))
        assertEquals(listOf("a", "c", "b"), sortApps(apps, AppSort.NAME).map { it.packageName })
    }

    @Test
    fun sortsByNewestInstallFirst() {
        val apps = listOf(app("a", "A", installed = 1), app("c", "C", installed = 5), app("b", "B", installed = 5))
        assertEquals(listOf("b", "c", "a"), sortApps(apps, AppSort.INSTALL_DATE).map { it.packageName })
    }

    @Test
    fun splitMeansMoreThanOneFile() {
        assertFalse(app("a", "A").isSplit)
        assertTrue(app("a", "A", files = 3).isSplit)
    }
}
