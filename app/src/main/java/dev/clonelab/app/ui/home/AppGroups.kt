package dev.clonelab.app.ui.home

import dev.clonelab.app.data.CloneItem
import dev.clonelab.app.model.CloneRecord

/**
 * One home tile: every clone of [packageName]. [representative] is the clone that sorts first;
 * [label] is its stored app label (the UI may prefer an APK-derived one for apps not on the phone).
 */
data class AppGroup(
    val packageName: String,
    val label: String,
    val cloneCount: Int,
    val anyRunning: Boolean,
    val representative: CloneRecord,
)

private val cloneOrder = compareBy<CloneItem>({ it.record.sortOrder }, { it.record.createdAt }, { it.record.id.userId })

/** One group per package, ordered by the smallest sortOrder (then earliest createdAt) among its clones. */
fun groupByApp(items: List<CloneItem>): List<AppGroup> =
    items.groupBy { it.record.id.packageName }
        .map { (packageName, clones) ->
            val sorted = clones.sortedWith(cloneOrder)
            val representative = sorted.first().record
            val label = sorted.map { it.record.appLabel }.firstOrNull { it.isNotBlank() } ?: packageName
            AppGroup(packageName, label, clones.size, clones.any { it.running }, representative)
        }
        .sortedWith(compareBy({ it.representative.sortOrder }, { it.representative.createdAt }, { it.packageName }))
