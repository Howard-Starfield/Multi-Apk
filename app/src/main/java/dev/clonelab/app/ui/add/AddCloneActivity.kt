package dev.clonelab.app.ui.add

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import dev.clonelab.app.databinding.ActivityAddCloneBinding
import kotlinx.coroutines.launch

class AddCloneActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddCloneBinding
    private val viewModel: AddCloneViewModel by viewModels {
        viewModelFactory {
            initializer {
                AddCloneViewModel(ServiceLocator.repository(applicationContext), packageManager, packageName)
            }
        }
    }

    private val pickApk = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.cloneApk(uri.toString(), displayName(uri))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddCloneBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val adapter = InstalledAppAdapter(packageManager, viewModel::clone)
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.search.doAfterTextChanged { viewModel.setQuery(it?.toString().orEmpty()) }
        binding.fromApk.setOnClickListener { chooseApk() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        adapter.submitList(state.apps)
                        binding.empty.visibility = if (state.apps.isEmpty()) View.VISIBLE else View.GONE
                        binding.progress.visibility = if (state.installing) View.VISIBLE else View.INVISIBLE
                        binding.installing.visibility = if (state.installing) View.VISIBLE else View.GONE
                        binding.fromApk.isEnabled = !state.installing
                    }
                }
                launch {
                    viewModel.results.collect { event ->
                        when (event) {
                            AddEvent.Done -> finish()
                            is AddEvent.Failed -> Snackbar.make(binding.root, event.message.resolve(this@AddCloneActivity), Snackbar.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun chooseApk() {
        try {
            pickApk.launch(arrayOf(APK_MIME))
        } catch (e: Exception) {
            pickApk.launch(arrayOf("*/*"))
        }
    }

    private fun displayName(uri: Uri): String {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        return name?.removeSuffix(".apk")?.takeIf { it.isNotBlank() } ?: getString(R.string.add_apk_default_label)
    }

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}
