package com.chronicle.app.mind

import com.chronicle.app.domain.TaskExtractor
import java.time.LocalDateTime

/** A task understood from the user's words. */
data class MindTask(
    val title: String,
    val remindAt: LocalDateTime?,
    /**
     * True when the engine read the intent as hedged self-talk
     * ("I should…", "I have to…") — the UI offers these for keep/reject
     * rather than committing them outright.
     */
    val suggested: Boolean = false,
)

/** What the mind made of a capture: extracted tasks plus an optional reflection. */
data class MindResult(
    val tasks: List<MindTask>,
    val reflection: String? = null,
)

/**
 * The outcome of one extraction pass — which engine was configured, which
 * engine actually answered, and why a fallback happened (null when the
 * configured engine ran cleanly). UI must show [actualLabel], never assume
 * the configured engine produced the result.
 */
data class MindExecutionResult(
    val result: MindResult,
    val configuredLabel: String,
    val actualLabel: String,
    val fallbackReason: String?,
)

/** Something that reads captured words and decides what they mean. */
interface MindEngine {
    /** Human-facing name shown in Settings and confirmations. */
    val label: String

    /** Native load is measured separately and excluded from the generation budget. */
    suspend fun prepare() { }

    /** Extracts tasks/reflection from [text]; throws on failure so callers can fall back. */
    suspend fun extract(text: String, now: LocalDateTime): MindResult

    /** Rewriting is optional; unsupported engines must say so, not fabricate a result. */
    suspend fun cleanup(text: String, now: LocalDateTime): String =
        error("This engine does not support note cleanup")
}

/** The no-model baseline — deterministic parsing, works offline with nothing downloaded. */
object DeterministicMind : MindEngine {
    override val label = "Basic (built in)"

    override suspend fun extract(text: String, now: LocalDateTime): MindResult =
        MindResult(
            TaskExtractor.extract(text, now).map {
                MindTask(it.title, it.remindAt, suggested = it.suggested)
            },
        )
}

const val MIND_PROMPT = """You read a short journal capture and reply with ONLY JSON (no prose, no fences):
{"tasks":[{"title":"...","remindAt":"ISO-8601 local datetime or null","suggested":false}],"reflection":"one short sentence"}
Rules: only include tasks the person clearly intends to do (reminders, plans, todos).
Read every sentence and paragraph, not just the first action. Find separate future commitments even inside narrative prose.
Do not turn completed events, regrets, negated actions, other people's responsibilities or hypothetical plans into tasks.
Keep each action's own time; do not copy a time from a neighbouring action. Use null when no time was given.
Set "suggested":true for hedged self-talk ("I should…", "I have to…") the person has not committed to; omit otherwise.
Use the supplied current time to resolve relative times ("tomorrow 5pm" -> next-day 17:00).
Title is imperative, <= 10 words. reflection may be omitted.
Examples (current time 2026-10-04T16:43):
Capture: I should probably call the dentist
Reply: {"tasks":[{"title":"Call the dentist","remindAt":null,"suggested":true}]}
Capture: I need to buy milk , eggs and bread
Reply: {"tasks":[{"title":"Buy milk, eggs and bread","remindAt":null}]}
Capture: walked the coast road, cold wind
Reply: {"tasks":[]}
Capture: remind me in half an hour to take the pizza out
Reply: {"tasks":[{"title":"Take the pizza out","remindAt":"2026-10-04T17:13"}]}
Capture: I'm supposed to meet Raj for lunch on Tuesday at 1
Reply: {"tasks":[{"title":"Meet Raj for lunch","remindAt":"2026-10-06T13:00"}]}
Capture: remind me to text Ana in 2 hours and to water the plants tonight
Reply: {"tasks":[{"title":"Text Ana","remindAt":"2026-10-04T18:43"},{"title":"Water the plants","remindAt":"2026-10-04T20:00"}]}
Use the actual supplied current time for this capture, not the examples' dates."""
