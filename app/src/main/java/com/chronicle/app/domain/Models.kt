package com.chronicle.app.domain

import com.chronicle.app.data.db.EntryEntity
import com.chronicle.app.data.db.TRANSCRIPT_FAILED
import com.chronicle.app.data.db.TRANSCRIPT_NOT_REQUESTED
import com.chronicle.app.data.db.TRANSCRIPT_PENDING
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

enum class EntryKind { VOICE, TEXT }

/** How an entry's transcript was produced — mirrors the entries table values. */
enum class TranscriptStatus { PENDING, READY, FAILED, NOT_REQUESTED }

data class JournalEntry(
    val id: Long,
    val createdAt: LocalDateTime,
    val day: LocalDate,
    val kind: EntryKind,
    val transcript: String,
    val audioPath: String?,
    val durationMs: Long,
    val transcriptStatus: TranscriptStatus = TranscriptStatus.READY,
)

fun EntryEntity.toDomain(): JournalEntry {
    val instant = Instant.ofEpochMilli(createdAt)
    // Render in the writer's zone when stored; legacy rows (zoneId null)
    // fall back to the system zone — documented, best-effort.
    val zone = zoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        ?: ZoneId.systemDefault()
    return JournalEntry(
        id = id,
        createdAt = LocalDateTime.ofInstant(instant, zone),
        day = LocalDate.parse(day),
        kind = if (kind == "voice") EntryKind.VOICE else EntryKind.TEXT,
        transcript = transcript,
        audioPath = audioPath,
        durationMs = durationMs,
        transcriptStatus = when (transcriptStatus) {
            TRANSCRIPT_PENDING -> TranscriptStatus.PENDING
            TRANSCRIPT_FAILED -> TranscriptStatus.FAILED
            TRANSCRIPT_NOT_REQUESTED -> TranscriptStatus.NOT_REQUESTED
            else -> TranscriptStatus.READY
        },
    )
}
