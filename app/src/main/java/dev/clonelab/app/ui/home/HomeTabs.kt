package dev.clonelab.app.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import dev.clonelab.app.R
import dev.clonelab.app.databinding.ActivityHomeBinding
import dev.clonelab.app.databinding.ItemToolBinding
import dev.clonelab.app.tools.export.ExportApkActivity
import dev.clonelab.app.tools.uninstall.BatchUninstallActivity

/**
 * The two Home tabs, "Clones" and "Tools", as two containers of one layout that are shown in turn: the
 * Clones page is exactly what Home always was, so nothing in it had to move. The selected tab is saved
 * with the instance state (each page's own scroll position is saved with its views); Back on Tools
 * returns to Clones first.
 */
class HomeTabs(
    private val activity: AppCompatActivity,
    private val binding: ActivityHomeBinding,
    private val onLeaveClones: () -> Unit,
    savedState: Bundle?,
) {
    private var tools = savedState?.getBoolean(KEY_TOOLS) == true

    private val backToClones = activity.onBackPressedDispatcher.addCallback(activity, enabled = false) {
        select(tools = false)
    }

    init {
        bindTool(binding.toolBatchUninstall, R.drawable.ic_delete, R.string.tools_batch_uninstall, R.string.tools_batch_uninstall_desc, BatchUninstallActivity::class.java)
        bindTool(binding.toolExportApk, R.drawable.ic_export, R.string.tools_export_apk, R.string.tools_export_apk_desc, ExportApkActivity::class.java)
        show(tools)
        binding.bottomNav.selectedItemId = if (tools) R.id.tab_tools else R.id.tab_clones
        binding.bottomNav.setOnItemSelectedListener {
            show(it.itemId == R.id.tab_tools)
            true
        }
    }

    fun saveState(out: Bundle) = out.putBoolean(KEY_TOOLS, tools)

    private fun select(tools: Boolean) {
        binding.bottomNav.selectedItemId = if (tools) R.id.tab_tools else R.id.tab_clones // calls show()
    }

    private fun show(tools: Boolean) {
        if (tools && !this.tools) onLeaveClones()
        this.tools = tools
        binding.clonesPage.visibility = if (tools) View.GONE else View.VISIBLE
        binding.toolsPage.visibility = if (tools) View.VISIBLE else View.GONE
        backToClones.isEnabled = tools
    }

    private fun bindTool(card: ItemToolBinding, icon: Int, title: Int, description: Int, target: Class<*>) {
        card.toolIcon.setImageResource(icon)
        card.toolTitle.setText(title)
        card.toolDescription.setText(description)
        card.root.setOnClickListener { activity.startActivity(Intent(activity, target)) }
    }

    private companion object {
        const val KEY_TOOLS = "home_tab_tools"
    }
}
