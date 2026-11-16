package dev.clonelab.app.ui.keepalive

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import dev.clonelab.app.R
import dev.clonelab.app.databinding.ActivityKeepaliveGuideBinding
import dev.clonelab.app.databinding.ItemKeepaliveStepBinding
import dev.clonelab.app.keepalive.VendorSettings

/** Checklist of the phone settings that keep clones running in the background, with live status. */
class KeepAliveGuideActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKeepaliveGuideBinding

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
        showStatus(binding.stepNotifications, NotificationManagerCompat.from(this).areNotificationsEnabled())
        showStatus(binding.stepBattery, isIgnoringBatteryOptimisations())
        showStatus(binding.stepOverlay, canDrawOverlays())
        binding.stepAutoStart.status.setText(R.string.guide_status_manual)
        binding.stepAutoStart.action.setText(R.string.guide_open_settings)
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

    private fun isIgnoringBatteryOptimisations(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    companion object {
        fun intent(context: Context) = Intent(context, KeepAliveGuideActivity::class.java)
    }
}
