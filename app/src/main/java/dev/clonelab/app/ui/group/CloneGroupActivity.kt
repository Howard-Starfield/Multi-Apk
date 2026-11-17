package dev.clonelab.app.ui.group

import android.content.Context
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
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import dev.clonelab.app.R
import dev.clonelab.app.ServiceLocator
import dev.clonelab.app.data.CloneItem
import dev.clonelab.app.databinding.ActivityCloneGroupBinding
import dev.clonelab.app.keepalive.KeepAliveController
import dev.clonelab.app.ui.AppVisuals
import dev.clonelab.app.ui.detail.CloneDetailActivity
import dev.clonelab.app.ui.home.CloneAdapter
import dev.clonelab.app.ui.selection.SelectionActionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The clones of one app: launch, open details, add another, reorder. */
class CloneGroupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCloneGroupBinding
    private lateinit var adapter: CloneAdapter
    private lateinit var touchHelper: ItemTouchHelper
    private lateinit var selectionBar: SelectionActionMode
    private val appPackage: String get() = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
    private val viewModel: CloneGroupViewModel by viewModels {
        viewModelFactory {
            initializer {
                CloneGroupViewModel(ServiceLocator.repository(applicationContext), packageManager, appPackage)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCloneGroupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = CloneAdapter(
            onClick = ::onCloneClicked,
            onLongClick = { viewModel.toggleSelected(it.record.id) },
            onSettings = ::openDetail,
            onAdd = viewModel::addClone,
            addCaption = R.string.home_add_clone,
            onDragStart = { touchHelper.startDrag(it) },
        )
        selectionBar = SelectionActionMode(
            this,
            onSelectAll = viewModel::selectAll,
            onUninstall = ::confirmUninstall,
            onClosed = viewModel::clearSelection,
        )
        adapter.reorderMode = savedInstanceState?.getBoolean(STATE_REORDER) == true
        binding.list.layoutManager = GridLayoutManager(this, COLUMNS)
        binding.list.adapter = adapter
        touchHelper = ItemTouchHelper(DragCallback())
        touchHelper.attachToRecyclerView(binding.list)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        // Every clone was removed (from its detail screen): nothing left to show.
                        if (state.loaded && state.items.isEmpty()) {
                            finish()
                            return@collect
                        }
                        adapter.submitList(state.items)
                        warmUpVisuals(state.items)
                        showTitle(state.items)
                        binding.progress.visibility =
                            if (state.adding || state.removing) View.VISIBLE else View.INVISIBLE
                        adapter.showSelection(state.selection.selected)
                        selectionBar.render(state.selection.size, state.removing)
                    }
                }
                launch {
                    viewModel.messages.collect {
                        Snackbar.make(binding.root, it.resolve(this@CloneGroupActivity), Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        KeepAliveController.reviveAfterExit(this)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_REORDER, adapter.reorderMode)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.group, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_reorder).setTitle(
            if (adapter.reorderMode) R.string.home_reorder_done else R.string.home_reorder,
        )
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != R.id.action_reorder) return super.onOptionsItemSelected(item)
        // Reorder and selection exclude each other.
        if (viewModel.state.value.selection.isActive) return true
        adapter.reorderMode = !adapter.reorderMode
        invalidateOptionsMenu()
        return true
    }

    private fun showTitle(items: List<CloneItem>) {
        val record = items.firstOrNull()?.record ?: return
        title = AppVisuals.appLabel(record.id.packageName, record.appLabel)
    }

    /** Loads APK-derived labels and icons off the main thread, then rebinds if anything changed. */
    private fun warmUpVisuals(items: List<CloneItem>) {
        lifecycleScope.launch {
            val changed = withContext(Dispatchers.IO) {
                AppVisuals.warmUp(applicationContext, ServiceLocator.engine(), items.map { it.record })
            }
            if (changed) {
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
                showTitle(items)
            }
        }
    }

    /** Drag is started from touch in reorder mode, never by long press (that selects a clone). */
    private inner class DragCallback : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
        0,
    ) {
        override fun isLongPressDragEnabled() = false

        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) =
            if (adapter.isAddTile(viewHolder)) 0 else super.getMovementFlags(recyclerView, viewHolder)

        override fun canDropOver(
            recyclerView: RecyclerView,
            current: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ) = !adapter.isAddTile(target)

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            if (adapter.isAddTile(target)) return false
            adapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            viewModel.reorder(adapter.order())
        }
    }

    private fun confirmUninstall() {
        val count = viewModel.state.value.selection.size
        if (count == 0) return
        val message = if (count == 1) {
            getString(R.string.selection_uninstall_one)
        } else {
            getString(R.string.selection_uninstall_many, count)
        }
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(R.string.detail_uninstall) { _, _ -> viewModel.uninstallSelected() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun onCloneClicked(item: CloneItem) {
        if (viewModel.state.value.selection.isActive) {
            viewModel.toggleSelected(item.record.id)
            return
        }
        // A broken clone cannot be launched; its detail screen offers removal.
        if (item.installed) viewModel.launch(item.record.id) else openDetail(item)
    }

    private fun openDetail(item: CloneItem) {
        startActivity(CloneDetailActivity.intent(this, item.record.id))
    }

    companion object {
        private const val COLUMNS = 3
        private const val EXTRA_PACKAGE = "package"
        private const val STATE_REORDER = "reorder"

        fun intent(context: Context, packageName: String) =
            Intent(context, CloneGroupActivity::class.java).putExtra(EXTRA_PACKAGE, packageName)
    }
}
