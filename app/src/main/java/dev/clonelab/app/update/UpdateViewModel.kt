package dev.clonelab.app.update

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.clonelab.app.R
import dev.clonelab.app.ui.UserMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.io.File
import java.io.IOException

data class DownloadProgress(val done: Long, val total: Long)

/** One-shot things for the screen to show or do. */
sealed interface UpdateEvent {
    data class UpToDate(val version: String) : UpdateEvent
    data class Error(val message: UserMessage) : UpdateEvent
    data class Install(val intent: Intent) : UpdateEvent
}

/** Check, offer, download, hand over to the system installer. Lives across rotation; closing the screen cancels a download. */
class UpdateViewModel(
    private val checker: UpdateChecker,
    private val downloader: UpdateDownloader,
    private val installer: UpdateInstaller,
    private val prefs: UpdatePrefs,
    private val installedVersion: String,
    private val clock: () -> Long,
) : ViewModel() {

    private val release = MutableStateFlow<ReleaseInfo?>(null)
    private val dismissedTag = MutableStateFlow(prefs.dismissedTag)
    private val _checking = MutableStateFlow(false)
    private val _dialogOpen = MutableStateFlow(false)
    private val _progress = MutableStateFlow<DownloadProgress?>(null)
    private val _explainPermission = MutableStateFlow(false)
    private val events = Channel<UpdateEvent>(Channel.BUFFERED)

    private var downloadJob: Job? = null
    private var autoChecked = false
    private var pendingApk: File? = null
    private var awaitingSettings = false

    /** The newest known release, or null (also while it is up to date). */
    val available: StateFlow<ReleaseInfo?> = release

    /** The release to announce in the banner: newer than installed and not dismissed. */
    val banner: StateFlow<ReleaseInfo?> = combine(release, dismissedTag) { r, dismissed ->
        r.takeIf { UpdatePolicy.showBanner(it, dismissed) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val checking: StateFlow<Boolean> = _checking
    val dialogOpen: StateFlow<Boolean> = _dialogOpen
    val progress: StateFlow<DownloadProgress?> = _progress

    /** True while the user is to be told why CloneLab must be allowed to install apps (Android 8+). */
    val explainPermission: StateFlow<Boolean> = _explainPermission

    val updateEvents: Flow<UpdateEvent> = events.receiveAsFlow()

    init {
        // A release found by an earlier check stays on offer until the installed version catches up.
        prefs.releaseJson?.let { json ->
            (UpdateChecker.interpret(json, installedVersion) as? CheckResult.Available)?.let { release.value = it.release }
        }
    }

    /** From the menu: always asks, and always answers. */
    fun checkManually() {
        if (_checking.value || downloadJob?.isActive == true) return
        _checking.value = true
        viewModelScope.launch {
            try {
                when (val result = checker.check()) {
                    CheckResult.Failed -> events.send(UpdateEvent.Error(UserMessage(R.string.update_check_failed)))
                    is CheckResult.UpToDate -> {
                        noteChecked(null)
                        events.send(UpdateEvent.UpToDate(result.version))
                    }
                    is CheckResult.Available -> {
                        noteChecked(result)
                        _dialogOpen.value = true
                    }
                }
            } finally {
                _checking.value = false
            }
        }
    }

    /** On Home start: at most once per 24 h and once per screen lifetime, silent on failure. */
    fun autoCheck() {
        if (autoChecked || _checking.value || downloadJob?.isActive == true) return
        if (!UpdatePolicy.shouldAutoCheck(prefs.lastCheck, clock())) return
        autoChecked = true
        viewModelScope.launch {
            when (val result = checker.check()) {
                CheckResult.Failed -> Unit
                is CheckResult.UpToDate -> noteChecked(null)
                is CheckResult.Available -> noteChecked(result)
            }
        }
    }

    private fun noteChecked(found: CheckResult.Available?) {
        prefs.lastCheck = clock()
        prefs.releaseJson = found?.rawJson
        release.value = found?.release
    }

    fun openDialog() {
        if (release.value != null) _dialogOpen.value = true
    }

    /** The dialog went away (Later, back, outside tap): a running download is cancelled with it. */
    fun dismissDialog() {
        _dialogOpen.value = false
        downloadJob?.cancel()
    }

    fun dismissBanner() {
        val tag = release.value?.tag ?: return
        prefs.dismissedTag = tag
        dismissedTag.value = tag
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    fun startDownload() {
        val target = release.value ?: return
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            _progress.value = DownloadProgress(0, target.apkSize)
            try {
                val file = downloader.existing(target)
                    ?: downloader.download(target) { done, total -> _progress.value = DownloadProgress(done, total) }
                val acceptable = withContext(Dispatchers.IO) { installer.isNewerCloneLab(file) }
                if (!acceptable) {
                    file.delete()
                    throw UpdateException(UpdateException.Reason.NOT_NEWER)
                }
                _dialogOpen.value = false
                handOver(file)
            } catch (e: UpdateException) {
                _dialogOpen.value = false
                events.send(UpdateEvent.Error(e.toUserMessage()))
            } finally {
                _progress.value = null
            }
        }
    }

    private suspend fun handOver(file: File) {
        pendingApk = file
        if (installer.needsInstallPermission()) {
            _explainPermission.value = true
        } else {
            sendInstall(file)
        }
    }

    private suspend fun sendInstall(file: File) {
        try {
            val intent = withContext(Dispatchers.IO) { installer.installIntent(file) }
            events.send(UpdateEvent.Install(intent))
        } catch (e: IOException) {
            events.send(UpdateEvent.Error(UpdateException(UpdateException.Reason.STORAGE).toUserMessage()))
        } catch (e: IllegalArgumentException) {
            // FileProvider could not map the file to a uri.
            events.send(UpdateEvent.Error(UpdateException(UpdateException.Reason.STORAGE).toUserMessage()))
        }
    }

    /** The user declined the explanation. */
    fun permissionDeclined() {
        _explainPermission.value = false
        pendingApk = null
    }

    /** The user is being sent to the "install unknown apps" page; [onSettingsReturned] follows. */
    fun permissionSettingsOpened() {
        _explainPermission.value = false
        awaitingSettings = true
    }

    /** Back from the settings page: install if CloneLab is allowed now. */
    fun onSettingsReturned() {
        val file = pendingApk
        if (!awaitingSettings || file == null) return
        awaitingSettings = false
        viewModelScope.launch {
            if (installer.needsInstallPermission()) {
                events.send(UpdateEvent.Error(UserMessage(R.string.update_error_permission)))
            } else {
                sendInstall(file)
            }
        }
    }
}
