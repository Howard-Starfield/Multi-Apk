package dev.clonelab.app.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
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
import dev.clonelab.app.BuildConfig
import dev.clonelab.app.R
import dev.clonelab.app.ServiceLocator
import dev.clonelab.app.databinding.ActivityHomeBinding
import dev.clonelab.app.keepalive.ExitCloneLab
import dev.clonelab.app.keepalive.KeepAliveController
import dev.clonelab.app.ui.AppVisuals
import dev.clonelab.app.ui.add.AddCloneActivity
import dev.clonelab.app.ui.group.CloneGroupActivity
import dev.clonelab.app.ui.selection.SelectionActionMode
import dev.clonelab.app.update.UpdateChecker
import dev.clonelab.app.update.UpdateDownloader
import dev.clonelab.app.update.UpdateFiles
import dev.clonelab.app.update.UpdateInstaller
import dev.clonelab.app.update.UpdatePrefs
import dev.clonelab.app.update.UpdateUi
import dev.clonelab.app.update.UpdateViewModel
import dev.clonelab.app.update.ReleaseInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var adapter: AppGroupAdapter
    private lateinit var setupFlow: SetupFlow
    private lateinit var updateUi: UpdateUi
    private lateinit var selectionBar: SelectionActionMode
    private var checkingUpdates = false
    private var removingApps = false
    private val updateViewModel: UpdateViewModel by viewModels {
        viewModelFactory {
            initializer {
                val app = applicationContext
                UpdateViewModel(
                    UpdateChecker(BuildConfig.VERSION_NAME),
                    UpdateDownloader(UpdateFiles.dir(app)),
                    UpdateInstaller(app),
                    UpdatePrefs(app),
                    BuildConfig.VERSION_NAME,
                    System::currentTimeMillis,
                )
            }
        }
    }
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
            onAppClick = ::onAppClicked,
            onAppLongClick = { viewModel.toggleSelected(it.packageName) },
            onAddClick = { startActivity(Intent(this, AddCloneActivity::class.java)) },
        )
        selectionBar = SelectionActionMode(
            this,
            onSelectAll = viewModel::selectAll,
            onUninstall = ::confirmUninstall,
            onClosed = viewModel::clearSelection,
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
        updateUi = UpdateUi(this, updateViewModel, binding.root)
        updateUi.start()
        binding.updateAction.setOnClickListener { updateViewModel.openDialog() }
        binding.updateDismiss.setOnClickListener { updateViewModel.dismissBanner() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        adapter.submitList(state.groups)
                        adapter.showSelection(state.selection.selected)
                        selectionBar.render(state.selection.size, state.removing)
                        removingApps = state.removing
                        renderProgress()
                        warmUpVisuals(state.groups)
                        binding.empty.visibility =
                            if (state.groups.isEmpty() && !state.engineFailed) View.VISIBLE else View.GONE
                        binding.banner.visibility = if (state.engineFailed) View.VISIBLE else View.GONE
                    }
                }
                launch { updateViewModel.banner.collect(::renderUpdateBanner) }
                launch {
                    updateViewModel.checking.collect {
                        checkingUpdates = it
                        renderProgress()
                    }
                }
                launch {
                    viewModel.messages.collect { Snackbar.make(binding.root, it.resolve(this@HomeActivity), Snackbar.LENGTH_LONG).show() }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Opening CloneLab again ends an "Exit CloneLab"; onResume's sync then brings the service back.
        if (!dev.clonelab.app.keepalive.ExitCloneLab.inProgress) KeepAlivePrefs(this).clearExited()
        updateViewModel.autoCheck()
    }

    override fun onDestroy() {
        updateUi.close()
        super.onDestroy()
    }

    private fun renderProgress() {
        binding.updateProgress.visibility = if (checkingUpdates || removingApps) View.VISIBLE else View.GONE
    }

    private fun renderUpdateBanner(release: ReleaseInfo?) {
        binding.updateBanner.visibility = if (release == null) View.GONE else View.VISIBLE
        if (release != null) binding.updateBannerText.text = getString(R.string.update_banner, release.version)
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
            R.id.action_check_updates -> updateViewModel.checkManually()
            R.id.action_exit -> confirmExit()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun confirmExit() {
        AlertDialog.Builder(this)
            .setMessage(R.string.exit_confirm_message)
            .setPositiveButton(R.string.home_exit) { _, _ -> ExitCloneLab.request(applicationContext) }
            .setNegativeButton(R.string.cancel, null)
            .show()
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

    private fun onAppClicked(group: AppGroup) {
        if (viewModel.state.value.selection.isActive) viewModel.toggleSelected(group.packageName) else openGroup(group)
    }

    private fun confirmUninstall() {
        val state = viewModel.state.value
        val chosen = state.groups.filter { it.packageName in state.selection }
        val clones = chosen.sumOf { it.cloneCount }
        val message = when {
            clones == 0 -> return
            clones == 1 -> getString(R.string.selection_uninstall_one)
            chosen.size == 1 -> getString(R.string.selection_uninstall_app, clones)
            else -> getString(R.string.selection_uninstall_apps, clones, chosen.size)
        }
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(R.string.detail_uninstall) { _, _ -> viewModel.uninstallSelected() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openGroup(group: AppGroup) {
        startActivity(CloneGroupActivity.intent(this, group.packageName))
    }

    private companion object {
        const val COLUMNS = 3
    }
}
