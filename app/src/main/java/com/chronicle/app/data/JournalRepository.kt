package com.chronicle.app.data

import com.chronicle.app.data.db.EntryDao
import com.chronicle.app.data.db.EntryEntity
import com.chronicle.app.data.db.TRANSCRIPT_READY
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class JournalRepository(
    private val entryDao: EntryDao,
    private val noteDetails: NoteDetailsStore? = null,
) {
    // Test/no-sidecar callers retain the same contract for the repository's lifetime.
    private val transientCleanups = kotlinx.coroutines.flow.MutableStateFlow<Map<Long, NoteCleanupRevision>>(emptyMap())
    private val cleanupLock = Mutex()
    val cleanupRevisions: Flow<Map<Long, NoteCleanupRevision>> get() = noteDetails?.cleanups ?: transientCleanups

    suspend fun applyCleanup(id: Long, original: String, status: String, cleaned: String): Boolean = cleanupLock.withLock {
        val revision = NoteCleanupRevision(original, cleaned, status)
        // Write the original first: process death after the Room update still leaves Undo available.
        val current = entryDao.byId(id) ?: return@withLock false
        if (current.transcript != original || current.transcriptStatus != status) return@withLock false
        if (noteDetails != null) noteDetails.saveCleanup(id, revision)
        else transientCleanups.value = transientCleanups.value + (id to revision)
        entryDao.replaceTranscriptIfUnchanged(id, original, status, cleaned)
    }

    suspend fun undoCleanup(id: Long): Boolean = cleanupLock.withLock {
        val revision = cleanupRevisions.first()[id] ?: return@withLock false
        if (!entryDao.replaceTranscriptIfUnchanged(id, revision.applied, revision.status, revision.original)) return@withLock false
        noteDetails?.clearCleanup(id)
        transientCleanups.value = transientCleanups.value - id
        true
    }
    val engineLabels: Flow<Map<Long, String>> get() = noteDetails?.labels ?: kotlinx.coroutines.flow.flowOf(emptyMap())

    suspend fun saveEngineLabel(id: Long, label: String) {
        noteDetails?.save(id, label)
    }

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
