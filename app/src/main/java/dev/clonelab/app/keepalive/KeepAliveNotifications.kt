package dev.clonelab.app.keepalive

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.clonelab.app.R
import dev.clonelab.app.model.CloneId
import dev.clonelab.app.model.CloneRecord
import dev.clonelab.app.shortcut.ShortcutTrampolineActivity
import dev.clonelab.app.ui.AppVisuals
import dev.clonelab.app.ui.home.HomeActivity

/** Builds the keep-alive service notification and the per-clone "stopped" alerts. */
class KeepAliveNotifications(context: Context) {
    private val context = context.applicationContext
    private val manager = NotificationManagerCompat.from(this.context)

    /** Safe to call on every service start: re-creating a channel with the same id only updates its text, so it follows the language. */
    fun createChannels() {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_SERVICE, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.keepalive_channel_name))
                .setDescription(context.getString(R.string.keepalive_channel_description))
                .build(),
        )
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ALERT, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.keepalive_alert_channel_name))
                .setDescription(context.getString(R.string.keepalive_alert_channel_description))
                .build(),
        )
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_RUNNING, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.running_channel_name))
                .setDescription(context.getString(R.string.running_channel_description))
                .setShowBadge(false)
                .setSound(null, null)
                .build(),
        )
    }

    fun service(runningCount: Int): Notification {
        val text = if (runningCount > 0) {
            context.resources.getQuantityString(R.plurals.keepalive_running, runningCount, runningCount)
        } else {
            context.getString(R.string.keepalive_none_running)
        }
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(activity(Intent(context, HomeActivity::class.java)))
            .addAction(0, context.getString(R.string.keepalive_exit), ExitCloneLab.pendingIntent(context))
            .build()
    }

    fun updateService(runningCount: Int) = notify(null, SERVICE_ID, service(runningCount))

    /** "<name> stopped - tap to restart"; tapping goes through the shortcut trampoline. */
    fun stopped(record: CloneRecord) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.keepalive_stopped, AppVisuals.label(record)))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(launchIntent(record.id))
            .build()
        notify(tag(record), ALERT_ID, notification)
    }

    /** Brings the clone to the front via the trampoline; used by the "stopped" alert and the running notifications. */
    fun launchIntent(id: CloneId): PendingIntent {
        // The data URI keeps the PendingIntents of different clones distinct: extras do not count for equality.
        val restart = ShortcutTrampolineActivity.intent(context, id)
            .setData(Uri.parse("clonelab://restart/${id.packageName}/${id.userId}"))
        return activity(restart)
    }

    fun cancelStopped(record: CloneRecord) {
        try {
            manager.cancel(tag(record), ALERT_ID)
        } catch (e: RuntimeException) {
            // Nothing to cancel.
        }
    }

    private fun notify(tag: String?, id: Int, notification: Notification) {
        try {
            manager.notify(tag, id, notification)
        } catch (e: SecurityException) {
            // Notifications are not allowed (Android 13+); the guide screen asks for them.
        }
    }

    private fun tag(record: CloneRecord) = "stopped:${record.id.packageName}#${record.id.userId}"

    fun activity(intent: Intent): PendingIntent {
        val immutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or immutable)
    }

    companion object {
        const val SERVICE_ID = 4101
        private const val ALERT_ID = 4102
        private const val CHANNEL_SERVICE = "background_clones"
        private const val CHANNEL_ALERT = "clone_alerts"
        const val CHANNEL_RUNNING = "running_clones"
    }
}
