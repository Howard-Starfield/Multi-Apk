package dev.clonelab.app.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.snackbar.Snackbar
import dev.clonelab.app.ServiceLocator
import dev.clonelab.app.data.CloneItem
import dev.clonelab.app.databinding.ActivityHomeBinding
import dev.clonelab.app.ui.add.AddCloneActivity
import dev.clonelab.app.ui.detail.CloneDetailActivity
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val viewModel: HomeViewModel by viewModels {
        viewModelFactory {
            initializer { HomeViewModel(ServiceLocator.repository(applicationContext), ServiceLocator.engine()) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val adapter = CloneAdapter(onClick = ::onCloneClicked, onLongClick = ::openDetail)
        binding.list.layoutManager = GridLayoutManager(this, COLUMNS)
        binding.list.adapter = adapter
        binding.fab.setOnClickListener { startActivity(Intent(this, AddCloneActivity::class.java)) }
        binding.retry.setOnClickListener { viewModel.checkEngine() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        adapter.submitList(state.items)
                        binding.empty.visibility =
                            if (state.items.isEmpty() && !state.engineFailed) View.VISIBLE else View.GONE
                        binding.banner.visibility = if (state.engineFailed) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.messages.collect { Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show() }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun onCloneClicked(item: CloneItem) {
        // A broken clone cannot be launched; its detail screen offers removal.
        if (item.installed) viewModel.launch(item.record.id) else openDetail(item)
    }

    private fun openDetail(item: CloneItem) {
        startActivity(CloneDetailActivity.intent(this, item.record.id))
    }

    private companion object {
        const val COLUMNS = 3
    }
}
