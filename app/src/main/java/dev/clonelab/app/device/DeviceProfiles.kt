package dev.clonelab.app.device

import dev.clonelab.app.model.DeviceProfile
import kotlin.random.Random

data class Identifiers(val androidId: String, val imei: String, val serial: String, val wifiMac: String)

/** Preset phone models and generators/validators for per-clone identifiers. Presets carry no identifiers. */
object DeviceProfiles {

    private fun preset(
        name: String, brand: String, manufacturer: String, model: String, device: String,
        product: String, board: String, hardware: String, fingerprint: String,
    ) = DeviceProfile(
        presetName = name, brand = brand, manufacturer = manufacturer, model = model, device = device,
        product = product, board = board, hardware = hardware, fingerprint = fingerprint,
        androidId = null, imei = null, serial = null, wifiMac = null,
    )

    val presets: List<DeviceProfile> = listOf(
        preset("Google Pixel 8", "google", "Google", "Pixel 8", "shiba", "shiba", "shiba", "shiba",
            "google/shiba/shiba:14/UQ1A.240205.002/11224170:user/release-keys"),
        preset("Google Pixel 7", "google", "Google", "Pixel 7", "panther", "panther", "panther", "panther",
            "google/panther/panther:14/UP1A.231105.001/10817346:user/release-keys"),
        preset("Google Pixel 6", "google", "Google", "Pixel 6", "oriole", "oriole", "oriole", "oriole",
            "google/oriole/oriole:13/TQ3A.230901.001/10750268:user/release-keys"),
        preset("Samsung Galaxy S23", "samsung", "samsung", "SM-S911B", "dm1q", "dm1qxxx", "kalama", "qcom",
            "samsung/dm1qxxx/dm1q:14/UP1A.231005.007/S911BXXU3CWL1:user/release-keys"),
        preset("Samsung Galaxy S22", "samsung", "samsung", "SM-S901B", "r0s", "r0sxxx", "exynos2200", "s5e9925",
            "samsung/r0sxxx/r0s:13/TP1A.220624.014/S901BXXU4CWC1:user/release-keys"),
        preset("Samsung Galaxy A54 5G", "samsung", "samsung", "SM-A546B", "a54x", "a54xnsxx", "s5e8835", "s5e8835",
            "samsung/a54xnsxx/a54x:14/UP1A.231005.007/A546BXXU6CWL1:user/release-keys"),
        preset("Xiaomi 13", "Xiaomi", "Xiaomi", "2211133G", "fuxi", "fuxi_global", "fuxi", "qcom",
            "Xiaomi/fuxi_global/fuxi:14/UKQ1.230804.001/V816.0.3.0.UMCMIXM:user/release-keys"),
        preset("Redmi Note 12", "Redmi", "Xiaomi", "23021RAAEG", "tapas", "tapas_global", "tapas", "qcom",
            "Redmi/tapas_global/tapas:13/TKQ1.221114.001/V14.0.7.0.TMTMIXM:user/release-keys"),
        preset("OnePlus 11", "OnePlus", "OnePlus", "CPH2449", "OP594DL1", "CPH2449EEA", "kalama", "qcom",
            "OnePlus/CPH2449EEA/OP594DL1:14/UKQ1.230924.001/T.R4T2.1f2e3d4:user/release-keys"),
        preset("Motorola Moto G84 5G", "motorola", "motorola", "moto g84 5G", "bangkk", "bangkk_g", "bangkk", "qcom",
            "motorola/bangkk_g/bangkk:13/T1TBS33.1-45-2-2/6e1a2b:user/release-keys"),
    )

    private const val HEX = "0123456789abcdef"
    private const val SERIAL_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private val androidIdPattern = Regex("[0-9a-f]{16}")
    private val macPattern = Regex("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}")
    private val serialPattern = Regex("[A-Za-z0-9]{6,32}")

    fun randomIdentifiers(random: Random): Identifiers = Identifiers(
        androidId = String(CharArray(16) { HEX[random.nextInt(HEX.length)] }),
        imei = randomImei(random),
        serial = String(CharArray(12) { SERIAL_CHARS[random.nextInt(SERIAL_CHARS.length)] }),
        wifiMac = randomMac(random),
    )

    fun withFreshIdentifiers(profile: DeviceProfile, random: Random): DeviceProfile {
        val ids = randomIdentifiers(random)
        return profile.copy(androidId = ids.androidId, imei = ids.imei, serial = ids.serial, wifiMac = ids.wifiMac)
    }

    fun isValidAndroidId(value: String) = androidIdPattern.matches(value)

    fun isValidImei(value: String): Boolean = value.length == 15 && value.all { it in '0'..'9' } && luhnValid(value)

    /** A colon-separated MAC that is unicast (multicast bit clear); the locally administered bit is not required. */
    fun isValidMac(value: String): Boolean =
        macPattern.matches(value) && (value.substring(0, 2).toInt(16) and 0x01) == 0

    fun isValidSerial(value: String) = serialPattern.matches(value)

    private fun randomImei(random: Random): String {
        val body = buildString {
            append(random.nextInt(1, 10))
            repeat(13) { append(random.nextInt(10)) }
        }
        return body + luhnCheckDigit(body)
    }

    private fun randomMac(random: Random): String {
        val bytes = random.nextBytes(6)
        // Locally administered (bit 1 set), unicast (bit 0 clear).
        bytes[0] = ((bytes[0].toInt() and 0xFC) or 0x02).toByte()
        return bytes.joinToString(":") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** Check digit that makes [body] plus that digit pass the Luhn test. */
    private fun luhnCheckDigit(body: String): Int {
        var sum = 0
        // The last digit of body sits next to the check digit, so it is the first one doubled.
        body.reversed().forEachIndexed { index, c ->
            var d = c - '0'
            if (index % 2 == 0) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
        }
        return (10 - sum % 10) % 10
    }

    private fun luhnValid(value: String): Boolean {
        var sum = 0
        value.reversed().forEachIndexed { index, c ->
            var d = c - '0'
            if (index % 2 == 1) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
        }
        return sum % 10 == 0
    }
}
