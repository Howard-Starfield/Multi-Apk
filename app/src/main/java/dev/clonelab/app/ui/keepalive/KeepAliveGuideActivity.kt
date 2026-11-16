package dev.clonelab.app.ui.keepalive

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.widget.Toast
import dev.clonelab.app.R
import dev.clonelab.app.databinding.ActivityKeepaliveGuideBinding
import dev.clonelab.app.databinding.ItemKeepaliveStepBinding
import dev.clonelab.app.keepalive.KeepAlivePrefs
import dev.clonelab.app.keepalive.SetupFlow
import dev.clonelab.app.keepalive.SetupStatusReader
import dev.clonelab.app.keepalive.VendorSettings

/** Checklist of the phone settings that keep clones running in the background, with live status. */
class KeepAliveGuideActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKeepaliveGuideBinding
    private lateinit var setupFlow: SetupFlow

    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Denied for good, or the system does not show the prompt: send the user to the settings page.
        if (!granted) openNotificationSettings()
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKeepaliveGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        bindStatic(binding.stepNotifications, R.string.guide_notifications_title, R.string.guide_notifications_text)
        bindStatic(binding.stepBattery, R.string.guide_battery_title, R.string.guide_battery_text)
        bindStatic(binding.stepOverlay, R.string.guide_overlay_title, R.string.guide_overlay_text)
        bindStatic(binding.stepAutoStart, R.string.guide_autostart_title, R.string.guide_autostart_text)

        setupFlow = SetupFlow(this) { remaining ->
            val text = if (remaining == 0) {
                getString(R.string.setup_all_set)
            } else {
                resources.getQuantityString(R.plurals.setup_remaining, remaining, remaining)
            }
            Toast.makeText(this, text, Toast.LENGTH_LONG).show()
            render()
        }
        binding.fixAll.setOnClickListener { setupFlow.start() }

        binding.stepNotifications.action.setOnClickListener { askForNotifications() }
        binding.stepBattery.action.setOnClickListener { askToIgnoreBatteryOptimisations() }
        binding.stepOverlay.action.setOnClickListener { openOverlaySettings() }
        binding.stepAutoStart.action.setOnClickListener { openAutoStartSettings() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun bindStatic(step: ItemKeepaliveStepBinding, title: Int, text: Int) {
        step.title.setText(title)
        step.description.setText(text)
    }

    private fun render() {
        val status = SetupStatusReader.read(this)
        showStatus(binding.stepNotifications, status.notifications)
        showStatus(binding.stepBattery, status.battery)
        showStatus(binding.stepOverlay, status.overlay)
        // Auto-start cannot be read: "done" only means the page was opened (or this phone has none).
        binding.stepAutoStart.status.setText(if (status.autoStart) R.string.guide_status_done else R.string.guide_status_manual)
        binding.stepAutoStart.action.setText(R.string.guide_open_settings)
        binding.fixAll.visibility = if (status.complete) View.GONE else View.VISIBLE
    }

    private fun showStatus(step: ItemKeepaliveStepBinding, done: Boolean) {
        step.status.setText(if (done) R.string.guide_status_done else R.string.guide_status_todo)
        step.action.setText(if (done) R.string.guide_open_settings else R.string.guide_grant)
    }

    private fun askForNotifications() {
        val needsPrompt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPrompt) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) else openNotificationSettings()
    }

    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            appDetailsIntent()
        }
        startFirst(listOf(intent, appDetailsIntent()))
    }

    private fun askToIgnoreBatteryOptimisations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        startFirst(listOf(request, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appDetailsIntent()))
    }

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val request = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).setData(Uri.parse("package:$packageName"))
        startFirst(listOf(request, appDetailsIntent()))
    }

    private fun openAutoStartSettings() {
        KeepAlivePrefs(this).autoStartVisited = true
        startFirst(VendorSettings.autoStartIntents(Build.MANUFACTURER) + appDetailsIntent())
    }

    private fun appDetailsIntent() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.parse("package:$packageName"))

    /** Starts the first intent that resolves and opens without error. */
    private fun startFirst(intents: List<Intent>) {
        for (intent in intents) {
            if (intent.resolveActivity(packageManager) == null) continue
            try {
                startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                // Try the next one.
            } catch (e: SecurityException) {
                // Not exported on this ROM; try the next one.
            }
        }
    }

    companion object {
        fun intent(context: Context) = Intent(context, KeepAliveGuideActivity::class.java)
    }
}
