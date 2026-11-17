package dev.clonelab.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.clonelab.app.data.CloneItem
import dev.clonelab.app.data.CloneRepository
import dev.clonelab.app.data.GoogleServicesStatus
import android.net.Uri
import dev.clonelab.app.model.CloneId
import dev.clonelab.app.model.CloneRecord
import dev.clonelab.app.model.IconKind
import dev.clonelab.app.shortcut.ShortcutPublisher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import dev.clonelab.app.R
import dev.clonelab.app.ui.UserMessage
import dev.clonelab.app.ui.launchGuarded
import dev.clonelab.app.ui.toUserMessage

sealed interface DetailEvent {
    data class Error(val message: UserMessage) : DetailEvent
    data object Closed : DetailEvent
    data object ImageFailed : DetailEvent
    data object PinUnsupported : DetailEvent
    data object PinFailed : DetailEvent
}

/**
 * The "Google services" section. [status] is null while unknown (engine unreachable); [sharedWith] are the
 * other clones that use the same clone number and so share the setting.
 */
data class GoogleUi(
    val status: GoogleServicesStatus? = null,
    val busy: Boolean = false,
    val sharedWith: List<CloneRecord> = emptyList(),
)

class CloneDetailViewModel(
    private val id: CloneId,
    private val repository: CloneRepository,
    private val shortcuts: ShortcutPublisher,
    private val iconImages: IconImageStore,
    /** Called after the stored keep-alive flags changed, so the service can start or stop. */
    private val syncKeepAlive: () -> Unit = {},
) : ViewModel() {

    val cloneId: CloneId get() = id

    /** The clone's current state, or null once it has been removed. */
    val item: StateFlow<CloneItem?> = repository.clones
        .map { items -> items.firstOrNull { it.record.id == id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val googleStatus = MutableStateFlow<GoogleServicesStatus?>(null)
    private val googleBusy = MutableStateFlow(false)
    val google: StateFlow<GoogleUi> = combine(googleStatus, googleBusy, repository.clones) { status, busy, items ->
        GoogleUi(
            status,
            busy,
            items.map { it.record }.filter { it.id.userId == id.userId && it.id != id },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, GoogleUi())

    private val events = Channel<DetailEvent>(Channel.BUFFERED)
    val messages: Flow<DetailEvent> = events.receiveAsFlow()

    fun refresh() {
        guarded {
            repository.refreshWhenReady()
            if (!googleBusy.value) googleStatus.value = repository.googleServicesStatus(id.userId)
        }
    }

    /** Gives every clone with this clone's number its own Google services, or takes them away. */
    fun setGoogleServices(enabled: Boolean) {
        if (googleBusy.value) return
        googleBusy.value = true
        guarded {
            try {
                repository.setGoogleServices(id.userId, enabled)
                    .onFailure { events.send(DetailEvent.Error(it.toUserMessage(R.string.error_google_failed))) }
                googleStatus.value = repository.googleServicesStatus(id.userId)
            } finally {
                googleBusy.value = false
            }
        }
    }

    fun launch() {
        guarded {
            repository.launch(id).onFailure { events.send(DetailEvent.Error(it.toUserMessage(R.string.error_launch_failed))) }
        }
    }

    fun stop() {
        guarded { repository.stop(id) }
    }

    fun clearData() {
        guarded {
            repository.clearData(id).onFailure { events.send(DetailEvent.Error(it.toUserMessage(R.string.error_clear_data_failed))) }
        }
    }

    /** Uninstalls the clone and forgets it; also used to remove a broken record. */
    fun remove() {
        guarded {
            repository.remove(id).fold(
                onSuccess = {
                    shortcuts.disable(id)
                    syncKeepAlive()
                    events.send(DetailEvent.Closed)
                },
                onFailure = { events.send(DetailEvent.Error(it.toUserMessage(R.string.error_remove_failed))) },
            )
        }
    }

    fun setKeepAlive(enabled: Boolean) {
        guarded {
            repository.update(id) { it.copy(keepAlive = enabled) }
            syncKeepAlive()
        }
    }

    /** A blank name resets to the default "<app> <number>". */
    fun rename(name: String) {
        edit { it.copy(customName = name.trim().ifEmpty { null }) }
    }

    fun useAppIcon() {
        edit { it.copy(iconKind = IconKind.APP, iconImagePath = null, iconVersion = it.iconVersion + 1) }
        deleteImage()
    }

    fun useTextIcon(color: Int) {
        edit { it.copy(iconKind = IconKind.TEXT, iconColor = color, iconImagePath = null, iconVersion = it.iconVersion + 1) }
        deleteImage()
    }

    fun useImageIcon(source: Uri) {
        guarded {
            val path = withContext(Dispatchers.IO) { iconImages.save(source, id) }
            if (path == null) {
                events.send(DetailEvent.ImageFailed)
                return@guarded
            }
            // The file name is fixed per clone, so a replaced image keeps its path and the record would
            // compare equal; the version bump is what makes the UI reload the picture.
            edit { it.copy(iconKind = IconKind.IMAGE, iconImagePath = path, iconVersion = it.iconVersion + 1) }
        }
    }

    /** Pins a launcher shortcut; reports why it could not when the launcher says no. */
    fun pinShortcut() {
        val record = item.value?.record ?: return
        guarded {
            val message = when {
                !shortcuts.isSupported() -> DetailEvent.PinUnsupported
                !shortcuts.pin(record) -> DetailEvent.PinFailed
                else -> null
            }
            message?.let { events.send(it) }
        }
    }

    private fun guarded(block: suspend CoroutineScope.() -> Unit) {
        launchGuarded({ events.send(DetailEvent.Error(it)) }, block)
    }

    private fun edit(transform: (CloneRecord) -> CloneRecord) {
        guarded {
            repository.update(id, transform)
            repository.clones.value.firstOrNull { it.record.id == id }?.let {
                withContext(Dispatchers.Default) { shortcuts.update(it.record) }
            }
        }
    }

    private fun deleteImage() {
        val path = item.value?.record?.iconImagePath
        if (path != null) viewModelScope.launch(Dispatchers.IO) { iconImages.delete(path) }
    }
}
