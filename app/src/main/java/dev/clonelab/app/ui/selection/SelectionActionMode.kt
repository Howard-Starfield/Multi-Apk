package dev.clonelab.app.ui.selection

import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import dev.clonelab.app.R

/**
 * The contextual toolbar of selection mode: "<n> selected", Select all, Uninstall, and the close button.
 * Drive it with [render]; Back and the close button end the mode and call [onClosed].
 */
class SelectionActionMode(
    private val activity: AppCompatActivity,
    private val onSelectAll: () -> Unit,
    private val onUninstall: () -> Unit,
    private val onClosed: () -> Unit,
) {
    private var mode: ActionMode? = null
    private var busy = false

    fun render(count: Int, busy: Boolean) {
        this.busy = busy
        if (count == 0) {
            mode?.finish()
            return
        }
        val current = mode ?: activity.startSupportActionMode(callback).also { mode = it } ?: return
        current.title = activity.getString(R.string.selection_count, count)
        current.invalidate()
    }

    private val callback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.selection, menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.findItem(R.id.action_select_all).isEnabled = !busy
            menu.findItem(R.id.action_uninstall).isEnabled = !busy
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                R.id.action_select_all -> onSelectAll()
                R.id.action_uninstall -> onUninstall()
                else -> return false
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            this@SelectionActionMode.mode = null
            // A rotation destroys the mode too, but the selection must survive it.
            if (!activity.isChangingConfigurations) onClosed()
        }
    }
}
