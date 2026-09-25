package com.chronicle.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class StoryComposerTest {

    private fun entry(day: LocalDate, hour: Int, text: String): JournalEntry =
        JournalEntry(
            id = 0,
            createdAt = LocalDateTime.of(day, java.time.LocalTime.of(hour, 0)),
            day = day,
            kind = EntryKind.TEXT,
            transcript = text,
            audioPath = null,
            durationMs = 0,
        )

    @Test
    fun `empty journal gives empty story`() {
        val story = StoryComposer.compose(emptyList(), LocalDate.of(2026, 9, 25))
        assertTrue(story.chapters.isEmpty())
        assertEquals(0, story.totalEntries)
    }

    @Test
    fun `entries group into chapters by week`() {
        // Week of Mon 21 Sep 2026 and week of Mon 28 Sep 2026.
        val entries = listOf(
            entry(LocalDate.of(2026, 9, 23), 21, "a quiet evening"),
            entry(LocalDate.of(2026, 9, 25), 9, "morning light"),
            entry(LocalDate.of(2026, 9, 30), 18, "closing the month"),
        )
        val story = StoryComposer.compose(entries, LocalDate.of(2026, 9, 30))
        assertEquals(2, story.chapters.size)
        assertEquals("Chapter I", story.chapters[0].title)
        assertEquals("Chapter II", story.chapters[1].title)
        assertEquals(2, story.chapters[0].entryCount)
        assertEquals(1, story.chapters[1].entryCount)
    }

    @Test
    fun `same day entries stitch with later`() {
        val entries = listOf(
            entry(LocalDate.of(2026, 9, 25), 8, "Woke early"),
            entry(LocalDate.of(2026, 9, 25), 20, "Watched the rain"),
        )
        val story = StoryComposer.compose(entries, LocalDate.of(2026, 9, 25))
        val paragraph = story.chapters.single().paragraphs.single()
        assertTrue(paragraph.contains("Later, watched the rain."))
        assertTrue(paragraph.startsWith("On Friday, the twenty-fifth of September,"))
    }

    @Test
    fun `markdown renders chapters`() {
        val entries = listOf(entry(LocalDate.of(2026, 9, 25), 9, "a start"))
        val md = StoryComposer.compose(entries, LocalDate.of(2026, 9, 25)).toMarkdown()
        assertTrue(md.contains("# The Story So Far"))
        assertTrue(md.contains("## Chapter I"))
        assertTrue(md.contains("1 days kept · 1 entries"))
    }

    @Test
    fun `roman numerals`() {
        assertEquals("I", StoryComposer.roman(1))
        assertEquals("IV", StoryComposer.roman(4))
        assertEquals("IX", StoryComposer.roman(9))
        assertEquals("XXVII", StoryComposer.roman(27))
    }

    @Test
    fun `ordinal words`() {
        assertEquals("twenty-fifth", StoryComposer.ordinalWords(25))
        assertEquals("first", StoryComposer.ordinalWords(1))
        assertEquals("thirtieth", StoryComposer.ordinalWords(30))
        assertEquals("twentieth", StoryComposer.ordinalWords(20))
    }
}
