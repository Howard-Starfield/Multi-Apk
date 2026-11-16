package dev.clonelab.app.keepalive

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dev.clonelab.app.R
import dev.clonelab.app.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Walks through the missing [SetupStep]s one after the other: each settings page opens when the user
 * comes back from the previous one. A step is tried at most once per run, so a declined step is
 * skipped and never loops. Create it in `onCreate` (it registers ActivityResult launchers) and call
 * [start] from a click. [onFinished] gets the number of steps still missing.
 */
class SetupFlow(
    private val activity: ComponentActivity,
    private val onFinished: (remaining: Int) -> Unit,
) {
    private val prefs = KeepAlivePrefs(activity)
    private val handler = Handler(Looper.getMainLooper())
    private val attempted = mutableSetOf<SetupStep>()
    private var active = false

    /** Set when only one step was asked for (a row of the guide); null walks through everything missing. */
    private var only: SetupStep? = null

    private val clonePermissions = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (!active) return@registerForActivityResult
        // Still denied and the system will not ask again (or showed no prompt at all): offer the settings page once.
        val stuck = SetupStatusReader.missingClonePermissions(activity)
            .filterNot { androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        if (stuck.isNotEmpty()) {
            toast(R.string.setup_toast_app_permissions)
            launchFirst()
        } else {
            advance()
        }
    }

    private val permission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!active) return@registerForActivityResult
        // Denied and the system will not ask again (or showed no prompt at all): offer the settings page once.
        if (!granted && !canAskNotificationsAgain()) openNotificationSettings() else advance()
    }

    private val screen = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (active) advance()
    }

    val isRunning: Boolean get() = active

    fun start() = begin(null)

    /** Runs just [step] (if it is still missing), then finishes. */
    fun startOnly(step: SetupStep) = begin(step)

    private fun begin(step: SetupStep?) {
        if (active) return
        attempted.clear()
        only = step
        active = true
        advance()
    }

    /** Moves to the next missing step on the next main-loop turn, so a result callback has fully returned. */
    private fun advance() {
        handler.post {
            if (!active) return@post
            if (activity.isFinishing || activity.isDestroyed) {
                active = false
                return@post
            }
            next()
        }
    }

    private fun next() {
        val status = SetupStatusReader.read(activity)
        val step = status.missing().firstOrNull { it !in attempted && (only == null || it == only) }
        if (step == null) {
            finish(status)
            return
        }
        attempted += step
        when (step) {
            SetupStep.APP_PERMISSIONS -> askClonePermissions()
            SetupStep.NOTIFICATIONS -> askNotifications()
            SetupStep.BATTERY -> {
                toast(R.string.setup_toast_battery)
                launchFirst(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(packageUri()),
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                )
            }
            SetupStep.OVERLAY -> {
                toast(R.string.setup_toast_overlay)
                launchFirst(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).setData(packageUri()))
            }
            SetupStep.AUTO_START -> {
                toast(R.string.setup_toast_autostart)
                prefs.autoStartVisited = true
                // The vendor intents carry NEW_TASK, which would hand back a result before the user returns.
                val vendor = VendorSettings.autoStartIntents(Build.MANUFACTURER).map {
                    Intent(it).apply { flags = flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv() }
                }
                launchFirst(*vendor.toTypedArray())
            }
        }
    }

    private fun askClonePermissions() {
        val missing = SetupStatusReader.missingClonePermissions(activity)
        try {
            clonePermissions.launch(missing.toTypedArray())
        } catch (e: RuntimeException) {
            Log.w(TAG, "Permission prompt failed", e)
            toast(R.string.setup_toast_app_permissions)
            launchFirst()
        }
    }

    private fun askNotifications() {
        toast(R.string.setup_toast_notifications)
        val needsPrompt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (!needsPrompt) {
            openNotificationSettings()
            return
        }
        if (prefs.notificationsAsked && !canAskNotificationsAgain()) {
            openNotificationSettings()
            return
        }
        prefs.notificationsAsked = true
        try {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Notification prompt failed", e)
            openNotificationSettings()
        }
    }

    /** False once the user denied the prompt for good (always false below Android 13, which has no prompt). */
    private fun canAskNotificationsAgain(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)

    private fun openNotificationSettings() {
        val intents = mutableListOf<Intent>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intents += Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
        }
        launchFirst(*intents.toTypedArray())
    }

    /** Starts the first intent that opens; app details is always the last resort. If nothing opens, the flow moves on. */
    private fun launchFirst(vararg intents: Intent) {
        val candidates = intents.toList() +
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(packageUri())
        for (intent in candidates) {
            if (intent.resolveActivity(activity.packageManager) == null) continue
            try {
                screen.launch(intent)
                return
            } catch (e: Exception) {
                // ActivityNotFoundException, SecurityException (not exported on this ROM), ...: try the next one.
                Log.w(TAG, "Could not open $intent", e)
            }
        }
        advance()
    }

    private fun finish(status: SetupStatus) {
        active = false
        if (status.battery && status.overlay && prefs.consumeKeepAliveMigration()) {
            enableKeepAliveForExistingClones()
        }
        onFinished(status.missing().size)
    }

    /** Runs once, so clones the user switches off later stay off. Not tied to the activity: it must finish. */
    private fun enableKeepAliveForExistingClones() {
        val app = activity.applicationContext
        scope.launch {
            try {
                val repository = ServiceLocator.repository(app)
                for (record in ServiceLocator.store(app).all()) {
                    if (!record.keepAlive) repository.update(record.id) { it.copy(keepAlive = true) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not enable keep-alive for existing clones", e)
            }
            KeepAliveController.sync(app)
        }
    }

    private fun packageUri() = Uri.parse("package:${activity.packageName}")

    private fun toast(text: Int) {
        Toast.makeText(activity, activity.getString(text, activity.getString(R.string.app_name)), Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val TAG = "SetupFlow"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
