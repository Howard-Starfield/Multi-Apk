package dev.clonelab.app.tools.export

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.clonelab.app.R
import dev.clonelab.app.tools.AppSort
import dev.clonelab.app.tools.PhoneApp
import dev.clonelab.app.tools.PhoneApps
import dev.clonelab.app.tools.filterApps
import dev.clonelab.app.tools.sortApps
import dev.clonelab.app.ui.UserMessage
import dev.clonelab.app.ui.toUserMessage
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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

/** How far the running export is, in bytes. */
data class ExportProgress(val label: String, val done: Long, val total: Long)

/** An export waiting for the user to pick where to save it. */
data class ExportRequest(val app: PhoneApp, val fileName: String, val mimeType: String)

/** A finished export, shown in the "Saved" dialog until dismissed. */
data class SavedExport(val fileName: String, val uri: Uri, val mimeType: String, val split: Boolean)

data class ExportState(
    val apps: List<PhoneApp> = emptyList(),
    val loading: Boolean = true,
    val showSystem: Boolean = false,
    val progress: ExportProgress? = null,
    val saved: SavedExport? = null,
)

/**
 * Copies an installed app's APK to a file the user picks, or its base and split APKs as an .apks zip.
 * One export at a time; a cancelled or failed export deletes its half-written file.
 */
class ExportViewModel(
    private val phoneApps: PhoneApps,
    private val resolver: ContentResolver,
) : ViewModel() {

    /** All installed apps, system ones included; null until loaded. */
    private val allApps = MutableStateFlow<List<PhoneApp>?>(null)
    private val query = MutableStateFlow("")
    private val showSystem = MutableStateFlow(false)
    private val progress = MutableStateFlow<ExportProgress?>(null)
    private val saved = MutableStateFlow<SavedExport?>(null)
    private val messages = Channel<UserMessage>(Channel.BUFFERED)

    private var pending: ExportRequest? = null
    private var job: Job? = null

    val state: StateFlow<ExportState> = combine(allApps, query, showSystem, progress, saved) { apps, q, system, running, done ->
        ExportState(sortApps(filterApps(apps.orEmpty(), q, system), AppSort.NAME), apps == null, system, running, done)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ExportState())

    val uiMessages: Flow<UserMessage> = messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            allApps.value = withContext(Dispatchers.IO) { phoneApps.load() }
        }
    }

    fun setQuery(text: String) {
        query.value = text
    }

    fun setShowSystem(show: Boolean) {
        showSystem.value = show
    }

    fun appFor(packageName: String): PhoneApp? = allApps.value?.firstOrNull { it.packageName == packageName }

    /**
     * Remembers [app] as the one to export once a destination is chosen.
     * @return the suggested file name and type, or null while another export runs
     */
    fun prepare(app: PhoneApp): ExportRequest? {
        if (job?.isActive == true) return null
        val extension = if (app.isSplit) "apks" else "apk"
        val request = ExportRequest(
            app,
            ApkNames.fileName(app.label, app.versionName, app.packageName, extension),
            if (app.isSplit) ApkNames.APKS_MIME else ApkNames.APK_MIME,
        )
        pending = request
        return request
    }

    /** The file picker returned: [uri] is the created document, or null if the user backed out. */
    fun onDestinationChosen(uri: Uri?) {
        val request = pending
        pending = null
        if (uri == null) return
        if (request == null || job?.isActive == true) {
            // The picker already created the file; nothing will be written to it.
            viewModelScope.launch(Dispatchers.IO) { deleteQuietly(uri) }
            return
        }
        job = viewModelScope.launch { run(request, uri) }
    }

    fun cancel() {
        val running = job ?: return
        if (!running.isActive) return
        running.cancel()
        messages.trySend(UserMessage(R.string.tools_export_cancelled))
    }

    fun dismissSaved() {
        saved.value = null
    }

    private suspend fun run(request: ExportRequest, uri: Uri) {
        val app = request.app
        var ok = false
        try {
            val total = app.apkBytes.coerceAtLeast(1)
            progress.value = ExportProgress(app.label, 0, total)
            withContext(Dispatchers.IO) { write(app, uri, total) }
            saved.value = SavedExport(request.fileName, uri, request.mimeType, app.isSplit)
            ok = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            messages.send(e.toUserMessage(R.string.tools_export_failed))
        } finally {
            if (!ok) withContext(NonCancellable + Dispatchers.IO) { deleteQuietly(uri) }
            progress.value = null
        }
    }

    private suspend fun write(app: PhoneApp, uri: Uri, total: Long) {
        var done = 0L
        var lastTick = -1L
        // Progress in tenths of a percent, published only when it changes.
        val onBytes = { count: Int ->
            done += count
            val tick = done * 1000 / total
            if (tick != lastTick) {
                lastTick = tick
                progress.value = ExportProgress(app.label, minOf(done, total), total)
            }
        }
        val stream = resolver.openOutputStream(uri, "w") ?: throw IOException("Cannot open the destination")
        BufferedOutputStream(stream, 64 * 1024).use { out ->
            if (!app.isSplit) {
                FileInputStream(app.apkPaths.first()).use { copyStream(it, out, onBytes) }
            } else {
                val names = zipEntryNames(app.apkPaths)
                val sources = app.apkPaths.mapIndexed { i, path -> ZipSource(names[i]) { FileInputStream(path) } }
                writeZip(sources, out, onBytes)
            }
        }
    }

    private fun deleteQuietly(uri: Uri) {
        try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (e: Exception) {
            // Best effort: the provider may not support deletion, or the file is already gone.
        }
    }
}
