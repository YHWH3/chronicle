package com.chronicle.app.ui.vm

import com.chronicle.app.core.UiMessage
import com.chronicle.app.core.UiMessageBus
import com.chronicle.app.data.TaskRepository
import com.chronicle.app.data.db.TaskDao
import com.chronicle.app.data.db.TaskEntity
import com.chronicle.app.reminders.ReminderGateway
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * BE-02 / FE-10 — reminder side effects of task edits: completing or
 * deleting tears down both the alarm and the notification; un-completing
 * or rescheduling clears the delivery marker and re-arms.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModelReminderTest {

    @get:Rule val mainRule = MainDispatcherRule()

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
                if (it.id == id) {
                    it.copy(title = title, remindAt = remindAt, notifiedAt = null)
                } else {
                    it
                }
            }
        }
        override suspend fun insert(task: TaskEntity): Long {
            rows.value = rows.value + task
            return task.id
        }
        override suspend fun update(task: TaskEntity) {
            rows.value = rows.value.map { if (it.id == task.id) task else it }
        }
        override suspend fun delete(task: TaskEntity) {
            rows.value = rows.value.filter { it.id != task.id }
        }
        override suspend fun deleteById(id: Long) {
            rows.value = rows.value.filter { it.id != id }
        }
        override suspend fun clearSourceEntry(entryId: Long) {}
    }

    private class FakeGateway : ReminderGateway {
        val events = mutableListOf<String>()
        var notificationsEnabled = true
        override fun scheduleTask(taskId: Long, title: String, atMillis: Long) {
            events += "schedule:$taskId"
        }
        override fun cancelTask(taskId: Long) {
            events += "cancel-alarm:$taskId"
        }
        override fun notifyTask(taskId: Long, title: String) {
            events += "notify:$taskId"
        }
        override fun cancelNotification(taskId: Long) {
            events += "cancel-notif:$taskId"
        }
        override fun scheduleDailyNudge(minutesAfterMidnight: Int) {}
        override fun cancelDailyNudge() {}
        override fun notificationsEnabled() = notificationsEnabled
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

    private fun vm(dao: FakeTaskDao, gateway: FakeGateway, bus: UiMessageBus = UiMessageBus()) =
        TasksViewModel(TaskRepository(dao), gateway, bus)

    @Test
    fun `completing cancels both the alarm and the notification`() = runTest {
        val dao = FakeTaskDao()
        val gateway = FakeGateway()
        dao.rows.value = listOf(task(1, remindAt = System.currentTimeMillis() + 60_000))
        val v = vm(dao, gateway)
        runCurrent()

        v.toggleDone(dao.rows.value[0])
        runCurrent()

        assertTrue("cancel-alarm:1" in gateway.events)
        assertTrue("cancel-notif:1" in gateway.events)
        assertTrue(dao.rows.value[0].done)
    }

    @Test
    fun `un-completing a future reminder clears notifiedAt and re-arms`() = runTest {
        val dao = FakeTaskDao()
        val gateway = FakeGateway()
        val future = System.currentTimeMillis() + 60_000
        dao.rows.value = listOf(task(1, remindAt = future, done = true, notifiedAt = 1234L))
        val v = vm(dao, gateway)
        runCurrent()

        v.toggleDone(dao.rows.value[0])
        runCurrent()

        val row = dao.rows.value[0]
        assertTrue(!row.done)
        assertNull(row.doneAt)
        assertNull(row.notifiedAt)
        assertTrue("schedule:1" in gateway.events)
    }

    @Test
    fun `un-completing a task with no future reminder does not re-arm`() = runTest {
        val dao = FakeTaskDao()
        val gateway = FakeGateway()
        dao.rows.value = listOf(task(1, done = true))
        val v = vm(dao, gateway)
        runCurrent()

        v.toggleDone(dao.rows.value[0])
        runCurrent()
        assertTrue(gateway.events.isEmpty())
    }

    @Test
    fun `delete cancels both the alarm and the notification`() = runTest {
        val dao = FakeTaskDao()
        val gateway = FakeGateway()
        dao.rows.value = listOf(task(1, remindAt = System.currentTimeMillis() + 60_000))
        val v = vm(dao, gateway)
        runCurrent()

        v.delete(dao.rows.value[0])
        runCurrent()

        assertTrue("cancel-alarm:1" in gateway.events)
        assertTrue("cancel-notif:1" in gateway.events)
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun `updateTask reschedules and clears the delivery marker`() = runTest {
        val dao = FakeTaskDao()
        val gateway = FakeGateway()
        val future = System.currentTimeMillis() + 3_600_000
        dao.rows.value = listOf(
            task(1, remindAt = future - 10_000, notifiedAt = future - 20_000),
        )
        val v = vm(dao, gateway)
        runCurrent()

        v.updateTask(1, "renamed", future)
        runCurrent()

        val row = dao.rows.value[0]
        assertEquals("renamed", row.title)
        assertEquals(future, row.remindAt)
        assertNull(row.notifiedAt)
        assertTrue("cancel-alarm:1" in gateway.events)
        assertTrue("cancel-notif:1" in gateway.events)
        assertTrue("schedule:1" in gateway.events)
    }

    @Test
    fun `updateTask clearing the reminder arms nothing`() = runTest {
        val dao = FakeTaskDao()
        val gateway = FakeGateway()
        dao.rows.value = listOf(task(1, remindAt = System.currentTimeMillis() + 60_000))
        val v = vm(dao, gateway)
        runCurrent()

        v.updateTask(1, "renamed", null)
        runCurrent()

        assertNull(dao.rows.value[0].remindAt)
        assertTrue("schedule:1" !in gateway.events)
        assertTrue("cancel-alarm:1" in gateway.events)
    }

    @Test
    fun `updateTask on a done task leaves it done`() = runTest {
        val dao = FakeTaskDao()
        val gateway = FakeGateway()
        dao.rows.value = listOf(task(1, done = true))
        val v = vm(dao, gateway)
        runCurrent()

        v.updateTask(1, "renamed", null)
        runCurrent()
        assertTrue(dao.rows.value[0].done)
    }

    @Test
    fun `updateTask with notifications off surfaces the quiet message`() = runTest {
        val dao = FakeTaskDao()
        val gateway = FakeGateway()
        gateway.notificationsEnabled = false
        val bus = UiMessageBus()
        val messages = mutableListOf<UiMessage>()
        backgroundScope.launch { bus.messages.collect { messages += it } }
        dao.rows.value = listOf(task(1))
        val v = vm(dao, gateway, bus)
        runCurrent()

        v.updateTask(1, "renamed", System.currentTimeMillis() + 60_000)
        runCurrent()

        assertTrue(messages.any { it is UiMessage.NotificationsOff })
    }
}
