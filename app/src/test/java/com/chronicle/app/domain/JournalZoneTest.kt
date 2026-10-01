package com.chronicle.app.domain

import com.chronicle.app.data.db.EntryEntity
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * BE-04 — an entry's time-of-day renders in the zone it was WRITTEN in, not
 * wherever the phone happens to be when it's read. [EntryEntity.zoneId] is
 * captured at insert; legacy rows (null) fall back to the system zone.
 */
class JournalZoneTest {

    private fun entity(createdAt: Long, zoneId: String?) = EntryEntity(
        id = 1,
        createdAt = createdAt,
        day = "2026-01-10",
        kind = "text",
        transcript = "a note",
        zoneId = zoneId,
    )

    @Test
    fun `time-of-day renders in the stored writer zone`() {
        // 09:30 in New York — 14:30 UTC.
        val ny = ZoneId.of("America/New_York")
        val epoch = LocalDateTime.of(2026, 1, 10, 9, 30)
            .atZone(ny).toInstant().toEpochMilli()
        val entry = entity(epoch, ny.id).toDomain()

        // Whatever zone the test machine runs in, the render is the writer's.
        assertEquals(9, entry.createdAt.hour)
        assertEquals(30, entry.createdAt.minute)
    }

    @Test
    fun `a stored zone survives a system-zone change`() {
        // Written at 18:00 in Tokyo; read while the system sits in London —
        // the row still says 18:00, not 09:00.
        val tokyo = ZoneId.of("Asia/Tokyo")
        val epoch = LocalDateTime.of(2026, 1, 10, 18, 0)
            .atZone(tokyo).toInstant().toEpochMilli()
        val entry = entity(epoch, tokyo.id).toDomain()
        assertEquals(18, entry.createdAt.hour)
    }

    @Test
    fun `legacy rows without a zone fall back to the system zone`() {
        val instant = Instant.ofEpochMilli(1_000_000_000L)
        val expected = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
        val entry = entity(instant.toEpochMilli(), zoneId = null).toDomain()
        assertEquals(expected, entry.createdAt)
    }

    @Test
    fun `an unparseable stored zone falls back to the system zone`() {
        val instant = Instant.ofEpochMilli(1_000_000_000L)
        val expected = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
        val entry = entity(instant.toEpochMilli(), zoneId = "Bogus/Zone").toDomain()
        assertEquals(expected, entry.createdAt)
    }
}
