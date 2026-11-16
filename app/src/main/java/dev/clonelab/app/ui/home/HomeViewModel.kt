package dev.clonelab.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.clonelab.app.data.CloneItem
import dev.clonelab.app.data.CloneRepository
import dev.clonelab.app.engine.CloneEngine
import dev.clonelab.app.model.CloneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeState(val items: List<CloneItem> = emptyList(), val engineFailed: Boolean = false)

class HomeViewModel(
    private val repository: CloneRepository,
    private val engine: CloneEngine,
) : ViewModel() {

    private val engineReady = MutableStateFlow<Boolean?>(null)
    private val errors = Channel<String>(Channel.BUFFERED)

    val state: StateFlow<HomeState> = combine(repository.clones, engineReady) { items, ready ->
        HomeState(items, engineFailed = ready == false)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeState())

    /** One-shot error messages for the UI to show. */
    val messages: Flow<String> = errors.receiveAsFlow()

    init {
        checkEngine()
    }

    fun checkEngine() {
        viewModelScope.launch {
            engineReady.value = withContext(Dispatchers.IO) { engine.awaitReady(ENGINE_TIMEOUT_MS) }
            refresh()
        }
    }

    fun refresh() {
        if (engineReady.value == true) viewModelScope.launch { repository.refresh() }
    }

    fun launch(id: CloneId) {
        viewModelScope.launch {
            repository.launch(id).onFailure { errors.send(it.message ?: "Launch failed.") }
        }
    }

    fun reorder(ids: List<CloneId>) {
        viewModelScope.launch { repository.reorder(ids) }
    }

    private companion object {
        const val ENGINE_TIMEOUT_MS = 10_000L
    }
}
