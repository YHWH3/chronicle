package com.chronicle.app.data

import com.chronicle.app.data.db.TaskDao
import com.chronicle.app.data.db.TaskEntity
import com.chronicle.app.reminders.ReminderGateway
import com.chronicle.app.reminders.ReminderReconciler
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime
import java.time.ZoneId

class TaskRepository(private val taskDao: TaskDao) {

    fun observeAll(): Flow<List<TaskEntity>> = taskDao.observeAll()

    suspend fun byId(id: Long): TaskEntity? = taskDao.byId(id)

    /** Undone tasks whose reminder is still in the future — armed or not. */
    suspend fun pendingReminders(afterMillis: Long): List<TaskEntity> =
        taskDao.pendingReminders(afterMillis)

    /** Past-due reminders that never fired — missed while off or dead. */
    suspend fun pendingRemindersDue(nowMillis: Long): List<TaskEntity> =
        taskDao.pendingRemindersDue(nowMillis)

    /**
     * Once-only delivery claim. True when this call flipped notifiedAt from
     * null — false when another path (receiver, reconciler) already claimed
     * the fire, so the loser must not post a second notification.
     */
    suspend fun markNotified(id: Long, nowMillis: Long): Boolean =
        taskDao.markNotified(id, nowMillis) > 0

    /** Re-arms delivery — a moved or un-completed reminder may fire again. */
    suspend fun clearNotified(id: Long) = taskDao.clearNotified(id)

    suspend fun add(
        title: String,
        remindAt: Long? = null,
        sourceEntryId: Long? = null,
        now: LocalDateTime = LocalDateTime.now(),
    ): Long =
        taskDao.insert(
            TaskEntity(
                title = title.trim(),
                createdAt = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                remindAt = remindAt,
                sourceEntryId = sourceEntryId,
            ),
        )

    suspend fun setDone(task: TaskEntity, done: Boolean) =
        taskDao.update(
            task.copy(
                done = done,
                doneAt = if (done) System.currentTimeMillis() else null,
            ),
        )

    /**
     * Targeted edit — title and/or reminder only. Done state is untouched,
     * and the DAO clears notifiedAt so a rescheduled reminder delivers.
     */
    suspend fun updateDetails(id: Long, title: String, remindAt: Long?) =
        taskDao.updateDetails(id, title.trim(), remindAt)

    suspend fun delete(id: Long) = taskDao.deleteById(id)

    /** Detaches intentions from a deleted entry — the tasks themselves survive. */
    suspend fun clearSourceEntry(entryId: Long) = taskDao.clearSourceEntry(entryId)

    /**
     * Startup catch-up: fires reminders missed while the phone was off,
     * re-arms future alarms, repairs edited-forward deliveries. Invoke from
     * the application entry point (ChronicleApp.onCreate via appScope) —
     * receivers run it themselves.
     */
    suspend fun reconcileReminders(
        reminders: ReminderGateway,
        nowMs: () -> Long = { System.currentTimeMillis() },
    ) = ReminderReconciler(this, reminders, nowMs).reconcile()
}
