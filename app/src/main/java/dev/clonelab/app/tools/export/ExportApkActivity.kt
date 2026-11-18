package dev.clonelab.app.tools.export

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import dev.clonelab.app.R
import dev.clonelab.app.databinding.ActivityExportApkBinding
import dev.clonelab.app.tools.PhoneApp
import dev.clonelab.app.tools.PhoneAppAdapter
import dev.clonelab.app.tools.PhoneApps
import kotlinx.coroutines.launch

/**
 * Tools > Export APK: tap an app, confirm in a sheet, pick where to save; the copy runs with a progress
 * bar that can cancel it. Afterwards a dialog offers to share the file.
 */
class ExportApkActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExportApkBinding

    /** Whether the options menu shows "Show system apps" checked. */
    private var shownSystem = false
    private var savedDialog: AlertDialog? = null

    private val viewModel: ExportViewModel by viewModels {
        viewModelFactory {
            initializer {
                val app = applicationContext
                ExportViewModel(PhoneApps(app.packageManager, app.packageName), app.contentResolver)
            }
        }
    }

    private val createApk = registerForActivityResult(ActivityResultContracts.CreateDocument(ApkNames.APK_MIME)) {
        viewModel.onDestinationChosen(it)
    }
    private val createApks = registerForActivityResult(ActivityResultContracts.CreateDocument(ApkNames.APKS_MIME)) {
        viewModel.onDestinationChosen(it)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExportApkBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val adapter = PhoneAppAdapter(packageManager, ::showSheet)
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.search.doAfterTextChanged { viewModel.setQuery(it?.toString().orEmpty()) }
        binding.cancelExport.setOnClickListener { viewModel.cancel() }

        supportFragmentManager.setFragmentResultListener(ExportSheet.RESULT_KEY, this) { _, result ->
            val app = viewModel.appFor(result.getString(ExportSheet.ARG_PACKAGE).orEmpty()) ?: return@setFragmentResultListener
            val request = viewModel.prepare(app) ?: return@setFragmentResultListener
            (if (app.isSplit) createApks else createApk).launch(request.fileName)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        adapter.submitList(state.apps)
                        binding.loading.visibility = if (state.loading) View.VISIBLE else View.GONE
                        binding.empty.visibility = if (!state.loading && state.apps.isEmpty()) View.VISIBLE else View.GONE
                        renderProgress(state.progress)
                        renderSaved(state.saved)
                        if (state.showSystem != shownSystem) {
                            shownSystem = state.showSystem
                            invalidateOptionsMenu()
                        }
                    }
                }
                launch {
                    viewModel.uiMessages.collect {
                        Snackbar.make(binding.root, it.resolve(this@ExportApkActivity), Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.tools_export, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_show_system).isChecked = viewModel.state.value.showSystem
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != R.id.action_show_system) return super.onOptionsItemSelected(item)
        viewModel.setShowSystem(!item.isChecked)
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        savedDialog?.dismiss()
        savedDialog = null
        super.onDestroy()
    }

    private fun showSheet(app: PhoneApp) {
        if (viewModel.state.value.progress != null) return
        if (supportFragmentManager.findFragmentByTag(ExportSheet.TAG) != null) return
        ExportSheet.forApp(app).show(supportFragmentManager, ExportSheet.TAG)
    }

    private fun renderProgress(progress: ExportProgress?) {
        binding.progressPanel.visibility = if (progress != null) View.VISIBLE else View.GONE
        if (progress == null) return
        binding.progressText.text = getString(
            R.string.tools_export_progress,
            progress.label,
            Formatter.formatShortFileSize(this, progress.done),
            Formatter.formatShortFileSize(this, progress.total),
        )
        binding.exportProgress.progress = (progress.done * 1000 / progress.total).toInt()
    }

    private fun renderSaved(saved: SavedExport?) {
        if (saved == null) {
            savedDialog?.dismiss()
            savedDialog = null
            return
        }
        if (savedDialog?.isShowing == true) return
        val message = buildString {
            append(getString(R.string.tools_export_saved_message, saved.fileName))
            if (saved.split) {
                append("\n\n")
                append(getString(R.string.tools_export_split_note))
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.tools_export_saved)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.tools_export_share) { _, _ -> share(saved) }
            .setNegativeButton(R.string.tools_export_done) { _, _ -> viewModel.dismissSaved() }
            .create()
        dialog.show()
        // Sharing keeps the dialog open, so the file can be shared again or the dialog closed with Done.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { share(saved) }
        savedDialog = dialog
    }

    private fun share(saved: SavedExport) {
        val send = Intent(Intent.ACTION_SEND)
            .setType(saved.mimeType)
            .putExtra(Intent.EXTRA_STREAM, saved.uri)
            .putExtra(Intent.EXTRA_SUBJECT, saved.fileName)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(saved.fileName, saved.uri)
        try {
            startActivity(Intent.createChooser(send, saved.fileName).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: ActivityNotFoundException) {
            Snackbar.make(binding.root, R.string.tools_export_no_share, Snackbar.LENGTH_LONG).show()
        }
    }
}
