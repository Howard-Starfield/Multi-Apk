package dev.clonelab.app.ui.group

import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.clonelab.app.R
import dev.clonelab.app.data.CloneItem
import dev.clonelab.app.data.CloneRepository
import dev.clonelab.app.model.CloneId
import dev.clonelab.app.ui.UserMessage
import dev.clonelab.app.ui.launchGuarded
import dev.clonelab.app.ui.toUserMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** [loaded] is false until the first refresh finished: an empty list before that is not "all clones removed". */
data class GroupState(
    val items: List<CloneItem> = emptyList(),
    val loaded: Boolean = false,
    val adding: Boolean = false,
)

class CloneGroupViewModel(
    private val repository: CloneRepository,
    private val packageManager: PackageManager,
    private val packageName: String,
) : ViewModel() {

    private val loaded = MutableStateFlow(false)
    private val adding = MutableStateFlow(false)
    private val errors = Channel<UserMessage>(Channel.BUFFERED)

    val state: StateFlow<GroupState> = combine(repository.clones, loaded, adding) { all, isLoaded, busy ->
        GroupState(all.filter { it.record.id.packageName == packageName }, isLoaded, busy)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, GroupState())

    /** One-shot error messages for the UI to show. */
    val messages: Flow<UserMessage> = errors.receiveAsFlow()

    init {
        launchGuarded(::report) {
            try {
                repository.refreshWhenReady()
            } finally {
                loaded.value = true
            }
        }
    }

    fun refresh() {
        if (loaded.value) launchGuarded(::report) { repository.refresh() }
    }

    fun launch(id: CloneId) {
        launchGuarded(::report) {
            repository.launch(id).onFailure { errors.send(it.toUserMessage(R.string.error_launch_failed)) }
        }
    }

    /** Persists a new order of this app's clones; the clones of other apps keep their places. */
    fun reorder(groupOrder: List<CloneId>) {
        launchGuarded(::report) {
            val all = repository.clones.value.map { it.record.id }
            repository.reorder(mergeGroupOrder(all, packageName, groupOrder))
        }
    }

    /** Creates one more clone of this app, without asking which app. */
    fun addClone() {
        if (adding.value) return
        adding.value = true
        launchGuarded(::report) {
            try {
                val onPhone = withContext(Dispatchers.IO) { isInstalledOnPhone() }
                repository.addAnotherClone(packageName, onPhone)
                    .onFailure { errors.send(it.toUserMessage(R.string.error_install_failed)) }
            } finally {
                adding.value = false
            }
        }
    }

    private fun isInstalledOnPhone(): Boolean =
        try {
            packageManager.getApplicationInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    private suspend fun report(message: UserMessage) {
        errors.send(message)
    }
}
