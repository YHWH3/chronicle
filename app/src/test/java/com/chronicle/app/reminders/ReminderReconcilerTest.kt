package com.chronicle.app.reminders

import com.chronicle.app.data.TaskRepository
import com.chronicle.app.data.db.TaskDao
import com.chronicle.app.data.db.TaskEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BE-01 — the reconcile pass: missed reminders fire late-but-once, future
 * ones re-arm, and a reminder pushed forward after firing re-delivers.
 * Runs entirely on fakes — TaskDao semantics are reproduced, not mocked.
 */
class ReminderReconcilerTest {

    private class FakeTaskDao : TaskDao {
        val rows = MutableStateFlow<List<TaskEntity>>(emptyList())

        override fun observeAll(): Flow<List<TaskEntity>> = rows
        override suspend fun byId(id: Long) = rows.value.firstOrNull { it.id == id }
        override suspend fun pendingReminders(after: Long) = rows.value.filter {
            !it.done && it.remindAt != null && it.remindAt > after
        }
        override suspend fun pendingRemindersDue(now: Long) = rows.value.filter {
            !it.done && it.remindAt != null && it.notifiedAt == null && it.remindAt <= now
        }
        override suspend fun markNotified(id: Long, now: Long): Int {
            val row = rows.value.firstOrNull { it.id == id } ?: return 0
            if (row.notifiedAt != null) return 0
            rows.value = rows.value.map {
                if (it.id == id) it.copy(notifiedAt = now) else it
            }
            return 1
        }
        override suspend fun clearNotified(id: Long) {
            rows.value = rows.value.map {
                if (it.id == id) it.copy(notifiedAt = null) else it
            }
        }
        override suspend fun updateDetails(id: Long, title: String, remindAt: Long?) {
            rows.value = rows.value.map {
                if (it.id == id) it.copy(title = title, remindAt = remindAt, notifiedAt = null) else it
            }
        }
        override suspend fun insert(task: TaskEntity) = task.id
        override suspend fun update(task: TaskEntity) {}
        override suspend fun delete(task: TaskEntity) {}
        override suspend fun deleteById(id: Long) {}
        override suspend fun clearSourceEntry(entryId: Long) {}
    }

    private class FakeGateway : ReminderGateway {
        val scheduled = mutableListOf<Pair<Long, Long>>()
        val notified = mutableListOf<Long>()
        override fun scheduleTask(taskId: Long, title: String, atMillis: Long) {
            scheduled += taskId to atMillis
        }
        override fun cancelTask(taskId: Long) {}
        override fun notifyTask(taskId: Long, title: String) {
            notified += taskId
        }
        override fun cancelNotification(taskId: Long) {}
        override fun scheduleDailyNudge(minutesAfterMidnight: Int) {}
        override fun cancelDailyNudge() {}
        override fun notificationsEnabled() = true
    }

    private fun task(
        id: Long,
        remindAt: Long? = null,
        done: Boolean = false,
        notifiedAt: Long? = null,
    ) = TaskEntity(
        id = id,
        title = "task-$id",
        createdAt = 0,
        remindAt = remindAt,
        done = done,
        notifiedAt = notifiedAt,
    )

    private val now = 1_000_000L

    private fun reconciler(
        dao: FakeTaskDao,
        gateway: FakeGateway,
        at: Long = now,
    ) = ReminderReconciler(TaskRepository(dao), gateway) { at }

    @Test
    fun `missed reminder fires once and is marked`() = runTest {
        val dao = FakeTaskDao()
        val gateway = FakeGateway()
        dao.rows.value = listOf(task(id = 1, remindAt = now - 60_000))
        reconciler(dao, gateway).reconcile()

        assertEquals(listOf(1L), gateway.notified)
        assertEquals(now, dao.rows.value[0].notifiedAt)

        // A second pass must not double-notify — the claim is exhausted.
        reconciler(dao, gateway).reconcile()
        assertEquals(listOf(1L), gateway.notified)
    }

    @Test
    fun `done task never notifies and never re-arms`() = runTest {
        val dao = FakeTaskDao()
        val gateway = FakeGateway()
        dao.rows.value = listOf(task(id = 1, remindAt = now - 60_000, done = true))
        reconciler(dao, gateway).reconcile()
        assertTrue(gateway.notified.isEmpty())
        assertTrue(gateway.scheduled.isEmpty())
    }

    @Test
    fun `future reminder re-arms without notifying`() = runTest {
        val dao = FakeTaskDao()
        val gateway = FakeGateway()
        dao.rows.value = listOf(task(id = 1, remindAt = now + 60_000))
        reconciler(dao, gateway).reconcile()

        assertTrue(gateway.notified.isEmpty())
        assertEquals(listOf(1L to now + 60_000), gateway.scheduled)
        assertNull(dao.rows.value[0].notifiedAt)
    }

    @Test
    fun `reminder moved forward after firing is cleared and re-armed`() = runTest {
        val dao = FakeTaskDao()
        val gateway = FakeGateway()
        // Fired yesterday, then edited to a future time — must deliver again.
        dao.rows.value = listOf(
            task(id = 1, remindAt = now + 60_000, notifiedAt = now - 120_000),
        )
        reconciler(dao, gateway).reconcile()

        assertTrue(gateway.notified.isEmpty())
        assertEquals(listOf(1L to now + 60_000), gateway.scheduled)
        assertNull(dao.rows.value[0].notifiedAt)
    }

    @Test
    fun `already-notified past reminder stays silent`() = runTest {
        val dao = FakeTaskDao()
        val gateway = FakeGateway()
        dao.rows.value = listOf(
            task(id = 1, remindAt = now - 60_000, notifiedAt = now - 60_000),
        )
        reconciler(dao, gateway).reconcile()
        assertTrue(gateway.notified.isEmpty())
        assertTrue(gateway.scheduled.isEmpty())
    }

    @Test
    fun `tasks without reminders are left alone`() = runTest {
        val dao = FakeTaskDao()
        val gateway = FakeGateway()
        dao.rows.value = listOf(task(id = 1), task(id = 2, remindAt = null))
        reconciler(dao, gateway).reconcile()
        assertTrue(gateway.notified.isEmpty())
        assertTrue(gateway.scheduled.isEmpty())
    }

    @Test
    fun `receiver-style claim loses the race after reconcile notified`() = runTest {
        val dao = FakeTaskDao()
        val gateway = FakeGateway()
        dao.rows.value = listOf(task(id = 1, remindAt = now - 60_000))
        val repo = TaskRepository(dao)
        ReminderReconciler(repo, gateway) { now }.reconcile()

        // A stale alarm delivering late: the claim is already spent.
        assertEquals(false, repo.markNotified(1, now + 1_000))
        assertEquals(now, dao.rows.value[0].notifiedAt)
    }
}
