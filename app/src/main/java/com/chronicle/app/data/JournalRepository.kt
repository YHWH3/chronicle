package com.chronicle.app.data

import com.chronicle.app.data.db.EntryDao
import com.chronicle.app.data.db.EntryEntity
import com.chronicle.app.data.db.TRANSCRIPT_READY
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class JournalRepository(private val entryDao: EntryDao) {

    fun observeAll(): Flow<List<EntryEntity>> = entryDao.observeAll()

    fun observeDay(day: LocalDate): Flow<List<EntryEntity>> =
        entryDao.observeDay(day.toString())

    suspend fun allOnce(): List<EntryEntity> = entryDao.allOnce()

    suspend fun byId(id: Long): EntryEntity? = entryDao.byId(id)

    suspend fun addText(
        text: String,
        transcriptStatus: String = TRANSCRIPT_READY,
        now: LocalDateTime = LocalDateTime.now(),
        zoneId: String? = ZoneId.systemDefault().id,
    ): Long =
        entryDao.insert(
            EntryEntity(
                createdAt = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                day = now.toLocalDate().toString(),
                kind = KIND_TEXT,
                transcript = text.trim(),
                transcriptStatus = transcriptStatus,
                zoneId = zoneId,
            ),
        )

    suspend fun addVoice(
        transcript: String,
        audioPath: String?,
        durationMs: Long,
        transcriptStatus: String = TRANSCRIPT_READY,
        now: LocalDateTime = LocalDateTime.now(),
        zoneId: String? = ZoneId.systemDefault().id,
    ): Long =
        entryDao.insert(
            EntryEntity(
                createdAt = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                day = now.toLocalDate().toString(),
                kind = KIND_VOICE,
                transcript = transcript.trim(),
                audioPath = audioPath,
                durationMs = durationMs,
                transcriptStatus = transcriptStatus,
                zoneId = zoneId,
            ),
        )

    /** Replaces a transcript after a (re-)transcription attempt. */
    suspend fun updateTranscript(id: Long, transcript: String, status: String) =
        entryDao.updateTranscript(id, transcript.trim(), status)

    suspend fun updateTranscriptStatus(id: Long, status: String) =
        entryDao.updateTranscriptStatus(id, status)

    suspend fun delete(id: Long) = entryDao.deleteById(id)

    /**
     * Undo-delete: re-inserts a removed row with its original id and fields
     * intact (the caller held onto the entity). The audio file is untouched
     * by delete, so a restored take still points at real audio.
     */
    suspend fun restore(entry: EntryEntity) = entryDao.insertPreservingId(entry)

    companion object {
        const val KIND_VOICE = "voice"
        const val KIND_TEXT = "text"
    }
}
