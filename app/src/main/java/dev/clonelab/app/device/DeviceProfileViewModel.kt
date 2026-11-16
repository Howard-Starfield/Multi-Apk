package dev.clonelab.app.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.clonelab.app.data.CloneItem
import dev.clonelab.app.data.CloneRepository
import dev.clonelab.app.model.CloneId
import dev.clonelab.app.model.DeviceProfile
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import dev.clonelab.app.ui.UserMessage
import dev.clonelab.app.ui.launchGuarded
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/** What the screen is editing. A null [presetName] means "use the real device". */
data class DeviceDraft(
    val presetName: String? = null,
    val androidId: String = "",
    val imei: String = "",
    val serial: String = "",
    val wifiMac: String = "",
)

enum class IdentifierField { ANDROID_ID, IMEI, SERIAL, WIFI_MAC }

sealed interface DeviceEvent {
    data object Saved : DeviceEvent
    data class Failed(val message: UserMessage) : DeviceEvent
    data class Invalid(val fields: Set<IdentifierField>) : DeviceEvent
}

class DeviceProfileViewModel(
    private val id: CloneId,
    private val repository: CloneRepository,
    private val random: Random = SecureRandom().asKotlinRandom(),
) : ViewModel() {

    val item: StateFlow<CloneItem?> = repository.clones
        .map { items -> items.firstOrNull { it.record.id == id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Null until the stored profile has been loaded. */
    private val _draft = MutableStateFlow<DeviceDraft?>(null)
    val draft: StateFlow<DeviceDraft?> = _draft

    private val events = Channel<DeviceEvent>(Channel.BUFFERED)
    val messages: Flow<DeviceEvent> = events.receiveAsFlow()

    init {
        guarded {
            repository.refreshWhenReady()
            val stored = repository.clones.value.firstOrNull { it.record.id == id }?.record?.deviceProfile
            _draft.value = if (stored == null) {
                DeviceDraft()
            } else {
                DeviceDraft(
                    presetName = stored.presetName,
                    androidId = stored.androidId.orEmpty(),
                    imei = stored.imei.orEmpty(),
                    serial = stored.serial.orEmpty(),
                    wifiMac = stored.wifiMac.orEmpty(),
                )
            }
        }
    }

    /** Picks a preset, or the real device for null. A first-time preset gets fresh identifiers. */
    fun select(presetName: String?) {
        val current = _draft.value ?: return
        if (presetName == current.presetName) return
        val hasIdentifiers = current.androidId.isNotEmpty() || current.imei.isNotEmpty() ||
            current.serial.isNotEmpty() || current.wifiMac.isNotEmpty()
        _draft.value = if (presetName != null && !hasIdentifiers) {
            fromIdentifiers(presetName, DeviceProfiles.randomIdentifiers(random))
        } else {
            current.copy(presetName = presetName)
        }
    }

    fun randomise() {
        val current = _draft.value ?: return
        _draft.value = fromIdentifiers(current.presetName, DeviceProfiles.randomIdentifiers(random))
    }

    fun edit(field: IdentifierField, text: String) {
        val current = _draft.value ?: return
        val updated = when (field) {
            IdentifierField.ANDROID_ID -> current.copy(androidId = text)
            IdentifierField.IMEI -> current.copy(imei = text)
            IdentifierField.SERIAL -> current.copy(serial = text)
            IdentifierField.WIFI_MAC -> current.copy(wifiMac = text)
        }
        if (updated != current) _draft.value = updated
    }

    /** Stores the profile. An empty identifier keeps the real value; a filled one must be valid. */
    fun save() {
        val draft = _draft.value ?: return
        val preset = DeviceProfiles.presets.firstOrNull { it.presetName == draft.presetName }
        if (preset == null) {
            persist(null)
            return
        }
        val invalid = buildSet {
            if (!draft.androidId.isEmpty() && !DeviceProfiles.isValidAndroidId(draft.androidId)) add(IdentifierField.ANDROID_ID)
            if (!draft.imei.isEmpty() && !DeviceProfiles.isValidImei(draft.imei)) add(IdentifierField.IMEI)
            if (!draft.serial.isEmpty() && !DeviceProfiles.isValidSerial(draft.serial)) add(IdentifierField.SERIAL)
            if (!draft.wifiMac.isEmpty() && !DeviceProfiles.isValidMac(draft.wifiMac)) add(IdentifierField.WIFI_MAC)
        }
        if (invalid.isNotEmpty()) {
            events.trySend(DeviceEvent.Invalid(invalid))
            return
        }
        persist(
            preset.copy(
                androidId = draft.androidId.ifEmpty { null },
                imei = draft.imei.ifEmpty { null },
                serial = draft.serial.ifEmpty { null },
                wifiMac = draft.wifiMac.ifEmpty { null },
            ),
        )
    }

    fun stopNow() {
        guarded { repository.stop(id) }
    }

    private fun persist(profile: DeviceProfile?) {
        guarded {
            repository.update(id) { it.copy(deviceProfile = profile) }
            events.send(DeviceEvent.Saved)
        }
    }

    private fun guarded(block: suspend CoroutineScope.() -> Unit) {
        launchGuarded({ events.send(DeviceEvent.Failed(it)) }, block)
    }

    private fun fromIdentifiers(presetName: String?, ids: Identifiers) = DeviceDraft(
        presetName = presetName,
        androidId = ids.androidId,
        imei = ids.imei,
        serial = ids.serial,
        wifiMac = ids.wifiMac,
    )
}
