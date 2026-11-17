package dev.clonelab.app.keepalive

import dev.clonelab.app.model.CloneId
import dev.clonelab.app.model.CloneRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunningModelTest {

    private fun record(pkg: String, user: Int, order: Int, appLabel: String = pkg.substringAfterLast('.'), name: String? = null) =
        CloneRecord(CloneId(pkg, user), appLabel, customName = name, sortOrder = order, createdAt = order.toLong())

    private fun build(
        records: List<CloneRecord>,
        running: Set<CloneId>,
        startedAt: Map<CloneId, Long> = emptyMap(),
        cap: Int = RunningModel.MAX_CLONES,
    ) = RunningModel.build(records, running, { it.displayName }, { _, stored -> stored }, startedAt, cap)

    private val a0 = record("com.a", 0, 0)
    private val b0 = record("com.b", 0, 1)
    private val a1 = record("com.a", 1, 2)

    @Test
    fun onlyRunningClonesAreListedGroupedByAppInStoreOrder() {
        val apps = build(listOf(a1, b0, a0), setOf(a0.id, a1.id, b0.id))
        assertEquals(listOf("com.a", "com.b"), apps.map { it.packageName })
        assertEquals(listOf(a0.id, a1.id), apps[0].clones.map { it.id })
        assertEquals(2, apps[0].total)
        assertEquals("a", apps[0].appLabel)
        assertTrue(apps[0].clones[0].sortKey < apps[0].clones[1].sortKey)
    }

    @Test
    fun stoppedClonesAndAppsWithoutRunningClonesAreLeftOut() {
        val apps = build(listOf(a0, b0, a1), setOf(a1.id))
        assertEquals(1, apps.size)
        assertEquals(listOf(a1.id), apps[0].clones.map { it.id })
        assertEquals("Clone number is userId + 1", 2, apps[0].clones[0].number)
        assertTrue(build(listOf(a0), emptySet()).isEmpty())
    }

    @Test
    fun singleCloneAppStillGetsChildAndSummary() {
        val apps = build(listOf(b0), setOf(b0.id))
        val diff = RunningModel.diff(emptyList(), apps)
        assertEquals(
            listOf("running:com.b#0", "running-summary:com.b"),
            diff.post.map { it.tag },
        )
        assertTrue(diff.cancel.isEmpty())
    }

    @Test
    fun capKeepsTheMostRecentlyStartedAndStoreOrderOnTies() {
        val records = (0 until 30).map { record("com.many", it, it) }
        val all = records.map { it.id }.toSet()
        val tied = build(records, all)
        assertEquals(RunningModel.MAX_CLONES, tied.single().clones.size)
        assertEquals(30, tied.single().total)
        assertEquals((0 until 24).toList(), tied.single().clones.map { it.id.userId })

        val started = records.associate { it.id to (if (it.id.userId >= 26) 100L else 1L) }
        val recent = build(records, all, started)
        val users = recent.single().clones.map { it.id.userId }
        assertEquals(24, users.size)
        assertTrue(users.containsAll(listOf(26, 27, 28, 29)))
        assertEquals("still shown in store order", users.sorted(), users)
    }

    @Test
    fun capIsTotalAcrossApps() {
        val records = (0 until 3).map { record("com.x", it, it) } + (0 until 3).map { record("com.y", it, 10 + it) }
        val apps = build(records, records.map { it.id }.toSet(), cap = 4)
        assertEquals(4, apps.sumOf { it.clones.size })
    }

    @Test
    fun capCanDropAnAppEntirely() {
        val records = listOf(record("com.x", 0, 0), record("com.y", 0, 1))
        val apps = build(records, records.map { it.id }.toSet(), mapOf(records[1].id to 5L), cap = 1)
        assertEquals(listOf("com.y"), apps.map { it.packageName })
    }

    @Test
    fun diffOfEqualModelsIsEmpty() {
        val apps = build(listOf(a0, a1), setOf(a0.id, a1.id))
        assertTrue(RunningModel.diff(apps, build(listOf(a0, a1), setOf(a0.id, a1.id))).isEmpty)
    }

    @Test
    fun startedCloneIsPostedWithItsSummaryOnly() {
        val before = build(listOf(a0, a1), setOf(a0.id))
        val after = build(listOf(a0, a1), setOf(a0.id, a1.id))
        val diff = RunningModel.diff(before, after)
        assertEquals(listOf("running:com.a#1", "running-summary:com.a"), diff.post.map { it.tag })
        assertTrue(diff.cancel.isEmpty())
    }

    @Test
    fun stoppedCloneIsCancelledAndSummaryUpdated() {
        val before = build(listOf(a0, a1), setOf(a0.id, a1.id))
        val after = build(listOf(a0, a1), setOf(a0.id))
        val diff = RunningModel.diff(before, after)
        assertEquals(listOf("running-summary:com.a"), diff.post.map { it.tag })
        assertEquals(listOf("running:com.a#1"), diff.cancel)
    }

    @Test
    fun lastCloneOfAnAppStoppingCancelsChildAndSummary() {
        val before = build(listOf(a0, b0), setOf(a0.id, b0.id))
        val after = build(listOf(a0, b0), setOf(b0.id))
        val diff = RunningModel.diff(before, after)
        assertTrue(diff.post.isEmpty())
        assertEquals(listOf("running:com.a#0", "running-summary:com.a"), diff.cancel)
    }

    @Test
    fun renamedCloneIsPostedAgainWithItsSummary() {
        val before = build(listOf(a0, a1), setOf(a0.id, a1.id))
        val renamed = a1.copy(customName = "Work")
        val after = build(listOf(a0, renamed), setOf(a0.id, a1.id))
        val diff = RunningModel.diff(before, after)
        assertEquals(listOf("running:com.a#1", "running-summary:com.a"), diff.post.map { it.tag })
        val summary = diff.post.last() as RunningPost.Summary
        assertEquals(listOf("a 1", "Work"), summary.names)
    }

    @Test
    fun iconChangeRepostsOnlyThatChild() {
        val before = build(listOf(a0, a1), setOf(a0.id, a1.id))
        val after = build(listOf(a0, a1.copy(iconVersion = 3)), setOf(a0.id, a1.id))
        assertEquals(listOf("running:com.a#1"), RunningModel.diff(before, after).post.map { it.tag })
    }

    @Test
    fun tagsAreStableAndDistinctAndRecognised() {
        assertEquals("running:com.a#1", RunningModel.childTag(CloneId("com.a", 1)))
        assertEquals("running:com.a", RunningModel.groupKey("com.a"))
        assertTrue(RunningModel.isOurs(RunningModel.childTag(a0.id)))
        assertTrue(RunningModel.isOurs(RunningModel.summaryTag("com.a")))
        assertTrue(!RunningModel.isOurs("stopped:com.a#0"))
        assertTrue(!RunningModel.isOurs(null))
    }
}
