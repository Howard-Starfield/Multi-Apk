package dev.clonelab.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.clonelab.app.data.CloneItem
import dev.clonelab.app.data.CloneRepository
import dev.clonelab.app.model.CloneId
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
}

class CloneDetailViewModel(
    private val id: CloneId,
    private val repository: CloneRepository,
) : ViewModel() {

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
            events.send(DetailEvent.Closed)
        }
    }
}
