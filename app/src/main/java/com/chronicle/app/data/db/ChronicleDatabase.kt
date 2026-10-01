package com.chronicle.app.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** Transcript lifecycle values stored on [EntryEntity.transcriptStatus]. */
const val TRANSCRIPT_PENDING = "pending"
const val TRANSCRIPT_READY = "ready"
const val TRANSCRIPT_FAILED = "failed"
const val TRANSCRIPT_NOT_REQUESTED = "not_requested"

@Entity(
    tableName = "entries",
    indices = [Index("day"), Index("createdAt")],
)
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
    /** TRANSCRIPT_* — how [transcript] was produced; drives the retry affordance. */
    val transcriptStatus: String = TRANSCRIPT_READY,
    /** ZoneId.getId() captured at write time so times render in the writer's zone. */
    val zoneId: String? = null,
)

@Entity(
    tableName = "tasks",
    indices = [Index("remindAt"), Index("done")],
)
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val remindAt: Long? = null,
    val done: Boolean = false,
    val doneAt: Long? = null,
    val sourceEntryId: Long? = null,
    /** When the reminder actually fired — distinguishes missed from pending. */
    val notifiedAt: Long? = null,
)

@Dao
interface EntryDao {
    @Query("SELECT * FROM entries ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE day = :day ORDER BY createdAt ASC")
    fun observeDay(day: String): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries ORDER BY createdAt ASC")
    suspend fun allOnce(): List<EntryEntity>

    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun byId(id: Long): EntryEntity?

    @Insert
    suspend fun insert(entry: EntryEntity): Long

    /**
     * Undo-delete: puts a removed row back with its original id (REPLACE is
     * a no-op conflict-wise — the row was deleted, nothing collides).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreservingId(entry: EntryEntity): Long

    @Query("UPDATE entries SET transcript = :transcript, transcriptStatus = :status WHERE id = :id")
    suspend fun updateTranscript(id: Long, transcript: String, status: String)

    @Query("UPDATE entries SET transcriptStatus = :status WHERE id = :id")
    suspend fun updateTranscriptStatus(id: Long, status: String)

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

    /**
     * Past-due reminders that never fired — the phone was off or the app
     * dead when the alarm window passed. Reconciler catch-up reads this.
     */
    @Query(
        "SELECT * FROM tasks WHERE done = 0 AND remindAt IS NOT NULL " +
            "AND notifiedAt IS NULL AND remindAt <= :now",
    )
    suspend fun pendingRemindersDue(now: Long): List<TaskEntity>

    /**
     * Once-only delivery claim: sets [notifiedAt] and returns rows changed —
     * >0 only when the reminder was still unclaimed, so a receiver and the
     * reconciler racing the same task produce exactly one notification.
     */
    @Query("UPDATE tasks SET notifiedAt = :now WHERE id = :id AND notifiedAt IS NULL")
    suspend fun markNotified(id: Long, now: Long): Int

    /** Re-arms a reminder for re-delivery (edited forward / un-completed). */
    @Query("UPDATE tasks SET notifiedAt = NULL WHERE id = :id")
    suspend fun clearNotified(id: Long)

    /**
     * Targeted edit — never touches done/doneAt (the old whole-row
     * updateRemindAt silently un-completed tasks). Editing clears
     * notifiedAt so a moved reminder can claim delivery again.
     */
    @Query("UPDATE tasks SET title = :title, remindAt = :remindAt, notifiedAt = NULL WHERE id = :id")
    suspend fun updateDetails(id: Long, title: String, remindAt: Long?)

    @Insert
    suspend fun insert(task: TaskEntity): Long

    @Update
    suspend fun update(task: TaskEntity)

    @Delete
    suspend fun delete(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Detaches intentions from a deleted entry — tasks survive on their own. */
    @Query("UPDATE tasks SET sourceEntryId = NULL WHERE sourceEntryId = :entryId")
    suspend fun clearSourceEntry(entryId: Long)
}

@Database(
    entities = [EntryEntity::class, TaskEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class ChronicleDatabase : RoomDatabase() {
    abstract fun entryDao(): EntryDao
    abstract fun taskDao(): TaskDao

    companion object {
        /**
         * v1 → v2: entry transcript lifecycle + writer's zone, task delivery
         * tracking, and the lookup indexes the new queries lean on.
         * Existing voice rows with no transcript were never visibly failing —
         * they're marked "not_requested" (audio note), not "failed".
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN notifiedAt INTEGER")
                db.execSQL(
                    "ALTER TABLE entries ADD COLUMN transcriptStatus TEXT NOT NULL DEFAULT 'ready'",
                )
                db.execSQL("ALTER TABLE entries ADD COLUMN zoneId TEXT")
                db.execSQL(
                    "UPDATE entries SET transcriptStatus = 'not_requested' " +
                        "WHERE kind = 'voice' AND (transcript IS NULL OR trim(transcript) = '')",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_entries_day ON entries(day)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_entries_createdAt ON entries(createdAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_remindAt ON tasks(remindAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_done ON tasks(done)")
            }
        }
    }
}
