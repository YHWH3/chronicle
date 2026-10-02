package com.chronicle.app.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

data class ExtractedTask(
    val title: String,
    val remindAt: LocalDateTime?,
    /**
     * True when the task clearly wants a reminder but the named slot already
     * passed for a same-day anchor ("today at 8", "tonight at 9" said too
     * late). No time was set because the intent is unclear — UI should offer
     * to pick one — rather than because none was mentioned.
     */
    val timeAmbiguous: Boolean = false,
    /**
     * True for hedged self-talk ("I should…", "I must…", "I have to…") —
     * surfaced as a suggestion the user can reject rather than a firm task.
     */
    val suggested: Boolean = false,
)

/**
 * Deterministic, on-device extraction of tasks from spoken or written prose.
 * Recognises intent phrases ("remind me to …", "I need to …", "todo …") and
 * natural time phrases ("tomorrow at 5", "in 2 hours", "on Friday", "tonight")
 * — no network, no model, fully predictable.
 */
object TaskExtractor {

    /**
     * One intent phrasing. [weak] marks hedged self-talk ("I should…",
     * "I have to…") that is surfaced as a suggestion rather than a firm task.
     */
    private data class Intent(val regex: Regex, val weak: Boolean = false)

    private val INTENTS = listOf(
        Intent(Regex("""\bremind me to\b\s*(.+)""", RegexOption.IGNORE_CASE)),
        Intent(Regex("""\bremind me\b\s*(.+)""", RegexOption.IGNORE_CASE)),
        Intent(Regex("""\bdon't let me forget(?:\s+to)?\b\s*(.+)""", RegexOption.IGNORE_CASE)),
        Intent(Regex("""\bdo not let me forget(?:\s+to)?\b\s*(.+)""", RegexOption.IGNORE_CASE)),
        Intent(Regex("""\bremember to\b\s*(.+)""", RegexOption.IGNORE_CASE)),
        Intent(Regex("""\bi (?:really )?need to\b\s*(.+)""", RegexOption.IGNORE_CASE)),
        Intent(Regex("""\bi've got to\b\s*(.+)""", RegexOption.IGNORE_CASE)),
        Intent(Regex("""\bi gotta\b\s*(.+)""", RegexOption.IGNORE_CASE)),
        Intent(Regex("""\bi(?:'m| am) supposed to\b\s*(.+)""", RegexOption.IGNORE_CASE)),
        Intent(Regex("""\bi (?:really )?should\b\s*(.+)""", RegexOption.IGNORE_CASE), weak = true),
        Intent(Regex("""\bi (?:really )?must\b\s*(.+)""", RegexOption.IGNORE_CASE), weak = true),
        Intent(Regex("""\bi (?:really )?have to\b\s*(.+)""", RegexOption.IGNORE_CASE), weak = true),
        Intent(Regex("""\b(?:to-?do|task|note to self)\s*[:\-–]\s*(.+)""", RegexOption.IGNORE_CASE)),
        Intent(Regex("""\badd\b\s+(.+?)\s+\bto (?:my )?(?:task|todo|to-?do) list\b""", RegexOption.IGNORE_CASE)),
    )

