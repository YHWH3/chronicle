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
            entry(LocalDate.of(2026, 9, 25), 9, "made coffee"),
        )
        val story = StoryComposer.compose(entries, LocalDate.of(2026, 9, 25))
        val paragraph = story.chapters.single().paragraphs.single()
        assertTrue(paragraph.contains("Later, made coffee."))
        assertTrue(paragraph.startsWith("On Friday, the twenty-fifth of September,"))
    }

    @Test
    fun `long evening gap earns a time-aware connective`() {
        val entries = listOf(
            entry(LocalDate.of(2026, 9, 25), 8, "Woke early"),
            entry(LocalDate.of(2026, 9, 25), 20, "Watched the rain"),
        )
        val story = StoryComposer.compose(entries, LocalDate.of(2026, 9, 25))
        // Capitalized word stays capitalized — it may be a proper noun.
        assertTrue(
            story.chapters.single().paragraphs.single()
                .contains("That evening, Watched the rain."),
        )
    }

    @Test
    fun `long daytime gap uses later that day`() {
        val entries = listOf(
            entry(LocalDate.of(2026, 9, 25), 8, "a dawn walk"),
            entry(LocalDate.of(2026, 9, 25), 14, "lunch with Mara"),
        )
        val story = StoryComposer.compose(entries, LocalDate.of(2026, 9, 25))
        assertTrue(
            story.chapters.single().paragraphs.single()
                .contains("Later that day, lunch with Mara."),
        )
    }

    @Test
    fun `back-to-back entries need no connective`() {
        val entries = listOf(
            entry(LocalDate.of(2026, 9, 25), 9, "woke early"),
            entry(LocalDate.of(2026, 9, 25), 9, "coffee was good"),
        )
        val story = StoryComposer.compose(entries, LocalDate.of(2026, 9, 25))
        assertTrue(
            story.chapters.single().paragraphs.single()
                .contains("Woke early. Coffee was good."),
        )
    }

    @Test
    fun `I and I-forms keep their capital after a connective`() {
        val entries = listOf(
            entry(LocalDate.of(2026, 9, 25), 8, "a slow morning"),
            entry(LocalDate.of(2026, 9, 25), 9, "I went for a walk"),
            entry(LocalDate.of(2026, 9, 25), 10, "I'm glad I did"),
        )
        val paragraph = StoryComposer.compose(entries, LocalDate.of(2026, 9, 25))
            .chapters.single().paragraphs.single()
        assertTrue(paragraph.contains("Later, I went for a walk."))
        assertTrue(paragraph.contains("Later, I'm glad I did."))
        assertTrue(!paragraph.contains(" i "))
    }

    @Test
    fun `proper nouns keep their capital after a connective`() {
        val entries = listOf(
            entry(LocalDate.of(2026, 9, 25), 8, "quiet start"),
            entry(LocalDate.of(2026, 9, 25), 9, "Sam came by"),
            entry(LocalDate.of(2026, 9, 25), 10, "Google announced layoffs"),
        )
        val paragraph = StoryComposer.compose(entries, LocalDate.of(2026, 9, 25))
            .chapters.single().paragraphs.single()
        assertTrue(paragraph.contains("Later, Sam came by."))
        assertTrue(paragraph.contains("Later, Google announced layoffs."))
    }

    @Test
    fun `safe closed-class words lowercase after a connective`() {
        val entries = listOf(
            entry(LocalDate.of(2026, 9, 25), 8, "a slow morning"),
            entry(LocalDate.of(2026, 9, 25), 9, "The sun came out"),
            entry(LocalDate.of(2026, 9, 25), 10, "It stayed all day"),
        )
        val paragraph = StoryComposer.compose(entries, LocalDate.of(2026, 9, 25))
            .chapters.single().paragraphs.single()
        assertTrue(paragraph.contains("Later, the sun came out."))
        assertTrue(paragraph.contains("Later, it stayed all day."))
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
    fun `markdown escapes hostile transcript content`() {
        val hostile = "a quiet start\n" +
            "# not a heading\n" +
            "*not italic* and _not em_ and `not code`\n" +
            "[link](https://evil.example)\n" +
            "> not a quote\n" +
            "- not a list\n" +
            "1. not ordered\n" +
            "a \\ backslash"
        val entries = listOf(entry(LocalDate.of(2026, 9, 25), 9, hostile))
        val md = StoryComposer.compose(entries, LocalDate.of(2026, 9, 25)).toMarkdown()
        // Template stays raw…
        assertTrue(md.startsWith("# The Story So Far"))
        assertTrue(md.contains("\n## Chapter I"))
        // …while entry text is neutralized.
        assertTrue(md.contains("\\# not a heading"))
        assertTrue(md.contains("\\*not italic\\* and \\_not em\\_ and \\`not code\\`"))
        assertTrue(md.contains("\\[link\\](https://evil.example)"))
        assertTrue(md.contains("\\> not a quote"))
        assertTrue(md.contains("\\- not a list"))
        assertTrue(md.contains("1\\. not ordered"))
        assertTrue(md.contains("a \\\\ backslash"))
        // No line in the paragraph body may open as a real heading or list.
        val paragraphLines = md.lines().filter { it.startsWith("On Friday") }
        assertTrue(paragraphLines.isNotEmpty())
    }

    @Test
    fun `markdown leaves plain prose untouched`() {
        val entries = listOf(entry(LocalDate.of(2026, 9, 25), 9, "a walk, some coffee."))
        val md = StoryComposer.compose(entries, LocalDate.of(2026, 9, 25)).toMarkdown()
        assertTrue(md.contains("A walk, some coffee."))
        assertTrue(!md.contains("\\"))
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
    fun `terminal punctuation inside a closing quote is not doubled`() {
        val entries = listOf(
            entry(LocalDate.of(2026, 9, 25), 9, "she said \"go.\""),
            entry(LocalDate.of(2026, 9, 25), 9, "it ended (finally!)"),
            entry(LocalDate.of(2026, 9, 25), 9, "read the note [page 2?]"),
        )
        val paragraph = StoryComposer.compose(entries, LocalDate.of(2026, 9, 25))
            .chapters.single().paragraphs.single()
        assertTrue(paragraph.contains("\"go.\""))
        assertTrue(paragraph.contains("(finally!)"))
        assertTrue(paragraph.contains("[page 2?]"))
        assertTrue(!paragraph.contains("\"."))
        assertTrue(!paragraph.contains(")."))
        assertTrue(!paragraph.contains("]."))
    }

    @Test
    fun `leading quote capitalizes the quoted word not the quote`() {
        val entries = listOf(entry(LocalDate.of(2026, 9, 25), 9, "\"the end is near\" he said"))
        val paragraph = StoryComposer.compose(entries, LocalDate.of(2026, 9, 25))
            .chapters.single().paragraphs.single()
        assertTrue(paragraph.contains("\"The end is near\" he said."))
    }

    @Test
    fun `markdown leading chars survive sentence casing`() {
        val entries = listOf(entry(LocalDate.of(2026, 9, 25), 9, "# a heading"))
        val paragraph = StoryComposer.compose(entries, LocalDate.of(2026, 9, 25))
            .chapters.single().paragraphs.single()
        assertTrue(paragraph.contains("# A heading."))
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
        assertTrue(paragraph.indexOf("First.") < paragraph.indexOf("That evening, second."))
    }

    @Test
    fun `subtitle names the week start`() {
        val entries = listOf(entry(LocalDate.of(2026, 9, 25), 9, "a day"))
        val story = StoryComposer.compose(entries, LocalDate.of(2026, 9, 25))
        assertEquals("The week of the twenty-first of September", story.chapters.single().subtitle)
    }
}
