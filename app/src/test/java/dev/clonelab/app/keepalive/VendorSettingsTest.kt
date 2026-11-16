package dev.clonelab.app.keepalive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorSettingsTest {
    @Test
    fun knownBrandsHaveTargets() {
        for (brand in listOf("Xiaomi", "Redmi", "POCO", "HUAWEI", "HONOR", "OPPO", "realme", "OnePlus", "vivo", "iQOO", "samsung")) {
            assertTrue("$brand has no targets", VendorSettings.autoStartTargets(brand).isNotEmpty())
            assertEquals(VendorSettings.autoStartTargets(brand).size, VendorSettings.autoStartIntents(brand).size)
        }
    }

    @Test
    fun brandsOfOneFamilyShareTargets() {
        assertEquals(VendorSettings.autoStartTargets("Xiaomi"), VendorSettings.autoStartTargets("POCO"))
        assertEquals(VendorSettings.autoStartTargets("huawei"), VendorSettings.autoStartTargets("Honor"))
        assertEquals(VendorSettings.autoStartTargets("vivo"), VendorSettings.autoStartTargets("iQOO"))
    }

    @Test
    fun firstTargetsPointAtTheMakersOwnPackage() {
        assertEquals("com.miui.securitycenter", VendorSettings.autoStartTargets("Xiaomi").first().packageName)
        assertEquals("com.huawei.systemmanager", VendorSettings.autoStartTargets("HUAWEI").first().packageName)
        assertEquals("com.oneplus.security", VendorSettings.autoStartTargets("OnePlus").first().packageName)
        assertEquals("com.coloros.safecenter", VendorSettings.autoStartTargets("realme").first().packageName)
        assertEquals("com.vivo.permissionmanager", VendorSettings.autoStartTargets("vivo").first().packageName)
        assertEquals("com.samsung.android.lool", VendorSettings.autoStartTargets("samsung").first().packageName)
    }

    @Test
    fun unknownOrBlankBrandIsEmpty() {
        assertTrue(VendorSettings.autoStartIntents("Google").isEmpty())
        assertTrue(VendorSettings.autoStartIntents("").isEmpty())
        assertTrue(VendorSettings.autoStartTargets("  ").isEmpty())
    }
}
