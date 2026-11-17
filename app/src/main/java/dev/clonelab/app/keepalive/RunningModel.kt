package dev.clonelab.app.keepalive

import dev.clonelab.app.model.CloneId
import dev.clonelab.app.model.CloneRecord

/** One running clone as the notification shows it. [sortKey] keeps store order inside its app group. */
data class RunningClone(
    val id: CloneId,
    val name: String,
    val number: Int,
    val sortKey: String,
    /** Changes whenever the clone's icon changes, so the notification is posted again with the new one. */
    val iconKey: String,
)

/** An app with at least one running clone. [total] counts every running clone; [clones] only those shown. */
data class RunningApp(val packageName: String, val appLabel: String, val total: Int, val clones: List<RunningClone>)

/** What to show for one tag. Equal entries need no new `notify`. */
sealed interface RunningPost {
    val tag: String

    data class Child(val packageName: String, val clone: RunningClone) : RunningPost {
        override val tag: String get() = RunningModel.childTag(clone.id)
    }

    data class Summary(val packageName: String, val appLabel: String, val total: Int, val names: List<String>) : RunningPost {
        override val tag: String get() = RunningModel.summaryTag(packageName)
    }
}

data class RunningDiff(val post: List<RunningPost>, val cancel: List<String>) {
    val isEmpty: Boolean get() = post.isEmpty() && cancel.isEmpty()
}

/** Pure model of the "running clones" notifications: grouping, ordering, the cap and the diff. */
object RunningModel {
    /**
     * Android 12+ limits an app to about 50 notifications and a group to about 24 children; excess ones are
     * dropped by the system without notice. At most this many clone notifications are posted in total,
     * the most recently started first (see `startedAt` in [build]); the rest stay running but unlisted.
     */
    const val MAX_CLONES = 24

    fun childTag(id: CloneId) = "running:${id.packageName}#${id.userId}"
    fun summaryTag(packageName: String) = "running-summary:$packageName"
    fun groupKey(packageName: String) = "running:$packageName"

    /** True for every tag this feature posts, whatever the app. */
    fun isOurs(tag: String?) = tag != null && (tag.startsWith("running:") || tag.startsWith("running-summary:"))

    /**
     * Apps in store order (the first clone of an app decides its place), each with its running clones in
     * store order. When more than [cap] clones run, the ones in [startedAt] with the latest time are kept
     * (store order breaks ties); apps left without a shown clone are omitted.
     */
    fun build(
        records: List<CloneRecord>,
        running: Set<CloneId>,
        label: (CloneRecord) -> String,
        appLabel: (packageName: String, stored: String) -> String,
        startedAt: Map<CloneId, Long> = emptyMap(),
        cap: Int = MAX_CLONES,
    ): List<RunningApp> {
        val ordered = records.sortedWith(compareBy({ it.sortOrder }, { it.createdAt }, { it.id.userId }))
        val live = ordered.withIndex().filter { it.value.id in running }
        val kept = live.sortedWith(compareByDescending<IndexedValue<CloneRecord>> { startedAt[it.value.id] ?: 0L }.thenBy { it.index })
            .take(cap).map { it.index }.toSet()
        val totals = live.groupingBy { it.value.id.packageName }.eachCount()
        val apps = LinkedHashMap<String, MutableList<RunningClone>>()
        val stored = HashMap<String, String>()
        for ((index, record) in live) {
            val pkg = record.id.packageName
            if (record.appLabel.isNotBlank()) stored.putIfAbsent(pkg, record.appLabel)
            if (index !in kept) continue
            apps.getOrPut(pkg) { mutableListOf() }.add(
                RunningClone(
                    id = record.id,
                    name = label(record),
                    number = record.number,
                    sortKey = index.toString().padStart(SORT_KEY_WIDTH, '0'),
                    iconKey = "${record.iconKind}|${record.iconColor}|${record.iconImagePath}|${record.iconVersion}",
                ),
            )
        }
        return apps.map { (pkg, clones) -> RunningApp(pkg, appLabel(pkg, stored[pkg] ?: pkg), totals.getValue(pkg), clones) }
    }

    /** Everything one set of apps shows, keyed by tag. */
    private fun posts(apps: List<RunningApp>): Map<String, RunningPost> {
        val result = LinkedHashMap<String, RunningPost>()
        for (app in apps) {
            for (clone in app.clones) RunningPost.Child(app.packageName, clone).let { result[it.tag] = it }
        }
        for (app in apps) {
            RunningPost.Summary(app.packageName, app.appLabel, app.total, app.clones.map { it.name }).let { result[it.tag] = it }
        }
        return result
    }

    /** What to post (new or changed; children before summaries) and which tags to cancel (children first). */
    fun diff(old: List<RunningApp>, new: List<RunningApp>): RunningDiff {
        val before = posts(old)
        val after = posts(new)
        val post = after.values.filter { before[it.tag] != it }
        val cancel = before.keys.filter { it !in after }
        return RunningDiff(post, cancel.sortedBy { it.startsWith("running-summary:") })
    }

    private const val SORT_KEY_WIDTH = 6
}
