package dev.clonelab.app.tools.uninstall

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import dev.clonelab.app.ServiceLocator
import dev.clonelab.app.databinding.ActivityBatchUninstallBinding
import dev.clonelab.app.tools.AppSort
import dev.clonelab.app.tools.PhoneAppAdapter
import dev.clonelab.app.tools.PhoneApps
import dev.clonelab.app.ui.selection.SelectionActionMode
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Tools > Batch uninstall: pick installed apps, then Android's own uninstall dialog comes up for each in
 * turn. A bar at the bottom shows the progress and can stop the queue.
 */
class BatchUninstallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBatchUninstallBinding
    private lateinit var adapter: PhoneAppAdapter
    private lateinit var selectionBar: SelectionActionMode

    /** The sort the options menu shows a check for. */
    private var shownSort = AppSort.NAME

    private val viewModel: BatchUninstallViewModel by viewModels {
        viewModelFactory {
            initializer {
                val app = applicationContext
                BatchUninstallViewModel(
                    PhoneApps(app.packageManager, app.packageName),
                    ServiceLocator.repository(app).clones.map { clones ->
                        clones.map { it.record.id.packageName }.toSet()
                    },
                )
            }
        }
    }

    private val uninstallDialog = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.onDialogReturned(it.resultCode == RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBatchUninstallBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = PhoneAppAdapter(packageManager) { viewModel.toggle(it.packageName) }
        selectionBar = SelectionActionMode(
            this,
            onSelectAll = viewModel::selectAllShown,
            onUninstall = ::confirmUninstall,
            onClosed = viewModel::clearSelection,
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.search.doAfterTextChanged { viewModel.setQuery(it?.toString().orEmpty()) }
        binding.cancelQueue.setOnClickListener { viewModel.cancel() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.uiEvents.collect(::onEvent) }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.tools_uninstall, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_sort_date).isChecked = viewModel.state.value.sort == AppSort.INSTALL_DATE
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != R.id.action_sort_date) return super.onOptionsItemSelected(item)
        viewModel.setSort(if (item.isChecked) AppSort.NAME else AppSort.INSTALL_DATE)
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun render(state: UninstallState) {
        adapter.submitList(state.apps)
        adapter.update(state.selection.selected, state.cloned)
        selectionBar.render(state.selection.size, busy = state.queue != null)
        binding.loading.visibility = if (state.loading) View.VISIBLE else View.GONE
        binding.empty.visibility = if (!state.loading && state.apps.isEmpty()) View.VISIBLE else View.GONE

        val queue = state.queue
        binding.progressBar.visibility = if (queue != null) View.VISIBLE else View.GONE
        if (queue != null) {
            binding.progressText.text = getString(R.string.tools_uninstall_progress, queue.position, queue.total)
            binding.cancelQueue.isEnabled = !queue.cancelled
        }
        if (state.sort != shownSort) {
            shownSort = state.sort
            invalidateOptionsMenu()
        }
    }

    private fun onEvent(event: UninstallEvent) {
        when (event) {
            is UninstallEvent.Launch -> launchDialog(event.packageName)
            is UninstallEvent.Summary -> Snackbar.make(
                binding.root,
                getString(R.string.tools_uninstall_summary, event.uninstalled, event.total),
                Snackbar.LENGTH_LONG,
            ).show()
        }
    }

    /** Asks once for the whole batch, mentioning apps whose clones would break. */
    private fun confirmUninstall() {
        val state = viewModel.state.value
        val count = state.selection.size
        if (count == 0 || state.queue != null) return
        val withClones = state.selection.selected.count { it in state.cloned }
        val message = buildString {
            append(resources.getQuantityString(R.plurals.tools_uninstall_confirm, count, count))
            if (withClones > 0) {
                append("\n\n")
                append(resources.getQuantityString(R.plurals.tools_uninstall_clones_note, withClones, withClones))
            }
        }
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(R.string.detail_uninstall) { _, _ -> viewModel.startUninstall() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    @Suppress("DEPRECATION") // ACTION_DELETE is the only way to get a result back from the uninstall dialog.
    private fun launchDialog(packageName: String) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
            .putExtra(Intent.EXTRA_RETURN_RESULT, true)
        try {
            uninstallDialog.launch(intent)
        } catch (e: ActivityNotFoundException) {
            viewModel.onDialogReturned(false)
        } catch (e: SecurityException) {
            viewModel.onDialogReturned(false)
        }
    }
}
