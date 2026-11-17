package dev.clonelab.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.clonelab.app.data.CloneRepository
import dev.clonelab.app.engine.CloneEngine
import dev.clonelab.app.ui.UserMessage
import dev.clonelab.app.ui.launchGuarded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.withContext

data class HomeState(val groups: List<AppGroup> = emptyList(), val engineFailed: Boolean = false)

class HomeViewModel(
    private val repository: CloneRepository,
    private val engine: CloneEngine,
) : ViewModel() {

    private val engineReady = MutableStateFlow<Boolean?>(null)
    private val errors = Channel<UserMessage>(Channel.BUFFERED)

    val state: StateFlow<HomeState> = combine(
        repository.clones, engineReady, repository.engineAvailable,
    ) { items, ready, available ->
        HomeState(groupByApp(items), engineFailed = ready == false || !available)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeState())

    /** One-shot error messages for the UI to show. */
    val messages: Flow<UserMessage> = errors.receiveAsFlow()

    init {
        checkEngine()
    }

    fun checkEngine() {
        launchGuarded(::report) {
            engineReady.value = withContext(Dispatchers.IO) { engine.awaitReady(ENGINE_TIMEOUT_MS) }
            repository.refresh()
        }
    }

    fun refresh() {
        if (engineReady.value == true) launchGuarded(::report) { repository.refresh() }
    }

    private suspend fun report(message: UserMessage) {
        errors.send(message)
    }

    private companion object {
        const val ENGINE_TIMEOUT_MS = 10_000L
    }
}
