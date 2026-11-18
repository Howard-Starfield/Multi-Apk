package dev.clonelab.app.tools.uninstall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.clonelab.app.tools.AppSort
import dev.clonelab.app.tools.PhoneApp
import dev.clonelab.app.tools.PhoneApps
import dev.clonelab.app.tools.filterApps
import dev.clonelab.app.tools.sortApps
import dev.clonelab.app.ui.selection.Selection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UninstallState(
    /** The user apps shown: filtered by the search and sorted. */
    val apps: List<PhoneApp> = emptyList(),
    val loading: Boolean = true,
    val selection: Selection<String> = Selection(),
    /** Packages that have clones in CloneLab. */
    val cloned: Set<String> = emptySet(),
    val sort: AppSort = AppSort.NAME,
    /** Non-null while a batch uninstall runs. */
    val queue: UninstallQueue? = null,
)

sealed interface UninstallEvent {
    /** Show the system uninstall dialog for this package; answer with [BatchUninstallViewModel.onDialogReturned]. */
    data class Launch(val packageName: String) : UninstallEvent

    data class Summary(val uninstalled: Int, val total: Int) : UninstallEvent
}

/**
 * Batch uninstall of apps installed on the phone (system apps are not offered). Android lets an app only
 * ask for an uninstall, so the selected apps go through the system dialog one after another; the queue
 * lives here so it survives rotation while a dialog is up.
 */
class BatchUninstallViewModel(
    private val phoneApps: PhoneApps,
    clonedPackages: Flow<Set<String>>,
) : ViewModel() {

    /** All user apps; null until loaded. */
    private val allApps = MutableStateFlow<List<PhoneApp>?>(null)
    private val query = MutableStateFlow("")
    private val sort = MutableStateFlow(AppSort.NAME)
    private val selection = MutableStateFlow(Selection<String>())
    private val queue = MutableStateFlow<UninstallQueue?>(null)
    private val events = Channel<UninstallEvent>(Channel.BUFFERED)

    /** The package whose dialog is showing, so a stray result cannot be counted twice. */
    private var awaiting: String? = null

    private val visible = combine(allApps, query, sort) { apps, q, order ->
        sortApps(filterApps(apps.orEmpty(), q, showSystem = false), order)
    }

    val state: StateFlow<UninstallState> = combine(
        visible,
        allApps,
        selection,
        clonedPackages,
        combine(sort, queue, ::Pair),
    ) { apps, all, selected, cloned, (order, running) ->
        UninstallState(apps, all == null, selected, cloned, order, running)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UninstallState())

    val uiEvents: Flow<UninstallEvent> = events.receiveAsFlow()

    init {
        viewModelScope.launch {
            allApps.value = withContext(Dispatchers.IO) { phoneApps.load().filter { !it.system } }
        }
    }

    fun setQuery(text: String) {
        query.value = text
    }

    fun setSort(order: AppSort) {
        sort.value = order
    }

    fun toggle(packageName: String) {
        if (queue.value == null) selection.value = selection.value.toggle(packageName)
    }

    fun selectAllShown() {
        if (queue.value != null) return
        selection.value = Selection(selection.value.selected + state.value.apps.map { it.packageName })
    }

    fun clearSelection() {
        if (queue.value == null) selection.value = Selection()
    }

    /** Starts the queue with the selected apps, in the order they are listed. */
    fun startUninstall() {
        if (queue.value != null) return
        val selected = selection.value.selected
        val packages = sortApps(allApps.value.orEmpty().filter { it.packageName in selected }, sort.value)
            .map { it.packageName }
        if (packages.isEmpty()) return
        queue.value = UninstallQueue(packages)
        advance()
    }

    /** Stops after the app whose dialog is showing now. */
    fun cancel() {
        queue.value = queue.value?.cancel()
    }

    /** The system dialog closed; [accepted] is whether it reported success. */
    fun onDialogReturned(accepted: Boolean) {
        if (queue.value == null) return
        val pkg = awaiting ?: return
        awaiting = null
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) { confirmRemoved(pkg, accepted, phoneApps::isInstalled) }
            if (removed) forget(pkg)
            queue.value = queue.value?.recordResult(removed)
            advance()
        }
    }

    private fun advance() {
        val current = queue.value ?: return
        val next = current.current
        if (next == null) {
            queue.value = null
            events.trySend(UninstallEvent.Summary(current.uninstalled.size, current.total))
            return
        }
        viewModelScope.launch {
            val gone = withContext(Dispatchers.IO) { !phoneApps.isInstalled(next) }
            if (queue.value?.cancelled == true) {
                advance()
                return@launch
            }
            if (gone) {
                // Already uninstalled from elsewhere: count it and go on without a dialog.
                queue.value = queue.value?.recordResult(true)
                forget(next)
                advance()
                return@launch
            }
            awaiting = next
            events.trySend(UninstallEvent.Launch(next))
        }
    }

    private fun forget(packageName: String) {
        allApps.value = allApps.value?.filter { it.packageName != packageName }
        selection.value = Selection(selection.value.selected - packageName)
    }
}
