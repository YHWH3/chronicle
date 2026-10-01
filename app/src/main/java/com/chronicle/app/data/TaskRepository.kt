package com.chronicle.app.data

import com.chronicle.app.data.db.TaskDao
import com.chronicle.app.data.db.TaskEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime
import java.time.ZoneId

class TaskRepository(private val taskDao: TaskDao) {

    fun observeAll(): Flow<List<TaskEntity>> = taskDao.observeAll()

    suspend fun byId(id: Long): TaskEntity? = taskDao.byId(id)

    suspend fun pendingReminders(afterMillis: Long): List<TaskEntity> =
        taskDao.pendingReminders(afterMillis)

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

    suspend fun updateRemindAt(task: TaskEntity, remindAt: Long?) =
        taskDao.update(task.copy(remindAt = remindAt, done = false, doneAt = null))

    suspend fun delete(id: Long) = taskDao.deleteById(id)
}
