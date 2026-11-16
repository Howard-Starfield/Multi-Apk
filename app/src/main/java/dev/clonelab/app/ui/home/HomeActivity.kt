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
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import dev.clonelab.app.R
import dev.clonelab.app.ServiceLocator
import dev.clonelab.app.data.CloneItem
import dev.clonelab.app.databinding.ActivityHomeBinding
import dev.clonelab.app.keepalive.KeepAliveController
import dev.clonelab.app.ui.AppVisuals
import dev.clonelab.app.ui.add.AddCloneActivity
import dev.clonelab.app.ui.detail.CloneDetailActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var adapter: CloneAdapter
    private lateinit var touchHelper: ItemTouchHelper
    private val viewModel: HomeViewModel by viewModels {
        viewModelFactory {
            initializer { HomeViewModel(ServiceLocator.repository(applicationContext), ServiceLocator.engine()) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = CloneAdapter(
            onClick = ::onCloneClicked,
            onLongClick = ::openDetail,
            onDragStart = { touchHelper.startDrag(it) },
        )
        binding.list.layoutManager = GridLayoutManager(this, COLUMNS)
        binding.list.adapter = adapter
        touchHelper = ItemTouchHelper(DragCallback())
        touchHelper.attachToRecyclerView(binding.list)
        binding.fab.setOnClickListener { startActivity(Intent(this, AddCloneActivity::class.java)) }
        binding.retry.setOnClickListener { viewModel.checkEngine() }
        KeepAliveController.sync(applicationContext)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        adapter.submitList(state.items)
                        warmUpVisuals(state.items)
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.home, menu)
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
        adapter.reorderMode = !adapter.reorderMode
        invalidateOptionsMenu()
        return true
    }

    /** Loads APK-derived labels and icons off the main thread, then rebinds if anything changed. */
    private fun warmUpVisuals(items: List<CloneItem>) {
        lifecycleScope.launch {
            val changed = withContext(Dispatchers.IO) {
                AppVisuals.warmUp(applicationContext, ServiceLocator.engine(), items.map { it.record })
            }
            if (changed) adapter.notifyItemRangeChanged(0, adapter.itemCount)
        }
    }

    /** Drag is started from touch in reorder mode, never by long press (that opens the detail). */
    private inner class DragCallback : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
        0,
    ) {
        override fun isLongPressDragEnabled() = false

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            adapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            viewModel.reorder(adapter.order())
        }
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
