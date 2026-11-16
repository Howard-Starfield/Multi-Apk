package dev.clonelab.app.location

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.clonelab.app.data.CloneItem
import dev.clonelab.app.data.CloneRepository
import dev.clonelab.app.model.CloneId
import dev.clonelab.app.model.LocationConfig
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import dev.clonelab.app.ui.UserMessage
import dev.clonelab.app.ui.launchGuarded

sealed interface LocationEvent {
    data object Saved : LocationEvent
    data class Failed(val message: UserMessage) : LocationEvent
    data class Invalid(val problem: CoordinateProblem) : LocationEvent
}

class LocationViewModel(
    private val id: CloneId,
    private val repository: CloneRepository,
) : ViewModel() {

    /** The clone's current state, or null until loaded (or once removed). */
    val item: StateFlow<CloneItem?> = repository.clones
        .map { items -> items.firstOrNull { it.record.id == id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val events = Channel<LocationEvent>(Channel.BUFFERED)
    val messages: Flow<LocationEvent> = events.receiveAsFlow()

    init {
        guarded { repository.refreshWhenReady() }
    }

    /**
     * Saves the location. When it is switched on the coordinates must be valid. When it is off they are
     * kept if they parse, so switching it on again later restores them.
     */
    fun save(enabled: Boolean, latitude: String, longitude: String) {
        val parsed = Coordinates.parse(latitude, longitude)
        val config = when {
            parsed is CoordinateResult.Valid -> LocationConfig(enabled, parsed.latitude, parsed.longitude)
            enabled -> {
                events.trySend(LocationEvent.Invalid((parsed as CoordinateResult.Invalid).problem))
                return
            }
            else -> null
        }
        guarded {
            repository.update(id) { it.copy(location = config) }
            events.send(LocationEvent.Saved)
        }
    }

    fun stopNow() {
        guarded { repository.stop(id) }
    }

    private fun guarded(block: suspend CoroutineScope.() -> Unit) {
        launchGuarded({ events.send(LocationEvent.Failed(it)) }, block)
    }
}
