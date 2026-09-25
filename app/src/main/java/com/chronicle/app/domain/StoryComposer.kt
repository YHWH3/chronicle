package com.chronicle.app.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class StoryChapter(
    /** 1-based chapter number. */
    val number: Int,
    val title: String,
    /** e.g. "The week of the twenty-second of September". */
    val subtitle: String,
    /** Narrative paragraphs, one per day. */
    val paragraphs: List<String>,
    val entryCount: Int,
    val wordCount: Int,
    val ongoing: Boolean,
)

data class Story(
    val chapters: List<StoryChapter>,
    val totalDays: Int,
    val totalEntries: Int,
    val totalWords: Int,
    val firstDay: LocalDate?,
    val lastDay: LocalDate?,
) {
    /** A quiet estimate, ~200 words a minute. */
    val readingMinutes: Int get() = maxOf(1, (totalWords + 199) / 200)

    fun toMarkdown(): String = buildString {
        appendLine("# The Story So Far")
        appendLine()
        firstDay?.let { first ->
            appendLine("*${first.format(LONG_DATE)} — ${lastDay?.format(LONG_DATE)}*")
            appendLine()
        }
        for (chapter in chapters) {
            appendLine("## ${chapter.title} · ${chapter.subtitle}")
            appendLine()
            for (p in chapter.paragraphs) {
                appendLine(p)
                appendLine()
            }
        }
        appendLine("---")
        appendLine("$totalDays days kept · $totalEntries entries · $totalWords words")
    }

    private companion object {
        val LONG_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)
    }
}

/**
 * Collates journal entries into a continuous written narrative — "the story" —
 * grouped into chapters by ISO week. Fully deterministic and on-device: prose
 * comes from the entries themselves, stitched with restrained connectives.
 */
object StoryComposer {

    fun compose(entries: List<JournalEntry>, today: LocalDate): Story {
        if (entries.isEmpty()) {
            return Story(emptyList(), 0, 0, 0, null, null)
        }

        val sorted = entries.sortedBy { it.createdAt }
        val byWeek = sorted.groupBy { it.day.with(DayOfWeek.MONDAY) }.toSortedMap()

        val chapters = byWeek.entries.mapIndexed { idx, (weekStart, weekEntries) ->
            val byDay = weekEntries.groupBy { it.day }.toSortedMap()
            val paragraphs = byDay.map { (day, dayEntries) -> dayParagraph(day, dayEntries) }
            val words = weekEntries.sumOf { wordCount(it.transcript) }
            val ongoing = !weekStart.plusDays(6).isBefore(today)

            StoryChapter(
                number = idx + 1,
                title = "Chapter ${roman(idx + 1)}",
                subtitle = "The week of the ${ordinalWords(weekStart.dayOfMonth)} of " +
                    weekStart.format(DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH)),
                paragraphs = paragraphs,
                entryCount = weekEntries.size,
                wordCount = words,
                ongoing = ongoing,
            )
        }

        return Story(
            chapters = chapters,
            totalDays = sorted.map { it.day }.distinct().size,
            totalEntries = sorted.size,
            totalWords = sorted.sumOf { wordCount(it.transcript) },
            firstDay = sorted.first().day,
            lastDay = sorted.last().day,
        )
    }

    private fun dayParagraph(day: LocalDate, entries: List<JournalEntry>): String {
        val opener = "On ${day.format(DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH))}, " +
            "the ${ordinalWords(day.dayOfMonth)} of " +
            day.format(DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH)) + ","

        val sentences = entries
            .map { it.transcript.trim() }
            .filter { it.isNotEmpty() }
            .mapIndexed { index, text ->
                val clean = ensureSentence(text)
                when {
                    index == 0 -> clean
                    else -> "Later, ${clean.replaceFirstChar { it.lowercase() }}"
                }
            }

        return if (sentences.isEmpty()) {
            "$opener the day was kept in silence."
        } else {
            "$opener ${sentences.joinToString(" ")}"
        }
    }

    private fun ensureSentence(text: String): String {
        var t = text.trim()
        t = t.replaceFirstChar { it.uppercase() }
        if (!t.endsWith(".") && !t.endsWith("!") && !t.endsWith("?")) t += "."
        return t
    }

    private fun wordCount(text: String): Int =
        text.split(Regex("""\s+""")).count { it.isNotBlank() }

    private val ONES = listOf(
        "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth",
        "tenth", "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth",
        "seventeenth", "eighteenth", "nineteenth", "twentieth",
    )
    private val TENS = listOf("", "", "twenty", "thirty")

    fun ordinalWords(n: Int): String = when {
        n <= 20 -> ONES[n - 1]
        n % 10 == 0 -> TENS[n / 10].dropLast(1) + "ieth" // thirty → thirtieth
        else -> "${TENS[n / 10]}-${ONES[n % 10 - 1]}"
    }

    fun roman(n: Int): String {
        var num = n
        val sb = StringBuilder()
        val table = listOf(
            1000 to "M", 900 to "CM", 500 to "D", 400 to "CD",
            100 to "C", 90 to "XC", 50 to "L", 40 to "XL",
            10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I",
        )
        for ((value, glyph) in table) {
            while (num >= value) {
                sb.append(glyph)
                num -= value
            }
        }
        return sb.toString()
    }
}
