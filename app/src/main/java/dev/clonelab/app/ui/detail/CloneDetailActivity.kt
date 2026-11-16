package dev.clonelab.app.ui.detail

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.android.material.snackbar.Snackbar
import dev.clonelab.app.R
import dev.clonelab.app.ServiceLocator
import dev.clonelab.app.data.CloneItem
import dev.clonelab.app.databinding.ActivityCloneDetailBinding
import dev.clonelab.app.model.CloneId
import dev.clonelab.app.shortcut.ShortcutPublisher
import dev.clonelab.app.ui.AppVisuals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

class CloneDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCloneDetailBinding
    private val viewModel: CloneDetailViewModel by viewModels {
        viewModelFactory {
            val id = CloneId(
                intent.getStringExtra(EXTRA_PACKAGE).orEmpty(),
                intent.getIntExtra(EXTRA_USER_ID, 0),
            )
            initializer {
                CloneDetailViewModel(
                    id,
                    ServiceLocator.repository(applicationContext),
                    ShortcutPublisher(applicationContext),
                    IconImageStore(applicationContext),
                )
            }
        }
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.useImageIcon(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCloneDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.launch.setOnClickListener { viewModel.launch() }
        binding.rename.setOnClickListener { showRenameDialog() }
        binding.editIcon.setOnClickListener {
            EditIconDialog(
                context = this,
                onApp = viewModel::useAppIcon,
                onText = viewModel::useTextIcon,
                onImage = { pickImage.launch("image/*") },
            ).show()
        }
        binding.addShortcut.setOnClickListener { viewModel.pinShortcut() }
        binding.stop.setOnClickListener { viewModel.stop() }
        binding.clearData.setOnClickListener { confirm(R.string.detail_clear_data_confirm, viewModel::clearData) }
        binding.uninstall.setOnClickListener { confirm(R.string.detail_uninstall_confirm, viewModel::remove) }
        binding.remove.setOnClickListener { confirm(R.string.detail_remove_confirm, viewModel::remove) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.item.collect { item ->
                        render(item)
                        if (item != null) warmUpVisuals(item)
                    }
                }
                launch {
                    viewModel.messages.collect { event ->
                        when (event) {
                            DetailEvent.Closed -> finish()
                            is DetailEvent.Error -> showMessage(event.message)
                            DetailEvent.ImageFailed -> showMessage(getString(R.string.icon_image_failed))
                            DetailEvent.PinUnsupported -> showMessage(getString(R.string.shortcut_pin_unsupported))
                            DetailEvent.PinFailed -> showMessage(getString(R.string.shortcut_pin_failed))
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun render(item: CloneItem?) {
        if (item == null) {
            binding.state.setText(R.string.error_not_found)
            binding.actions.visibility = View.GONE
            return
        }
        val record = item.record
        binding.icon.setImageDrawable(AppVisuals.icon(this, record))
        binding.name.text = AppVisuals.label(record)
        binding.packageName.text = getString(R.string.detail_package, record.id.packageName)
        binding.number.text = getString(R.string.detail_number, record.number)
        binding.state.setText(
            when {
                !item.installed -> R.string.detail_state_broken
                item.running -> R.string.detail_state_running
                else -> R.string.detail_state_stopped
            },
        )
        binding.actions.visibility = if (item.installed) View.VISIBLE else View.GONE
        binding.remove.visibility = if (item.installed) View.GONE else View.VISIBLE
    }

    /** Loads APK-derived label and icon off the main thread, then redraws if anything changed. */
    private fun warmUpVisuals(item: CloneItem) {
        lifecycleScope.launch {
            val changed = withContext(Dispatchers.IO) {
                AppVisuals.warmUp(applicationContext, ServiceLocator.engine(), listOf(item.record))
            }
            if (changed) render(viewModel.item.value)
        }
    }

    private fun showRenameDialog() {
        val record = viewModel.item.value?.record ?: return
        val input = EditText(this).apply {
            setSingleLine()
            setText(record.customName.orEmpty())
            hint = getString(R.string.rename_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename_title)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ -> viewModel.rename(input.text.toString()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showMessage(message: String) = Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()

    private fun confirm(message: Int, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(R.string.confirm) { _, _ -> action() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_USER_ID = "userId"

        fun intent(context: Context, id: CloneId) = Intent(context, CloneDetailActivity::class.java)
            .putExtra(EXTRA_PACKAGE, id.packageName)
            .putExtra(EXTRA_USER_ID, id.userId)
    }
}
