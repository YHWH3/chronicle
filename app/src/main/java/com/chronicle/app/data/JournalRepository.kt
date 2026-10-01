package com.chronicle.app.data

import com.chronicle.app.data.db.EntryDao
import com.chronicle.app.data.db.EntryEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class JournalRepository(private val entryDao: EntryDao) {

    fun observeAll(): Flow<List<EntryEntity>> = entryDao.observeAll()

    fun observeDay(day: LocalDate): Flow<List<EntryEntity>> =
        entryDao.observeDay(day.toString())

    suspend fun allOnce(): List<EntryEntity> = entryDao.allOnce()

    suspend fun addText(text: String, now: LocalDateTime = LocalDateTime.now()): Long =
        entryDao.insert(
            EntryEntity(
                createdAt = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                day = now.toLocalDate().toString(),
                kind = KIND_TEXT,
                transcript = text.trim(),
            ),
        )

    suspend fun addVoice(
        transcript: String,
        audioPath: String?,
        durationMs: Long,
        now: LocalDateTime = LocalDateTime.now(),
    ): Long =
        entryDao.insert(
            EntryEntity(
                createdAt = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                day = now.toLocalDate().toString(),
                kind = KIND_VOICE,
                transcript = transcript.trim(),
                audioPath = audioPath,
                durationMs = durationMs,
            ),
        )

    suspend fun delete(id: Long) = entryDao.deleteById(id)

    companion object {
        const val KIND_VOICE = "voice"
        const val KIND_TEXT = "text"
    }
}
