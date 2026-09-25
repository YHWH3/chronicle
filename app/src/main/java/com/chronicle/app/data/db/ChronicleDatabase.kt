package com.chronicle.app.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "entries")
data class EntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    /** ISO-8601 local date, e.g. 2026-09-25 — the day the entry belongs to. */
    val day: String,
    /** "voice" or "text". */
    val kind: String,
    val transcript: String,
    val audioPath: String? = null,
    val durationMs: Long = 0,
)

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val remindAt: Long? = null,
    val done: Boolean = false,
    val doneAt: Long? = null,
    val sourceEntryId: Long? = null,
)

@Dao
interface EntryDao {
    @Query("SELECT * FROM entries ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE day = :day ORDER BY createdAt ASC")
    fun observeDay(day: String): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries ORDER BY createdAt ASC")
    suspend fun allOnce(): List<EntryEntity>

    @Insert
    suspend fun insert(entry: EntryEntity): Long

    @Delete
    suspend fun delete(entry: EntryEntity)

    @Query("DELETE FROM entries WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface TaskDao {
    @Query(
        "SELECT * FROM tasks ORDER BY done ASC, " +
            "CASE WHEN remindAt IS NULL THEN 1 ELSE 0 END, remindAt ASC, createdAt DESC",
    )
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun byId(id: Long): TaskEntity?

    @Query("SELECT * FROM tasks WHERE done = 0 AND remindAt IS NOT NULL AND remindAt > :after")
    suspend fun pendingReminders(after: Long): List<TaskEntity>

    @Insert
    suspend fun insert(task: TaskEntity): Long

    @Update
    suspend fun update(task: TaskEntity)

    @Delete
    suspend fun delete(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Database(entities = [EntryEntity::class, TaskEntity::class], version = 1, exportSchema = false)
abstract class ChronicleDatabase : RoomDatabase() {
    abstract fun entryDao(): EntryDao
    abstract fun taskDao(): TaskDao
}
