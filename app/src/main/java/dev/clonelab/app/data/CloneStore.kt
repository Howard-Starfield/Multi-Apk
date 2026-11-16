package dev.clonelab.app.data

import dev.clonelab.app.model.CloneId
import dev.clonelab.app.model.CloneRecord
import dev.clonelab.app.model.DeviceProfile
import dev.clonelab.app.model.IconKind
import dev.clonelab.app.model.LocationConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Persists clone records as one JSON file, replaced atomically on every write. */
class CloneStore(private val file: File) {
    private val lock = Any()

    fun all(): List<CloneRecord> = synchronized(lock) { read() }

    fun get(id: CloneId): CloneRecord? = synchronized(lock) { read().firstOrNull { it.id == id } }

    fun put(record: CloneRecord) = synchronized(lock) {
        val records = read().filterNot { it.id == record.id } + record
        write(records)
    }

    fun remove(id: CloneId) = synchronized(lock) {
        write(read().filterNot { it.id == id })
    }

    /** Listed clones take positions 0..n-1 in the given order; any others follow in their old order. */
    fun reorder(idsInOrder: List<CloneId>) = synchronized(lock) {
        val records = read()
        val byId = records.associateBy { it.id }
        val listed = idsInOrder.mapNotNull { byId[it] }
        val rest = records.filterNot { it.id in idsInOrder }
        write((listed + rest).mapIndexed { index, record -> record.copy(sortOrder = index) })
    }

    private fun read(): List<CloneRecord> {
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONObject(file.readText()).getJSONArray(KEY_CLONES)
            (0 until array.length()).map { recordFromJson(array.getJSONObject(it)) }
                .sortedWith(compareBy({ it.sortOrder }, { it.createdAt }))
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun write(records: List<CloneRecord>) {
        val json = JSONObject().put(KEY_CLONES, JSONArray(records.map(::recordToJson)))
        file.absoluteFile.parentFile?.mkdirs()
        val temp = File(file.absolutePath + ".tmp")
        temp.writeText(json.toString())
        if (!temp.renameTo(file)) {
            // Some file systems refuse to rename over an existing file.
            file.delete()
            if (!temp.renameTo(file)) throw IOException("Could not replace ${file.name}")
        }
    }

    private fun recordToJson(r: CloneRecord) = JSONObject()
        .put("packageName", r.id.packageName)
        .put("userId", r.id.userId)
        .put("appLabel", r.appLabel)
        .put("customName", r.customName ?: JSONObject.NULL)
        .put("sortOrder", r.sortOrder)
        .put("iconKind", r.iconKind.name)
        .put("iconColor", r.iconColor)
        .put("iconImagePath", r.iconImagePath ?: JSONObject.NULL)
        .put("keepAlive", r.keepAlive)
        .put("location", r.location?.let(::locationToJson) ?: JSONObject.NULL)
        .put("deviceProfile", r.deviceProfile?.let(::profileToJson) ?: JSONObject.NULL)
        .put("createdAt", r.createdAt)

    private fun recordFromJson(o: JSONObject) = CloneRecord(
        id = CloneId(o.getString("packageName"), o.getInt("userId")),
        appLabel = o.getString("appLabel"),
        customName = o.optStringOrNull("customName"),
        sortOrder = o.getInt("sortOrder"),
        iconKind = IconKind.valueOf(o.getString("iconKind")),
        iconColor = o.getInt("iconColor"),
        iconImagePath = o.optStringOrNull("iconImagePath"),
        keepAlive = o.getBoolean("keepAlive"),
        location = if (o.isNull("location")) null else locationFromJson(o.getJSONObject("location")),
        deviceProfile = if (o.isNull("deviceProfile")) null else profileFromJson(o.getJSONObject("deviceProfile")),
        createdAt = o.getLong("createdAt"),
    )

    private fun locationToJson(l: LocationConfig) = JSONObject()
        .put("enabled", l.enabled).put("latitude", l.latitude).put("longitude", l.longitude)

    private fun locationFromJson(o: JSONObject) =
        LocationConfig(o.getBoolean("enabled"), o.getDouble("latitude"), o.getDouble("longitude"))

    private fun profileToJson(p: DeviceProfile) = JSONObject()
        .put("presetName", p.presetName ?: JSONObject.NULL)
        .put("brand", p.brand ?: JSONObject.NULL)
        .put("manufacturer", p.manufacturer ?: JSONObject.NULL)
        .put("model", p.model ?: JSONObject.NULL)
        .put("device", p.device ?: JSONObject.NULL)
        .put("product", p.product ?: JSONObject.NULL)
        .put("board", p.board ?: JSONObject.NULL)
        .put("hardware", p.hardware ?: JSONObject.NULL)
        .put("fingerprint", p.fingerprint ?: JSONObject.NULL)
        .put("androidId", p.androidId ?: JSONObject.NULL)
        .put("imei", p.imei ?: JSONObject.NULL)
        .put("serial", p.serial ?: JSONObject.NULL)
        .put("wifiMac", p.wifiMac ?: JSONObject.NULL)

    private fun profileFromJson(o: JSONObject) = DeviceProfile(
        presetName = o.optStringOrNull("presetName"),
        brand = o.optStringOrNull("brand"),
        manufacturer = o.optStringOrNull("manufacturer"),
        model = o.optStringOrNull("model"),
        device = o.optStringOrNull("device"),
        product = o.optStringOrNull("product"),
        board = o.optStringOrNull("board"),
        hardware = o.optStringOrNull("hardware"),
        fingerprint = o.optStringOrNull("fingerprint"),
        androidId = o.optStringOrNull("androidId"),
        imei = o.optStringOrNull("imei"),
        serial = o.optStringOrNull("serial"),
        wifiMac = o.optStringOrNull("wifiMac"),
    )

    private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else getString(key)

    private companion object {
        const val KEY_CLONES = "clones"
    }
}
