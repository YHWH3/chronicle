package com.chronicle.app.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

data class ExtractedTask(
    val title: String,
    val remindAt: LocalDateTime?,
)

/**
 * Deterministic, on-device extraction of tasks from spoken or written prose.
 * Recognises intent phrases ("remind me to …", "I need to …", "todo …") and
 * natural time phrases ("tomorrow at 5", "in 2 hours", "on Friday", "tonight")
 * — no network, no model, fully predictable.
 */
object TaskExtractor {

    private val INTENT_PATTERNS = listOf(
        Regex("""\bremind me to\b\s*(.+)""", RegexOption.IGNORE_CASE),
        Regex("""\bremind me\b\s*(.+)""", RegexOption.IGNORE_CASE),
        Regex("""\bdon't let me forget(?:\s+to)?\b\s*(.+)""", RegexOption.IGNORE_CASE),
        Regex("""\bdo not let me forget(?:\s+to)?\b\s*(.+)""", RegexOption.IGNORE_CASE),
        Regex("""\bremember to\b\s*(.+)""", RegexOption.IGNORE_CASE),
        Regex("""\bi (?:really )?(?:need|have|'ve got|am supposed|'m supposed) to\b\s*(.+)""", RegexOption.IGNORE_CASE),
        Regex("""\bi should\b\s*(.+)""", RegexOption.IGNORE_CASE),
        Regex("""\bi must\b\s*(.+)""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:to-?do|task|note to self)\s*[:\-–]\s*(.+)""", RegexOption.IGNORE_CASE),
        Regex("""\badd\b\s+(.+?)\s+\bto (?:my )?(?:task|todo|to-?do) list\b""", RegexOption.IGNORE_CASE),
    )

