package dev.clonelab.app.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import dev.clonelab.app.R
import dev.clonelab.app.databinding.DialogUpdateBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The update dialogs and one-shot reactions of [UpdateViewModel] on one activity: the update sheet, the
 * "allow installing" explanation, the settings round trip, the installer launch and the Snackbars.
 * Create it in onCreate (it registers an activity result launcher), then call [start].
 */
class UpdateUi(
    private val activity: AppCompatActivity,
    private val viewModel: UpdateViewModel,
    private val snackbarAnchor: View,
) {
    private var updateDialog: AlertDialog? = null
    private var updateBinding: DialogUpdateBinding? = null
    private var permissionDialog: AlertDialog? = null

    /** Set while the code (not the user) closes a dialog, so the dismiss listener stays quiet. */
    private var closingByCode = false

    private val settings = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.onSettingsReturned()
    }

    fun start() {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(viewModel.available, viewModel.dialogOpen, viewModel.progress) { r, open, p ->
                        Triple(r, open, p)
                    }.collect { (release, open, progress) -> renderUpdate(release, open, progress) }
                }
                launch { viewModel.explainPermission.collect(::renderPermission) }
                launch { viewModel.updateEvents.collect(::handle) }
            }
        }
    }

    /** Call from onDestroy: dialogs must not leak, and closing them for rotation is not a user decision. */
    fun close() {
        closingByCode = true
        updateDialog?.dismiss()
        permissionDialog?.dismiss()
        closingByCode = false
        updateDialog = null
        updateBinding = null
        permissionDialog = null
    }

    private fun handle(event: UpdateEvent) {
        when (event) {
            is UpdateEvent.UpToDate ->
                show(activity.getString(R.string.update_up_to_date, event.version))
            is UpdateEvent.Error -> show(event.message.resolve(activity))
            is UpdateEvent.Install -> try {
                activity.startActivity(event.intent)
            } catch (e: ActivityNotFoundException) {
                show(activity.getString(R.string.update_error_installer))
            } catch (e: SecurityException) {
                show(activity.getString(R.string.update_error_installer))
            }
        }
    }

    private fun show(text: String) = Snackbar.make(snackbarAnchor, text, Snackbar.LENGTH_LONG).show()

    private fun renderUpdate(release: ReleaseInfo?, open: Boolean, progress: DownloadProgress?) {
        if (!open || release == null) {
            closeUpdateDialog()
            return
        }
        val dialog = updateDialog ?: createUpdateDialog(release)
        val binding = updateBinding ?: return
        val downloading = progress != null
        binding.progressText.visibility = if (downloading) View.VISIBLE else View.GONE
        binding.progressBar.visibility = if (downloading) View.VISIBLE else View.GONE
        if (progress != null) {
            val mb = { bytes: Long -> bytes / BYTES_PER_MB }
            binding.progressText.text = if (progress.total > 0) {
                activity.getString(R.string.update_progress, mb(progress.done), mb(progress.total))
            } else {
                activity.getString(R.string.update_progress_unknown, mb(progress.done))
            }
            if (progress.total > 0) {
                binding.progressBar.isIndeterminate = false
                binding.progressBar.setProgressCompat((progress.done * 100 / progress.total).toInt(), true)
            } else {
                binding.progressBar.isIndeterminate = true
            }
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).visibility = if (downloading) View.GONE else View.VISIBLE
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).visibility = if (downloading) View.GONE else View.VISIBLE
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            .setText(if (downloading) R.string.cancel else R.string.update_later)
    }

    private fun createUpdateDialog(release: ReleaseInfo): AlertDialog {
        val binding = DialogUpdateBinding.inflate(activity.layoutInflater)
        binding.size.text = activity.getString(R.string.update_size, release.apkSize / BYTES_PER_MB)
        binding.size.visibility = if (release.apkSize > 0) View.VISIBLE else View.GONE
        binding.notes.text = release.notesPreview().ifBlank { activity.getString(R.string.update_no_notes) }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_title, release.version))
            .setView(binding.root)
            .setPositiveButton(R.string.update_download_install, null)
            .setNeutralButton(R.string.update_view_github, null)
            .setNegativeButton(R.string.update_later, null)
            .setOnDismissListener {
                if (!closingByCode) viewModel.dismissDialog()
                updateDialog = null
                updateBinding = null
            }
            .create()
        dialog.show()
        // Set after show() so that no button closes the dialog by itself (the download keeps it open).
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { viewModel.startDownload() }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { openReleasePage(release) }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            if (viewModel.progress.value != null) viewModel.cancelDownload() else dialog.dismiss()
        }
        updateDialog = dialog
        updateBinding = binding
        return dialog
    }

    private fun closeUpdateDialog() {
        val dialog = updateDialog ?: return
        closingByCode = true
        dialog.dismiss()
        closingByCode = false
        updateDialog = null
        updateBinding = null
    }

    private fun openReleasePage(release: ReleaseInfo) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.pageUrl)))
        } catch (e: ActivityNotFoundException) {
            show(activity.getString(R.string.error_generic))
        }
    }

    private fun renderPermission(explain: Boolean) {
        if (!explain) {
            val dialog = permissionDialog ?: return
            closingByCode = true
            dialog.dismiss()
            closingByCode = false
            permissionDialog = null
            return
        }
        if (permissionDialog != null) return
        permissionDialog = AlertDialog.Builder(activity)
            .setTitle(R.string.update_permission_title)
            .setMessage(R.string.update_permission_message)
            .setPositiveButton(R.string.update_permission_open) { _, _ ->
                viewModel.permissionSettingsOpened()
                try {
                    settings.launch(UpdateInstaller(activity).installPermissionIntent())
                } catch (e: ActivityNotFoundException) {
                    viewModel.permissionDeclined()
                    show(activity.getString(R.string.update_error_permission))
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> viewModel.permissionDeclined() }
            .setOnCancelListener { viewModel.permissionDeclined() }
            .setOnDismissListener { permissionDialog = null }
            .show()
    }

    private companion object {
        const val BYTES_PER_MB = 1024.0 * 1024.0
    }
}
