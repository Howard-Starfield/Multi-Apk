package dev.clonelab.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.clonelab.app.data.CloneItem
import dev.clonelab.app.data.CloneRepository
import android.net.Uri
import dev.clonelab.app.model.CloneId
import dev.clonelab.app.model.CloneRecord
import dev.clonelab.app.model.IconKind
import dev.clonelab.app.shortcut.ShortcutPublisher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface DetailEvent {
    data class Error(val message: String) : DetailEvent
    data object Closed : DetailEvent
    data object ImageFailed : DetailEvent
    data object PinUnsupported : DetailEvent
    data object PinFailed : DetailEvent
}

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

    private val events = Channel<DetailEvent>(Channel.BUFFERED)
    val messages: Flow<DetailEvent> = events.receiveAsFlow()

    fun refresh() {
        viewModelScope.launch { repository.refresh() }
    }

    fun launch() {
        viewModelScope.launch {
            repository.launch(id).onFailure { events.send(DetailEvent.Error(it.message ?: "Launch failed.")) }
        }
    }

    fun stop() {
        viewModelScope.launch { repository.stop(id) }
    }

    fun clearData() {
        viewModelScope.launch { repository.clearData(id) }
    }

    /** Uninstalls the clone and forgets it; also used to remove a broken record. */
    fun remove() {
        viewModelScope.launch {
            repository.remove(id)
            shortcuts.disable(id)
            syncKeepAlive()
            events.send(DetailEvent.Closed)
        }
    }

    fun setKeepAlive(enabled: Boolean) {
        viewModelScope.launch {
            repository.update(id) { it.copy(keepAlive = enabled) }
            syncKeepAlive()
        }
    }

    /** A blank name resets to the default "<app> <number>". */
    fun rename(name: String) {
        edit { it.copy(customName = name.trim().ifEmpty { null }) }
    }

    fun useAppIcon() {
        edit { it.copy(iconKind = IconKind.APP, iconImagePath = null) }
        deleteImage()
    }

    fun useTextIcon(color: Int) {
        edit { it.copy(iconKind = IconKind.TEXT, iconColor = color, iconImagePath = null) }
        deleteImage()
    }

    fun useImageIcon(source: Uri) {
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) { iconImages.save(source, id) }
            if (path == null) {
                events.send(DetailEvent.ImageFailed)
                return@launch
            }
            // The file name is fixed per clone, so a replaced image keeps its path; touch the record anyway.
            edit { it.copy(iconKind = IconKind.IMAGE, iconImagePath = path) }
        }
    }

    /** Pins a launcher shortcut; reports why it could not when the launcher says no. */
    fun pinShortcut() {
        val record = item.value?.record ?: return
        viewModelScope.launch {
            val message = when {
                !shortcuts.isSupported() -> DetailEvent.PinUnsupported
                !shortcuts.pin(record) -> DetailEvent.PinFailed
                else -> null
            }
            message?.let { events.send(it) }
        }
    }

    private fun edit(transform: (CloneRecord) -> CloneRecord) {
        viewModelScope.launch {
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
