package dev.clonelab.app.model

data class CloneId(val packageName: String, val userId: Int)

enum class IconKind { APP, TEXT, IMAGE }

data class LocationConfig(val enabled: Boolean, val latitude: Double, val longitude: Double)

data class DeviceProfile(
    val presetName: String?,
    val brand: String?, val manufacturer: String?, val model: String?, val device: String?,
    val product: String?, val board: String?, val hardware: String?, val fingerprint: String?,
    val androidId: String?, val imei: String?, val serial: String?, val wifiMac: String?,
)

data class CloneRecord(
    val id: CloneId,
    val appLabel: String,            // label of the source app at install time
    val customName: String? = null,
    val sortOrder: Int,              // ascending; new clones go last
    val iconKind: IconKind = IconKind.APP,
    val iconColor: Int = 0,          // ARGB, used when iconKind == TEXT
    val iconImagePath: String? = null, // file in filesDir/icons, when IMAGE
    val keepAlive: Boolean = false,
    val location: LocationConfig? = null,
    val deviceProfile: DeviceProfile? = null,
    val createdAt: Long,
    val iconVersion: Long = 0,       // bumped on every icon change so equal-looking records still rebind
) {
    val number: Int get() = id.userId + 1
    val displayName: String get() = customName ?: "$appLabel $number"
}