    /**
     * Clause boundaries that end one intent's capture: the next intent's
     * start (handled separately) or one of these connectives —
     * "and I …", "then", "but", "because".
     */
    private val CLAUSE_BOUNDARY = Regex(
        """\b(?:and i|then|but|because)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Modal perfect after a weak intent — "I should have called her" is a
     * regret, not a task. Detects "have/'ve" followed by a past participle
     * (…ed/…en plus common irregulars). "should have a meeting" survives —
     * "a" is no participle.
     */
    private val MODAL_PERFECT = Regex(
        """^(?:have|'ve)\s+(?:\w+(?:ed|en)\b|been|done|gone|had|met|left|sent|""" +
            """forgotten|forgot|made|taken|given|seen|known|called|told|meant|felt|heard|read|lost|paid|said|thought)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * "in 30 minutes", "in 2 hrs", "in 5 mins", plus worded amounts for
     * sub-day units: "in a minute", "in an hour", "in half an hour".
     * Worded forms are deliberately not honoured for days/weeks —
     * "in a week" is a wish, not a schedule.
     */
    private val IN_DURATION = Regex(
        """\bin\s+(half\s+an?|an?|\d+)\s+(mins?|minutes?|hrs?|hours?|days?|weeks?)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val AT_TIME = Regex(
        """\bat\s+(\d{1,2})(?::(\d{2}))?\s*(a\.?m\.?|p\.?m\.?|am|pm)?\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Bare "5pm"/"5:30 am" without the "at" — transcripts drop the preposition. */
    private val BARE_MERIDIAN = Regex(
        """\b(\d{1,2})(?::(\d{2}))?\s*(am|pm)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val NOON = Regex("""\bat\s+noon\b|\bnoon\b""", RegexOption.IGNORE_CASE)
    private val MIDNIGHT = Regex("""\bat\s+midnight\b|\bmidnight\b""", RegexOption.IGNORE_CASE)

    /**
     * Day-part words that bias a bare clock hour's meridian: "morning" → AM,
     * everything else → PM. Checked independently of which day phrase matched
     * so "tomorrow morning" and "in the morning" behave the same.
     */
    private val DAYPART_WORD = Regex(
        """\b(morning|afternoon|evening|tonight|night)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val WEEKDAYS = mapOf(
        "monday" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY,
    )

    private val WEEKDAY_NAME = """monday|tuesday|wednesday|thursday|friday|saturday|sunday"""

    /**
     * Weekday policy:
     * - "next Friday" → the Friday of next week (coming Friday + 7 days).
     * - "this Friday" / "on Friday" / bare "Friday" → the next coming Friday,
     *   *including today* when today is Friday; if that slot already passed,
     *   the reminder rolls a full week, never to a different weekday.
     */
    private val NEXT_WEEKDAY = Regex(
        """\bnext\s+($WEEKDAY_NAME)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val THIS_ON_WEEKDAY = Regex(
        """\b(?:this|on)\s+($WEEKDAY_NAME)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val BARE_WEEKDAY = Regex(
        """\b($WEEKDAY_NAME)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * @param sameDayStrict true when the phrase anchors to *this* day —
     * "today", "tonight", "this afternoon". A past explicit clock then marks
     * the task [ExtractedTask.timeAmbiguous] instead of silently rolling into
     * a day the speaker didn't mean.
     */
    private data class DayPhrase(
        val days: Long,
        val impliedTime: LocalTime?,
        val sameDayStrict: Boolean = false,
    )

    /** Ordered longest-first so "tomorrow morning" wins over bare "tomorrow". */
    private val DAY_PHRASES: List<Pair<Regex, DayPhrase>> = listOf(
        Regex("""\bthe day after tomorrow\b""", RegexOption.IGNORE_CASE) to DayPhrase(2, LocalTime.of(9, 0)),
        Regex("""\bnext week\b""", RegexOption.IGNORE_CASE) to DayPhrase(7, LocalTime.of(9, 0)),
        Regex("""\btomorrow (?:morning|early)\b""", RegexOption.IGNORE_CASE) to DayPhrase(1, LocalTime.of(9, 0)),
        Regex("""\btomorrow afternoon\b""", RegexOption.IGNORE_CASE) to DayPhrase(1, LocalTime.of(15, 0)),
        Regex("""\btomorrow (?:evening|night)\b""", RegexOption.IGNORE_CASE) to DayPhrase(1, LocalTime.of(20, 0)),
        Regex("""\btomorrow\b""", RegexOption.IGNORE_CASE) to DayPhrase(1, LocalTime.of(9, 0)),
        Regex("""\btonight\b""", RegexOption.IGNORE_CASE) to DayPhrase(0, LocalTime.of(20, 0), sameDayStrict = true),
        Regex("""\bthis evening\b""", RegexOption.IGNORE_CASE) to DayPhrase(0, LocalTime.of(19, 0), sameDayStrict = true),
        Regex("""\bthis afternoon\b""", RegexOption.IGNORE_CASE) to DayPhrase(0, LocalTime.of(15, 0), sameDayStrict = true),
        Regex("""\bthis morning\b""", RegexOption.IGNORE_CASE) to DayPhrase(0, LocalTime.of(9, 0), sameDayStrict = true),
        Regex("""\bin the evening\b""", RegexOption.IGNORE_CASE) to DayPhrase(0, LocalTime.of(19, 0), sameDayStrict = true),
        Regex("""\bin the afternoon\b""", RegexOption.IGNORE_CASE) to DayPhrase(0, LocalTime.of(15, 0), sameDayStrict = true),
        Regex("""\bin the morning\b""", RegexOption.IGNORE_CASE) to DayPhrase(0, LocalTime.of(9, 0), sameDayStrict = true),
        Regex("""\btoday\b""", RegexOption.IGNORE_CASE) to DayPhrase(0, null, sameDayStrict = true),
        Regex("""\b(?:end of (?:the )?(?:working )?day|eod|close of business)\b""", RegexOption.IGNORE_CASE) to DayPhrase(0, LocalTime.of(17, 0), sameDayStrict = true),
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
        // Curly apostrophes — speech engines emit U+2019, our intents use '.
        t = t.replace('’', '\'').replace('‘', '\'')
        // Modal-perfect contractions ("should've") expand so the past-tense
        // guard sees the "have".
        t = t.replace(
            Regex("""\b(should|could|would|must)'ve\b""", RegexOption.IGNORE_CASE),
            "$1 have",
        )
        t = t.replace(Regex("""\b([aApP])\s*\.\s*[mM]\s*\.?"""), "$1m")
        t = t.replace(
            Regex("""\b(\d{1,2})\.(\d{2})\s*([aApP][mM])\b"""),
            "$1:$2 $3",
        )
        return t
    }

    /** One intent match inside a sentence, before clause bounding. */
    private data class Hit(
        val start: Int,
        val captureStart: Int,
        /** End of group(1) — early for lazy captures like "add X to list". */
        val captureEnd: Int,
        val weak: Boolean,
        val order: Int,
    )

    fun extract(transcript: String, now: LocalDateTime): List<ExtractedTask> {
        val sentences = normalizeTranscript(transcript)
            .split(SENTENCE_SPLIT)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val results = LinkedHashMap<String, ExtractedTask>()

        for (sentence in sentences) {
            // Collect every intent in the sentence; first pattern wins on ties
            // ("remind me to" beats "remind me" at the same offset).
            val hits = mutableListOf<Hit>()
            for ((order, intent) in INTENTS.withIndex()) {
                for (m in intent.regex.findAll(sentence)) {
                    val g = m.groups[1] ?: continue
                    hits += Hit(
                        start = m.range.first,
                        captureStart = g.range.first,
                        captureEnd = g.range.last + 1,
                        weak = intent.weak,
                        order = order,
                    )
                }
            }
            hits.sortWith(compareBy({ it.start }, { it.order }))
            val accepted = mutableListOf<Hit>()
            for (h in hits) {
                // A match whose intent phrase begins inside an accepted match's
                // capture is the same intent re-phrased ("remind me" inside
                // "remind me to …"), not a new one.
                if (accepted.none { h.start < it.captureStart }) accepted += h
            }

            for ((i, hit) in accepted.withIndex()) {
                // A capture ends at its own group end, the next intent's
                // start, or a clause boundary ("and I …", "then", "but",
                // "because") — whichever comes first.
                var spanEnd = accepted.getOrNull(i + 1)?.start ?: sentence.length
                if (hit.captureEnd < spanEnd) spanEnd = hit.captureEnd
                CLAUSE_BOUNDARY.find(sentence, hit.captureStart)?.let { b ->
                    if (b.range.first < spanEnd) spanEnd = b.range.first
                }
                var fragment = sentence.substring(hit.captureStart, spanEnd)

                // "I should have called her" — modal perfect is a regret,
                // not an intention. Weak intents only.
                if (hit.weak && MODAL_PERFECT.containsMatchIn(fragment.trimStart())) continue

                // Time phrases are resolved on this intent's own clause so a
                // later clause's "tomorrow" can't leak into the wrong task.
                val contextStart = if (i == 0) 0 else hit.start
                val time = resolveTime(sentence.substring(contextStart, spanEnd), now)

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
                        results[key] = ExtractedTask(
                            title = title,
                            remindAt = time.remindAt,
                            timeAmbiguous = time.ambiguous,
                            suggested = hit.weak,
                        )
                    }
                }
            }
        }
        return results.values.toList()
    }

    private data class TimeResult(
        val remindAt: LocalDateTime?,
        /** Literal matched substrings, for removal from the title. */
        val matchedLiterals: List<String>,
        /** See [ExtractedTask.timeAmbiguous]. */
        val ambiguous: Boolean = false,
    )

    private fun resolveTime(sentence: String, now: LocalDateTime): TimeResult {
        val literals = mutableListOf<String>()

        // Relative "in N units" wins over everything.
        for (m in IN_DURATION.findAll(sentence)) {
            val amountToken = m.groupValues[1].lowercase().replace(Regex("""\s+"""), " ")
            val unit = m.groupValues[2].lowercase()
            val unitMinutes = when {
                unit.startsWith("min") -> 1L
                unit.startsWith("h") -> 60L
                unit.startsWith("d") -> 24L * 60
                else -> 7L * 24 * 60
            }
            // Worded amounts ("a", "an", "half an") only bind to sub-day units.
            if (amountToken.toLongOrNull() == null && unitMinutes > 60L) continue
            val amount = when (amountToken) {
                "a", "an" -> 1.0
                "half a", "half an" -> 0.5
                else -> amountToken.toDouble()
            }
            val result = now.plusMinutes((amount * unitMinutes).toLong())
            return TimeResult(result, listOf(m.value))
        }

        val today = now.toLocalDate()
        var dayShift = 0L
        var impliedTime: LocalTime? = null
        var rollWeeks = false
        var strictSameDay = false

        val nextW = NEXT_WEEKDAY.find(sentence)
        val qualifiedW = if (nextW == null) THIS_ON_WEEKDAY.find(sentence) else null
        val bareW = if (nextW == null && qualifiedW == null) BARE_WEEKDAY.find(sentence) else null
        val weekdayMatch = nextW ?: qualifiedW ?: bareW
        if (weekdayMatch != null) {
            val dow = WEEKDAYS[weekdayMatch.groupValues[1].lowercase()]!!
            val coming = today.with(TemporalAdjusters.nextOrSame(dow))
            val target = if (nextW != null) coming.plusWeeks(1) else coming
            dayShift = target.toEpochDay() - today.toEpochDay()
            rollWeeks = true
            impliedTime = LocalTime.of(9, 0)
            literals.add(weekdayMatch.value)
        } else {
            for ((pattern, phrase) in DAY_PHRASES) {
                val m = pattern.find(sentence) ?: continue
                dayShift = phrase.days
                impliedTime = phrase.impliedTime
                strictSameDay = phrase.sameDayStrict
                literals.add(m.value)
                break
            }
        }

        val dayPart = DAYPART_WORD.find(sentence)?.groupValues?.get(1)?.lowercase()

        var explicitClock = false
        var explicitTime: LocalTime? = null
        val atMatch = AT_TIME.find(sentence)
        if (atMatch != null) {
            explicitTime = parseClock(
                atMatch.groupValues[1], atMatch.groupValues[2], atMatch.groupValues[3], dayPart,
            )
            if (explicitTime != null) {
                explicitClock = true
                literals.add(atMatch.value)
            }
        }
        if (explicitTime == null) {
            BARE_MERIDIAN.find(sentence)?.let { m ->
                explicitTime = parseClock(m.groupValues[1], m.groupValues[2], m.groupValues[3], dayPart)
                if (explicitTime != null) {
                    explicitClock = true
                    literals.add(m.value)
                }
            }
        }
        if (explicitTime == null) {
            NOON.find(sentence)?.let {
                explicitTime = LocalTime.NOON
                explicitClock = true
                literals.add(it.value)
            }
        }
        if (explicitTime == null) {
            MIDNIGHT.find(sentence)?.let {
                explicitTime = LocalTime.MIDNIGHT
                explicitClock = true
                literals.add(it.value)
            }
        }

        val time = explicitTime ?: impliedTime
        if (time == null) return TimeResult(null, literals)

        var result = LocalDateTime.of(today.plusDays(dayShift), time)
        if (dayShift == 0L && result <= now) {
            // "tonight at 9" said at 21:30 can't roll into tomorrow — that
            // would no longer be tonight. Flag it and let the user pick.
            if (explicitClock && strictSameDay) {
                return TimeResult(null, literals, ambiguous = true)
            }
            result = if (rollWeeks) result.plusWeeks(1) else result.plusDays(1)
        }

        return TimeResult(result, literals)
    }

    /**
     * Meridian resolution order: explicit am/pm wins; otherwise a day-part
     * word in context supplies it ("morning" → am, afternoon/evening/night →
     * pm); only with no context at all does the 1–7→PM heuristic apply.
     */
    private fun parseClock(
        hourRaw: String,
        minuteRaw: String,
        meridianRaw: String,
        dayPart: String?,
    ): LocalTime? {
        val hour = hourRaw.toIntOrNull() ?: return null
        val minute = minuteRaw.toIntOrNull() ?: 0
        val meridian = meridianRaw.lowercase().replace(".", "")
        val h24 = when {
            meridian.startsWith("p") -> if (hour == 12) 12 else hour + 12
            meridian.startsWith("a") -> if (hour == 12) 0 else hour
            dayPart == "morning" -> if (hour == 12) 0 else hour
            dayPart != null -> if (hour in 1..11) hour + 12 else hour
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
        t = t.replace(Regex("""\s+(at|on|in|by|before|for|and|or)$""", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("""^to\s+""", RegexOption.IGNORE_CASE), "")
        // Keep titles glanceable: ~8 words / 60 chars, then re-clean whatever
        // the cut exposed.
        val words = t.split(" ")
        if (words.size > 8) t = words.take(8).joinToString(" ")
        if (t.length > 60) t = t.take(60).substringBeforeLast(' ', t.take(60))
        t = t.replace(Regex("""\s+(at|on|in|by|before|for|and|or|to)$""", RegexOption.IGNORE_CASE), "")
        t = t.trim().trimEnd(',', '.')
        if (t.isEmpty()) return t
        return t.replaceFirstChar { it.uppercase() }
    }
}
