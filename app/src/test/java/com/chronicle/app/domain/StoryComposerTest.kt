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

    @Test
    fun `blank transcript becomes kept in silence`() {
        val entries = listOf(entry(LocalDate.of(2026, 9, 25), 9, "   "))
        val story = StoryComposer.compose(entries, LocalDate.of(2026, 9, 25))
        assertTrue(story.chapters.single().paragraphs.single().endsWith("the day was kept in silence."))
    }

    @Test
    fun `transcript punctuation is not doubled`() {
        val entries = listOf(entry(LocalDate.of(2026, 9, 25), 9, "what a day!"))
        val story = StoryComposer.compose(entries, LocalDate.of(2026, 9, 25))
        assertTrue(story.chapters.single().paragraphs.single().contains("What a day!"))
        assertTrue(!story.chapters.single().paragraphs.single().contains("!."))
    }

    @Test
    fun `current week chapter is ongoing and past week is not`() {
        val entries = listOf(
            entry(LocalDate.of(2026, 9, 18), 9, "last week"),
            entry(LocalDate.of(2026, 9, 25), 9, "this week"),
        )
        val story = StoryComposer.compose(entries, LocalDate.of(2026, 9, 25))
        assertTrue(!story.chapters[0].ongoing)
        assertTrue(story.chapters[1].ongoing)
    }

    @Test
    fun `totals count distinct days and words`() {
        val entries = listOf(
            entry(LocalDate.of(2026, 9, 24), 8, "two words"),
            entry(LocalDate.of(2026, 9, 24), 20, "three words here"),
            entry(LocalDate.of(2026, 9, 25), 9, "one"),
        )
        val story = StoryComposer.compose(entries, LocalDate.of(2026, 9, 25))
        assertEquals(2, story.totalDays)
        assertEquals(3, story.totalEntries)
        assertEquals(6, story.totalWords)
        assertEquals(LocalDate.of(2026, 9, 24), story.firstDay)
        assertEquals(LocalDate.of(2026, 9, 25), story.lastDay)
        assertEquals(1, story.readingMinutes)
    }

    @Test
    fun `unordered input sorts by time`() {
        val entries = listOf(
            entry(LocalDate.of(2026, 9, 25), 20, "second"),
            entry(LocalDate.of(2026, 9, 25), 8, "first"),
        )
        val story = StoryComposer.compose(entries, LocalDate.of(2026, 9, 25))
        val paragraph = story.chapters.single().paragraphs.single()
        assertTrue(paragraph.indexOf("First.") < paragraph.indexOf("Later, second."))
    }

    @Test
    fun `subtitle names the week start`() {
        val entries = listOf(entry(LocalDate.of(2026, 9, 25), 9, "a day"))
        val story = StoryComposer.compose(entries, LocalDate.of(2026, 9, 25))
        assertEquals("The week of the twenty-first of September", story.chapters.single().subtitle)
    }
}
