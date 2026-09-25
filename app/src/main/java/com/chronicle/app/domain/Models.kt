package com.chronicle.app.domain

import com.chronicle.app.data.db.EntryEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

enum class EntryKind { VOICE, TEXT }

data class JournalEntry(
    val id: Long,
    val createdAt: LocalDateTime,
    val day: LocalDate,
    val kind: EntryKind,
    val transcript: String,
    val audioPath: String?,
    val durationMs: Long,
)

fun EntryEntity.toDomain(): JournalEntry {
    val instant = Instant.ofEpochMilli(createdAt)
    val zone = ZoneId.systemDefault()
    return JournalEntry(
        id = id,
        createdAt = LocalDateTime.ofInstant(instant, zone),
        day = LocalDate.parse(day),
        kind = if (kind == "voice") EntryKind.VOICE else EntryKind.TEXT,
        transcript = transcript,
        audioPath = audioPath,
        durationMs = durationMs,
    )
}