    private val IN_DURATION = Regex(
        """\bin\s+(\d+)\s+(minutes?|hours?|days?|weeks?)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val AT_TIME = Regex(
        """\bat\s+(\d{1,2})(?::(\d{2}))?\s*(a\.?m\.?|p\.?m\.?|am|pm)?\b""",
        RegexOption.IGNORE_CASE,
    )

    private val NOON = Regex("""\bat\s+noon\b|\bnoon\b""", RegexOption.IGNORE_CASE)
    private val MIDNIGHT = Regex("""\bat\s+midnight\b|\bmidnight\b""", RegexOption.IGNORE_CASE)

    private val WEEKDAYS = mapOf(
        "monday" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY,
    )

    private val ON_WEEKDAY = Regex(
        """\bon\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""",
        RegexOption.IGNORE_CASE,
    )

    private data class DayPhrase(val days: Long, val impliedTime: LocalTime?)

    /** Ordered longest-first so "tomorrow morning" wins over bare "tomorrow". */
    private val DAY_PHRASES: List<Pair<Regex, DayPhrase>> = listOf(
        Regex("""\bthe day after tomorrow\b""", RegexOption.IGNORE_CASE) to DayPhrase(2, LocalTime.of(9, 0)),
        Regex("""\bnext week\b""", RegexOption.IGNORE_CASE) to DayPhrase(7, LocalTime.of(9, 0)),
        Regex("""\btomorrow (?:morning|early)\b""", RegexOption.IGNORE_CASE) to DayPhrase(1, LocalTime.of(9, 0)),
        Regex("""\btomorrow afternoon\b""", RegexOption.IGNORE_CASE) to DayPhrase(1, LocalTime.of(15, 0)),
        Regex("""\btomorrow (?:evening|night)\b""", RegexOption.IGNORE_CASE) to DayPhrase(1, LocalTime.of(20, 0)),
        Regex("""\btomorrow\b""", RegexOption.IGNORE_CASE) to DayPhrase(1, LocalTime.of(9, 0)),
        Regex("""\btonight\b""", RegexOption.IGNORE_CASE) to DayPhrase(0, LocalTime.of(20, 0)),
        Regex("""\bthis evening\b""", RegexOption.IGNORE_CASE) to DayPhrase(0, LocalTime.of(19, 0)),
        Regex("""\bthis afternoon\b""", RegexOption.IGNORE_CASE) to DayPhrase(0, LocalTime.of(15, 0)),
        Regex("""\bthis morning\b""", RegexOption.IGNORE_CASE) to DayPhrase(0, LocalTime.of(9, 0)),
        Regex("""\btoday\b""", RegexOption.IGNORE_CASE) to DayPhrase(0, null),
        Regex("""\b(?:end of (?:the )?(?:working )?day|eod|close of business)\b""", RegexOption.IGNORE_CASE) to DayPhrase(0, LocalTime.of(17, 0)),
    )

    private val SENTENCE_SPLIT = Regex("""[.!?\n;]+""")

    /**
     * Rewrites spoken punctuation into forms the sentence split can't destroy:
     * - "a.m."/"p.m." (and "a.m" without the trailing dot) → "am"/"pm"
     * - decimal times "5.30pm"/"5.30 p.m." → "5:30 pm" — only when a meridian
     *   follows, so plain decimals ("2.5 litres") are left alone.
     * Applied to the raw transcript BEFORE it is split on `[.!?\n;]+`.
     */
    private fun normalizeTranscript(raw: String): String {
        var t = raw
        t = t.replace(Regex("""\b([aApP])\s*\.\s*[mM]\s*\.?"""), "$1m")
        t = t.replace(
            Regex("""\b(\d{1,2})\.(\d{2})\s*([aApP][mM])\b"""),
            "$1:$2 $3",
        )
        return t
    }

    fun extract(transcript: String, now: LocalDateTime): List<ExtractedTask> {
        val sentences = normalizeTranscript(transcript)
            .split(SENTENCE_SPLIT)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val results = LinkedHashMap<String, ExtractedTask>()

        for (sentence in sentences) {
            val intent = INTENT_PATTERNS.firstNotNullOfOrNull { it.find(sentence) }
                ?: continue

            var fragment = intent.groupValues[1]
            val time = resolveTime(sentence, now)

            // Remove every matched time phrase from the captured title.
            for (matched in time.matchedLiterals.sortedByDescending { it.length }) {
                val idx = fragment.lowercase().indexOf(matched.lowercase())
                if (idx >= 0) {
                    fragment = fragment.substring(0, idx) + fragment.substring(idx + matched.length)
                }
            }

            val title = polishTitle(fragment)
            if (title.length >= 3) {
                val key = title.lowercase()
                if (!results.containsKey(key) || results[key]?.remindAt == null) {
                    results[key] = ExtractedTask(title = title, remindAt = time.remindAt)
                }
            }
        }
        return results.values.toList()
    }

    private data class TimeResult(
        val remindAt: LocalDateTime?,
        /** Literal matched substrings, for removal from the title. */
        val matchedLiterals: List<String>,
    )

    private fun resolveTime(sentence: String, now: LocalDateTime): TimeResult {
        val literals = mutableListOf<String>()

        // Relative "in N units" wins over everything.
        IN_DURATION.find(sentence)?.let { m ->
            val amount = m.groupValues[1].toLongOrNull() ?: 0L
            val unit = m.groupValues[2].lowercase()
            val result = when {
                unit.startsWith("minute") -> now.plusMinutes(amount)
                unit.startsWith("hour") -> now.plusHours(amount)
                unit.startsWith("day") -> now.plusDays(amount)
                else -> now.plusWeeks(amount)
            }
            return TimeResult(result, listOf(m.value))
        }

        var dayShift = 0L
        var impliedTime: LocalTime? = null

        val weekdayMatch = ON_WEEKDAY.find(sentence)
        if (weekdayMatch != null) {
            val dow = WEEKDAYS[weekdayMatch.groupValues[1].lowercase()]!!
            val today = now.toLocalDate()
            dayShift = today.with(TemporalAdjusters.next(dow)).toEpochDay() - today.toEpochDay()
            impliedTime = LocalTime.of(9, 0)
            literals.add(weekdayMatch.value)
        } else {
            for ((pattern, phrase) in DAY_PHRASES) {
                val m = pattern.find(sentence) ?: continue
                dayShift = phrase.days
                impliedTime = phrase.impliedTime
                literals.add(m.value)
                break
            }
        }

        var explicitTime: LocalTime? = null
        val atMatch = AT_TIME.find(sentence)
        if (atMatch != null) {
            explicitTime = parseClock(atMatch.groupValues[1], atMatch.groupValues[2], atMatch.groupValues[3])
            if (explicitTime != null) literals.add(atMatch.value)
        }
        if (explicitTime == null) {
            NOON.find(sentence)?.let {
                explicitTime = LocalTime.NOON
                literals.add(it.value)
            }
        }
        if (explicitTime == null) {
            MIDNIGHT.find(sentence)?.let {
                explicitTime = LocalTime.MIDNIGHT
                literals.add(it.value)
            }
        }

        val time = explicitTime ?: impliedTime
        if (time == null) return TimeResult(null, literals)

        var result = LocalDateTime.of(now.toLocalDate().plusDays(dayShift), time)
        // Same-day times that have already passed roll to tomorrow.
        if (dayShift == 0L && result <= now) result = result.plusDays(1)

        return TimeResult(result, literals)
    }

    private fun parseClock(hourRaw: String, minuteRaw: String, meridianRaw: String): LocalTime? {
        val hour = hourRaw.toIntOrNull() ?: return null
        val minute = minuteRaw.toIntOrNull() ?: 0
        val meridian = meridianRaw.lowercase().replace(".", "")
        val h24 = when {
            meridian.startsWith("p") -> if (hour == 12) 12 else hour + 12
            meridian.startsWith("a") -> if (hour == 12) 0 else hour
            hour in 1..7 -> hour + 12 // "at 5" without a.m./p.m. reads as evening
            else -> hour
        }
        if (minute > 59 || h24 > 23) return null
        return LocalTime.of(h24, minute)
    }

    /**
     * Resolves a bare time expression ("tomorrow 5pm", "next week") — used by
     * mind backends when a model returns natural language instead of ISO.
     */
    fun resolveTimeText(text: String, now: LocalDateTime): LocalDateTime? =
        resolveTime(text, now).remindAt

    private fun polishTitle(raw: String): String {
        var t = raw.replace(Regex("""\s+"""), " ").trim()
        // Drop dangling connectives left behind by removed time phrases.
        t = t.replace(Regex("""\s+(at|on|in|by|before|for|and)$""", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("""^to\s+""", RegexOption.IGNORE_CASE), "")
        t = t.trim().trimEnd(',', '.')
        if (t.isEmpty()) return t
        return t.replaceFirstChar { it.uppercase() }
    }
}
