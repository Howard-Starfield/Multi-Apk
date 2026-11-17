package dev.clonelab.app.keepalive

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import dev.clonelab.app.R
import dev.clonelab.app.ServiceLocator
import dev.clonelab.app.model.CloneId
import dev.clonelab.app.model.CloneRecord
import dev.clonelab.app.ui.AppVisuals
import dev.clonelab.app.ui.group.CloneGroupActivity
import dev.clonelab.app.ui.home.HomeActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The "running clones" notifications of [KeepAliveService]: per app one group summary and one child per
 * running clone, each child with a Stop button (see [RunningActionsReceiver]). What is shown comes from
 * [RunningModel]; this class computes the running set off the main thread, at most once per
 * [DEBOUNCE_MS] (a stop from a notification refreshes at once), and only calls `notify`/`cancel` for
 * what changed since the last pass.
 *
 * Everything is posted as `notify(tag, ID, ...)` with tags from [RunningModel], so ids never collide.
 * Created and shut down by the service; nothing is posted while the service is not running.
 */
class RunningNotifications(
    context: Context,
    private val scope: CoroutineScope,
    private val notifications: KeepAliveNotifications,
    private val prefs: KeepAlivePrefs,
) {
    private val context = context.applicationContext
    private val manager = NotificationManagerCompat.from(this.context)
    private val mutex = Mutex()

    /** The model last posted successfully. Written under [mutex]; read by [shutdown]. */
    @Volatile
    private var posted: List<RunningApp> = emptyList()
    private var adopted = false

    @Volatile
    private var closed = false

    @Volatile
    private var lastRunAt = 0L
    private val trailingScheduled = AtomicBoolean(false)

    /** When each running clone was first seen running, to keep the most recently started ones under the cap. */
    private val startedAt = HashMap<CloneId, Long>()
    private val stopLock = Any()
    private val recentStops = HashMap<CloneId, Long>()
    private val bitmaps = LruCache<String, Bitmap>(RunningModel.MAX_CLONES * 2)

    init {
        active = this
    }

    /**
     * Asks for a refresh without blocking the caller. Unless [force], runs at most once per [DEBOUNCE_MS]:
     * a request inside the window is turned into one refresh at the end of the window.
     */
    fun requestRefresh(force: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            val wait = if (force) 0L else lastRunAt + DEBOUNCE_MS - SystemClock.elapsedRealtime()
            if (wait > 0) {
                if (trailingScheduled.compareAndSet(false, true)) {
                    try {
                        delay(wait)
                    } finally {
                        trailingScheduled.set(false)
                    }
                    refresh()
                }
                return@launch
            }
            refresh()
        }
    }

    /** The user stopped [ids] from a notification: hide them at once, whatever the engine still reports. */
    private fun stopped(ids: Collection<CloneId>) {
        val now = SystemClock.elapsedRealtime()
        synchronized(stopLock) { ids.forEach { recentStops[it] = now } }
        requestRefresh(force = true)
        // Killing the processes may take a moment; look again once the grace period is over.
        scope.launch {
            delay(STOP_GRACE_MS + 500)
            requestRefresh()
        }
    }

    private fun recentlyStopped(id: CloneId, now: Long): Boolean = synchronized(stopLock) {
        recentStops.entries.removeAll { now - it.value >= STOP_GRACE_MS }
        id in recentStops
    }

    /** Removes everything this class posted; the service is going away. Cheap enough for the main thread. */
    fun shutdown() {
        closed = true
        if (active === this) active = null
        cancelTags(tagsOf(posted))
        posted = emptyList()
    }

    private suspend fun refresh() {
        try {
            mutex.withLock {
                lastRunAt = SystemClock.elapsedRealtime()
                if (!closed) update()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Refreshing the running notifications failed", e)
        }
    }

    /** Runs under [mutex] on an IO thread. */
    private fun update() {
        if (!prefs.showRunningClones) {
            cancelTags(activeTags() + tagsOf(posted))
            posted = emptyList()
            return
        }
        val engine = ServiceLocator.engine()
        // While the engine is unreachable "nothing runs" would be a lie: leave what is shown alone.
        if (!engine.isAvailable()) return
        val records = ServiceLocator.store(context).all()
        val now = SystemClock.elapsedRealtime()
        val running = records.map { it.id }
            .filter { engine.isInstalled(it) && engine.isRunning(it) && !recentlyStopped(it, now) }
            .toSet()
        if (!engine.isAvailable()) return
        startedAt.keys.retainAll(running)
        running.forEach { startedAt.putIfAbsent(it, now) }
        // Not allowed to notify (Android 13+ permission, or switched off by the user): skip quietly.
        if (!manager.areNotificationsEnabled()) return
        if (running.isNotEmpty()) AppVisuals.warmUp(context, engine, records.filter { it.id in running })
        val apps = RunningModel.build(records, running, AppVisuals::label, AppVisuals::appLabel, startedAt)
        if (!adopted) {
            // Notifications left behind by a process that was killed would keep dead Stop buttons.
            adopted = true
            cancelTags(activeTags())
            posted = emptyList()
        }
        val diff = RunningModel.diff(posted, apps)
        if (diff.isEmpty) return
        val byId = records.associateBy { it.id }
        var allPosted = true
        for (post in diff.post) {
            if (closed) return
            val notification = when (post) {
                is RunningPost.Child -> byId[post.clone.id]?.let { child(post, it) }
                is RunningPost.Summary -> summary(post)
            } ?: continue
            if (!notify(post.tag, notification)) allPosted = false
        }
        if (closed) return
        cancelTags(diff.cancel)
        // A failed post is retried on the next pass, because the model is then still seen as changed.
        if (allPosted) posted = apps
    }

    private fun tagsOf(apps: List<RunningApp>): List<String> =
        apps.flatMap { app -> app.clones.map { RunningModel.childTag(it.id) } } + apps.map { RunningModel.summaryTag(it.packageName) }

    private fun activeTags(): List<String> = try {
        manager.activeNotifications.mapNotNull { it.tag }.filter(RunningModel::isOurs)
    } catch (e: RuntimeException) {
        emptyList()
    }

    private fun cancelTags(tags: Collection<String>) {
        for (tag in tags) {
            try {
                manager.cancel(tag, ID)
            } catch (e: RuntimeException) {
                // Nothing to cancel.
            }
        }
    }

    private fun notify(tag: String, notification: Notification): Boolean = try {
        manager.notify(tag, ID, notification)
        true
    } catch (e: SecurityException) {
        false // notifications not allowed
    } catch (e: RuntimeException) {
        Log.w(TAG, "Could not post $tag", e)
        false
    }

    private fun child(post: RunningPost.Child, record: CloneRecord): Notification {
        val clone = post.clone
        return NotificationCompat.Builder(context, KeepAliveNotifications.CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(largeIcon(record, clone))
            .setContentTitle(clone.name)
            .setContentText(context.getString(R.string.running_clone_text, clone.number))
            .setGroup(RunningModel.groupKey(post.packageName))
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setSortKey(clone.sortKey)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(notifications.launchIntent(clone.id))
            .addAction(0, context.getString(R.string.running_stop), stopIntent(clone.id))
            .build()
    }

    private fun summary(post: RunningPost.Summary): Notification {
        val text = context.resources.getQuantityString(R.plurals.running_summary, post.total, post.total)
        val inbox = NotificationCompat.InboxStyle().setBigContentTitle(post.appLabel).setSummaryText(text)
        post.names.forEach { inbox.addLine(it) }
        return NotificationCompat.Builder(context, KeepAliveNotifications.CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(post.appLabel)
            .setContentText(text)
            .setStyle(inbox)
            .setGroup(RunningModel.groupKey(post.packageName))
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(groupIntent(post.packageName))
            .addAction(0, context.getString(R.string.running_stop_all), stopAllIntent(post.packageName))
            .build()
    }

    /** The clone's own icon as a bitmap (cached, drawn here on the IO thread), or null for "no large icon". */
    private fun largeIcon(record: CloneRecord, clone: RunningClone): Bitmap? {
        val key = "${RunningModel.childTag(clone.id)}|${clone.iconKey}|${clone.name}"
        bitmaps.get(key)?.let { return it }
        return try {
            val drawable = AppVisuals.icon(context, record)
            val size = (ICON_DP * context.resources.displayMetrics.density).toInt().coerceIn(MIN_ICON_PX, MAX_ICON_PX)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bitmap))
            bitmaps.put(key, bitmap)
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    // PendingIntents differ by data URI (extras do not count for equality): one URI per clone or app.
    private fun stopIntent(id: CloneId): PendingIntent = broadcast(
        RunningActionsReceiver.intent(context, RunningActionsReceiver.ACTION_STOP, id.packageName, id.userId)
            .setData(Uri.parse("clonelab://stop/${Uri.encode(id.packageName)}/${id.userId}")),
    )

    private fun stopAllIntent(packageName: String): PendingIntent = broadcast(
        RunningActionsReceiver.intent(context, RunningActionsReceiver.ACTION_STOP_ALL, packageName, 0)
            .setData(Uri.parse("clonelab://stopall/${Uri.encode(packageName)}")),
    )

    private fun broadcast(intent: Intent): PendingIntent =
        PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag())

    /** Opens the app's clone page with Home underneath, so Back leads into the app. */
    private fun groupIntent(packageName: String): PendingIntent {
        val target = CloneGroupActivity.intent(context, packageName)
            .setData(Uri.parse("clonelab://group/${Uri.encode(packageName)}"))
        return TaskStackBuilder.create(context)
            .addNextIntent(Intent(context, HomeActivity::class.java))
            .addNextIntent(target)
            .getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag())
            ?: notifications.activity(target)
    }

    private fun immutableFlag() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    companion object {
        private const val TAG = "RunningNotifications"
        private const val ID = 4103
        private const val DEBOUNCE_MS = 3_000L
        private const val STOP_GRACE_MS = 5_000L
        private const val ICON_DP = 48
        private const val MIN_ICON_PX = 64
        private const val MAX_ICON_PX = 192

        @Volatile
        private var active: RunningNotifications? = null

        /**
         * Called after the user stopped [ids] from a notification ([wholeApp]: with "Stop all" for that
         * package). The running service refreshes at once; without one the notifications are stale, so the
         * stopped ones are cancelled directly.
         */
        fun onStopped(context: Context, ids: Collection<CloneId>, wholeApp: String? = null) {
            val service = active
            if (service != null) {
                service.stopped(ids)
                return
            }
            val manager = NotificationManagerCompat.from(context.applicationContext)
            ids.forEach { manager.cancel(RunningModel.childTag(it), ID) }
            if (wholeApp != null) manager.cancel(RunningModel.summaryTag(wholeApp), ID)
        }
    }
}
