package dev.clonelab.app.ui.add

import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.clonelab.app.data.CloneRepository
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

data class InstalledApp(val packageName: String, val label: String)

data class AddState(val apps: List<InstalledApp> = emptyList(), val installing: Boolean = false)

sealed interface AddEvent {
    data object Done : AddEvent
    data class Failed(val message: String) : AddEvent
}

class AddCloneViewModel(
    private val repository: CloneRepository,
    private val packageManager: PackageManager,
    private val ownPackage: String,
) : ViewModel() {

    private val allApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    private val query = MutableStateFlow("")
    private val installing = MutableStateFlow(false)
    private val events = Channel<AddEvent>(Channel.BUFFERED)

    val state: StateFlow<AddState> = combine(allApps, query, installing) { apps, q, busy ->
        AddState(apps.filter { it.label.contains(q.trim(), ignoreCase = true) }, busy)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AddState())

    val results: Flow<AddEvent> = events.receiveAsFlow()

    init {
        viewModelScope.launch { allApps.value = withContext(Dispatchers.IO) { loadLaunchableApps() } }
    }

    fun setQuery(text: String) {
        query.value = text
    }

    fun clone(app: InstalledApp) = install { repository.addFromInstalledApp(app.packageName, app.label) }

    fun cloneApk(uri: String, fallbackLabel: String) = install { repository.addFromApk(uri, fallbackLabel) }

    private fun install(block: suspend () -> Result<*>) {
        if (installing.value) return
        installing.value = true
        viewModelScope.launch {
            val result = block()
            installing.value = false
            events.send(
                result.fold(
                    onSuccess = { AddEvent.Done },
                    onFailure = { AddEvent.Failed(it.message ?: "Install failed.") },
                ),
            )
        }
    }

    private fun loadLaunchableApps(): List<InstalledApp> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcher, 0)
            .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(packageManager).toString()) }
            .filter { it.packageName != ownPackage }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}
