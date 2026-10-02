package com.chronicle.app.domain

import java.time.DayOfWeek
import java.time.Duration
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
                appendLine(escapeMarkdown(p))
                appendLine()
            }
        }
        appendLine("---")
        appendLine("$totalDays days kept · $totalEntries entries · $totalWords words")
    }

    /**
     * Escapes user-derived text so it cannot break the document's markdown
     * structure: emphasis, code, and link characters are backslash-escaped
     * anywhere, and block markers (`#`, `>`, `-`, `+`, ordered-list `1.`)
     * are escaped at line starts. Only applied to entry text — template
     * headings and rules are emitted raw.
     */
    private fun escapeMarkdown(text: String): String {
        val sb = StringBuilder(text.length + 8)
        var lineStart = true
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\n' -> {
                    sb.append(c)
                    lineStart = true
                    i++
                }
                lineStart && c.isDigit() -> {
                    // Ordered-list marker like "1." or "12)" — escape the dot.
                    var j = i
                    while (j < text.length && text[j].isDigit()) j++
                    if (j < text.length && (text[j] == '.' || text[j] == ')') &&
                        (j + 1 == text.length || text[j + 1] == ' ')
                    ) {
                        sb.append(text, i, j).append('\\').append(text[j])
                        i = j + 1
                    } else {
                        sb.append(c)
                        i++
                    }
                    lineStart = false
                }
                lineStart && (c == '#' || c == '>' || c == '-' || c == '+') -> {
                    sb.append('\\').append(c)
                    lineStart = false
                    i++
                }
                c == '\\' || c == '*' || c == '_' || c == '`' ||
                    c == '[' || c == ']' -> {
                    sb.append('\\').append(c)
                    lineStart = false
                    i++
                }
                else -> {
                    sb.append(c)
                    if (!c.isWhitespace()) lineStart = false
                    i++
                }
            }
        }
        return sb.toString()
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

        val cleaned = entries
            .mapNotNull { entry ->
                val text = entry.transcript.trim()
                if (text.isEmpty()) null else entry to ensureSentence(text)
            }

        val sentences = cleaned.mapIndexed { index, (entry, clean) ->
            if (index == 0) {
                clean
            } else {
                when (val connective = connectiveAfter(cleaned[index - 1].first, entry)) {
                    null -> clean
                    else -> "$connective ${decapitalizeForConnective(entry.transcript, clean)}"
                }
            }
        }

        return if (sentences.isEmpty()) {
            "$opener the day was kept in silence."
        } else {
            "$opener ${sentences.joinToString(" ")}"
        }
    }

    /**
     * Picks a connective for a later same-day entry, or null when the entries
     * are close enough that a bare sentence reads better.
     * - >3h gap: a time-aware beat — "That evening," / "Later that day," /
     *   "Earlier," when timestamps went backwards inside the day group.
     * - ≥45m: a light "Later,".
     * - below that: no connective.
     */
    private fun connectiveAfter(prev: JournalEntry, current: JournalEntry): String? {
        val gapMinutes = Duration.between(prev.createdAt, current.createdAt)
            .toMinutes().coerceAtLeast(0)
        if (gapMinutes > LONG_GAP_MINUTES) {
            val hour = current.createdAt.hour
            return when {
                hour < prev.createdAt.hour -> "Earlier,"
                hour >= EVENING_HOUR -> "That evening,"
                else -> "Later that day,"
            }
        }
        return if (gapMinutes >= SHORT_GAP_MINUTES) "Later," else null
    }

    /**
     * Lowercases the first letter of [clean] for use after a connective — but
     * only when safe. The original transcript decides: a word the writer typed
     * lowercase may be restored to lowercase, and closed-class words
     * ("the", "and", "it"…) may be lowered even when capitalized. Capitalized
     * words outside the safe list — proper nouns like "Sam" or "Google", and
     * every I-form — keep their case.
     */
    private fun decapitalizeForConnective(original: String, clean: String): String {
        val word = firstWord(original)
        if (word.isEmpty()) return clean
        // Never decapitalize I, I'm, I've, I'd, I'll — straight or curly quote.
        if (word.equals("i", ignoreCase = true) ||
            word.startsWith("i'", ignoreCase = true) ||
            word.startsWith("i’", ignoreCase = true)
        ) {
            return clean
        }
        val mayLower = word.first().isLowerCase() ||
            word.lowercase(Locale.ROOT) in CONNECTIVE_LOWERCASE_OK
        if (!mayLower) return clean
        // Lower the first letter, skipping any leading quote/bracket.
        val idx = clean.indexOfFirst { it.isLetter() }
        return if (idx < 0) {
            clean
        } else {
            clean.substring(0, idx) + clean[idx].lowercaseChar() + clean.substring(idx + 1)
        }
    }

    /** The first run of letters (apostrophes allowed inside), past any opener. */
    private fun firstWord(text: String): String {
        val t = text.trimStart()
        val start = t.indexOfFirst { it.isLetter() }
        if (start < 0) return ""
        var end = start + 1
        while (end < t.length &&
            (t[end].isLetter() || t[end] == '\'' || t[end] == '’')
        ) {
            end++
        }
        return t.substring(start, end)
    }

    private fun ensureSentence(text: String): String {
        var t = text.trim()
        // Capitalize the first letter — inside a leading quote/bracket too,
        // so `"hello'` becomes `"Hello'` rather than uppercasing the quote.
        val first = t.indexOfFirst { it.isLetter() }
        if (first >= 0) {
            t = t.substring(0, first) + t[first].uppercaseChar() + t.substring(first + 1)
        }
        // Append a period unless the text already ends in terminal punctuation,
        // possibly followed by a closing quote/paren/bracket (`go."` is done).
        if (!endsTerminal(t)) t += "."
        return t
    }

    /** True when the last real char is `.`, `!`, `?` or `…`, past closers. */
    private fun endsTerminal(t: String): Boolean {
        var i = t.length - 1
        while (i >= 0 && t[i] in CLOSING_AFTER_TERMINAL) i--
        return i >= 0 && t[i] in TERMINAL_CHARS
    }

    private fun wordCount(text: String): Int =
        text.split(Regex("""\s+""")).count { it.isNotBlank() }

    /** Same-day gap that earns a time-aware connective instead of "Later,". */
    private const val LONG_GAP_MINUTES = 3L * 60
    /** Below this, consecutive entries are stitched without a connective. */
    private const val SHORT_GAP_MINUTES = 45L
    private const val EVENING_HOUR = 18

    private const val TERMINAL_CHARS = ".!?…"
    /** Closers that may follow terminal punctuation: quotes, parens, brackets. */
    private const val CLOSING_AFTER_TERMINAL = "\"'’”)]}"

    /**
     * Closed-class words safe to decapitalize after a connective even when the
     * writer capitalized them. Proper nouns and I-forms are never in here.
     */
    private val CONNECTIVE_LOWERCASE_OK = setOf(
        "a", "an", "the",
        "and", "but", "or", "so", "then",
        "it", "we", "they", "he", "she",
        "my", "our",
    )

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
