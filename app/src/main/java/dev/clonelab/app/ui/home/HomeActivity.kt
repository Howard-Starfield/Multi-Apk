package dev.clonelab.app.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
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
import dev.clonelab.app.keepalive.KeepAlivePrefs
import dev.clonelab.app.keepalive.SetupBanner
import dev.clonelab.app.keepalive.SetupFlow
import dev.clonelab.app.keepalive.SetupStatusReader
import dev.clonelab.app.switcher.SwitcherPrefs
import dev.clonelab.app.R
import dev.clonelab.app.ServiceLocator
import dev.clonelab.app.databinding.ActivityHomeBinding
import dev.clonelab.app.keepalive.KeepAliveController
import dev.clonelab.app.ui.AppVisuals
import dev.clonelab.app.ui.add.AddCloneActivity
import dev.clonelab.app.ui.group.CloneGroupActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var adapter: AppGroupAdapter
    private lateinit var setupFlow: SetupFlow
    private val viewModel: HomeViewModel by viewModels {
        viewModelFactory {
            initializer { HomeViewModel(ServiceLocator.repository(applicationContext), ServiceLocator.engine()) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = AppGroupAdapter(
            onAppClick = ::openGroup,
            onAddClick = { startActivity(Intent(this, AddCloneActivity::class.java)) },
        )
        binding.list.layoutManager = GridLayoutManager(this, COLUMNS)
        binding.list.adapter = adapter
        binding.retry.setOnClickListener { viewModel.checkEngine() }
        setupFlow = SetupFlow(this) { remaining ->
            val text = if (remaining == 0) {
                getString(R.string.setup_all_set)
            } else {
                resources.getQuantityString(R.plurals.setup_remaining, remaining, remaining)
            }
            Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG).show()
            renderSetupBanner()
        }
        binding.setupFix.setOnClickListener { setupFlow.start() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        adapter.submitList(state.groups)
                        warmUpVisuals(state.groups)
                        binding.empty.visibility =
                            if (state.groups.isEmpty() && !state.engineFailed) View.VISIBLE else View.GONE
                        binding.banner.visibility = if (state.engineFailed) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.messages.collect { Snackbar.make(binding.root, it.resolve(this@HomeActivity), Snackbar.LENGTH_LONG).show() }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
        renderSetupBanner()
        // New clones default to keep-alive and the switcher needs the service: make it match the records.
        KeepAliveController.sync(applicationContext)
    }

    private fun renderSetupBanner() {
        val banner = SetupStatusReader.read(this).banner()
        binding.setupBanner.visibility = if (banner == null) View.GONE else View.VISIBLE
        binding.setupBannerText.text = when (banner) {
            SetupBanner.PERMISSIONS_ONLY -> getString(R.string.home_setup_banner_permissions)
            SetupBanner.BACKGROUND_ONLY, null -> getString(R.string.home_setup_banner)
            SetupBanner.BOTH ->
                getString(R.string.home_setup_banner) + " " + getString(R.string.home_setup_banner_also)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.home, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_switcher).isChecked = SwitcherPrefs(this).enabled
        menu.findItem(R.id.action_show_running).isChecked = KeepAlivePrefs(this).showRunningClones
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_switcher -> {
                SwitcherPrefs(this).enabled = !item.isChecked
                invalidateOptionsMenu()
                // The service starts or ends (and shows or hides the pill) according to the new setting.
                KeepAliveController.sync(applicationContext)
            }
            R.id.action_show_running -> {
                KeepAlivePrefs(this).showRunningClones = !item.isChecked
                invalidateOptionsMenu()
                // The service refreshes its notifications on every start command (and cancels them when off).
                KeepAliveController.sync(applicationContext)
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    /** Loads APK-derived labels and icons off the main thread, then rebinds if anything changed. */
    private fun warmUpVisuals(groups: List<AppGroup>) {
        lifecycleScope.launch {
            val changed = withContext(Dispatchers.IO) {
                AppVisuals.warmUp(applicationContext, ServiceLocator.engine(), groups.map { it.representative })
            }
            if (changed) adapter.notifyItemRangeChanged(0, adapter.itemCount)
        }
    }

    private fun openGroup(group: AppGroup) {
        startActivity(CloneGroupActivity.intent(this, group.packageName))
    }

    private companion object {
        const val COLUMNS = 3
    }
}
