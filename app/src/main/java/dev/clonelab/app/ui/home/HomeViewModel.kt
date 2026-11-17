package dev.clonelab.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.clonelab.app.data.CloneRepository
import dev.clonelab.app.engine.CloneEngine
import dev.clonelab.app.ui.UserMessage
import dev.clonelab.app.ui.launchGuarded
import dev.clonelab.app.ui.selection.Selection
import dev.clonelab.app.ui.selection.SelectionState
import dev.clonelab.app.ui.selection.toMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.withContext

data class HomeState(
    val groups: List<AppGroup> = emptyList(),
    val engineFailed: Boolean = false,
    /** The selected apps, by package name. */
    val selection: Selection<String> = Selection(),
    /** True while the clones of the selected apps are being uninstalled. */
    val removing: Boolean = false,
)

class HomeViewModel(
    private val repository: CloneRepository,
    private val engine: CloneEngine,
) : ViewModel() {

    private val engineReady = MutableStateFlow<Boolean?>(null)
    private val errors = Channel<UserMessage>(Channel.BUFFERED)

    private val selecting = SelectionState<String>()

    val state: StateFlow<HomeState> = combine(
        repository.clones, engineReady, repository.engineAvailable, selecting.selection, selecting.removing,
    ) { items, ready, available, selection, removing ->
        HomeState(groupByApp(items), ready == false || !available, selection, removing)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeState())

    /** One-shot error messages for the UI to show. */
    val messages: Flow<UserMessage> = errors.receiveAsFlow()

    init {
        // Apps whose clones all disappeared leave the selection.
        viewModelScope.launch {
            repository.clones.collect { items -> selecting.retainOnly(items.map { it.record.id.packageName }) }
        }
        checkEngine()
    }

    fun checkEngine() {
        launchGuarded(::report) {
            engineReady.value = withContext(Dispatchers.IO) { engine.awaitReady(ENGINE_TIMEOUT_MS) }
            repository.refresh()
        }
    }

    fun toggleSelected(packageName: String) = selecting.toggle(packageName)

    fun selectAll() = selecting.selectAll(state.value.groups.map { it.packageName })

    fun clearSelection() = selecting.clear()

    /** Uninstalls every clone of the selected apps one by one, carrying on after failures, and reports. */
    fun uninstallSelected() {
        launchGuarded(::report) {
            val result = selecting.removeSelected(
                cloneIds = { pkg -> repository.clones.value.map { it.record.id }.filter { it.packageName == pkg } },
                keyOf = { it.packageName },
                remove = { repository.remove(it).isSuccess },
            )
            result?.toMessage()?.let { errors.send(it) }
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
