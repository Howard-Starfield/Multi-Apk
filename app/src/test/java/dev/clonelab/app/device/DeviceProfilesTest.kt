package dev.clonelab.app.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DeviceProfilesTest {

    @Test
    fun generatedIdentifiersAreValidOverManySeeds() {
        for (seed in 0 until 1000) {
            val ids = DeviceProfiles.randomIdentifiers(Random(seed))
            assertEquals(16, ids.androidId.length)
            assertTrue(ids.androidId, DeviceProfiles.isValidAndroidId(ids.androidId))
            assertEquals(15, ids.imei.length)
            assertTrue(ids.imei, DeviceProfiles.isValidImei(ids.imei))
            assertEquals(12, ids.serial.length)
            assertTrue(ids.serial, ids.serial.all { it in 'A'..'Z' || it in '0'..'9' })
            assertTrue(ids.wifiMac, DeviceProfiles.isValidMac(ids.wifiMac))
        }
    }

    @Test
    fun macIsLocallyAdministeredAndUnicast() {
        for (seed in 0 until 1000) {
            val mac = DeviceProfiles.randomIdentifiers(Random(seed)).wifiMac
            val first = mac.substring(0, 2).toInt(16)
            assertEquals(mac, 2, first and 0x02)
            assertEquals(mac, 0, first and 0x01)
            assertEquals(mac, mac.lowercase(), mac)
        }
    }

    @Test
    fun seedsControlIdentifiers() {
        assertNotEquals(DeviceProfiles.randomIdentifiers(Random(1)), DeviceProfiles.randomIdentifiers(Random(2)))
        assertEquals(DeviceProfiles.randomIdentifiers(Random(7)), DeviceProfiles.randomIdentifiers(Random(7)))
    }

    @Test
    fun validatorsRejectBadValues() {
        assertTrue(DeviceProfiles.isValidImei("490154203237518")) // textbook Luhn-valid number
        assertFalse(DeviceProfiles.isValidImei("490154203237519"))
        assertFalse(DeviceProfiles.isValidImei("49015420323751"))
        assertFalse(DeviceProfiles.isValidImei("49015420323751a"))
        assertFalse(DeviceProfiles.isValidAndroidId("0123456789ABCDEF"))
        assertFalse(DeviceProfiles.isValidAndroidId("0123456789abcde"))
        assertFalse(DeviceProfiles.isValidAndroidId("0123456789abcdeg"))
        assertTrue(DeviceProfiles.isValidMac("02:1a:2b:3c:4d:5e"))
        assertFalse(DeviceProfiles.isValidMac("03:1a:2b:3c:4d:5e")) // multicast
        assertFalse(DeviceProfiles.isValidMac("02-1a-2b-3c-4d-5e"))
        assertFalse(DeviceProfiles.isValidMac("02:1a:2b:3c:4d"))
        assertTrue(DeviceProfiles.isValidSerial("AB12CD34EF56"))
        assertFalse(DeviceProfiles.isValidSerial("AB 12"))
    }

    @Test
    fun presetsAreCompleteHaveNoIdentifiersAndUniqueNames() {
        val presets = DeviceProfiles.presets
        assertTrue(presets.size in 8..12)
        assertEquals(presets.size, presets.map { it.presetName }.toSet().size)
        val shape = Regex("[^/]+/[^/]+/[^/:]+:\\d+/[^/]+/[^/:]+:user/release-keys")
        for (p in presets) {
            assertNotNull(p.presetName)
            listOf(p.brand, p.manufacturer, p.model, p.device, p.product, p.board, p.hardware, p.fingerprint).forEach {
                assertFalse(p.presetName, it.isNullOrBlank())
            }
            assertTrue(p.fingerprint, shape.matches(p.fingerprint!!))
            assertTrue(p.fingerprint, p.fingerprint!!.startsWith("${p.brand}/${p.product}/${p.device}:"))
            assertNull(p.androidId)
            assertNull(p.imei)
            assertNull(p.serial)
            assertNull(p.wifiMac)
        }
    }

    @Test
    fun withFreshIdentifiersKeepsModelAndFillsIdentifiers() {
        val preset = DeviceProfiles.presets.first()
        val profile = DeviceProfiles.withFreshIdentifiers(preset, Random(5))
        val expected = preset.copy(
            androidId = profile.androidId, imei = profile.imei, serial = profile.serial, wifiMac = profile.wifiMac,
        )
        assertEquals(expected, profile)
        assertTrue(DeviceProfiles.isValidImei(profile.imei!!))
        assertTrue(DeviceProfiles.isValidAndroidId(profile.androidId!!))
    }
}
