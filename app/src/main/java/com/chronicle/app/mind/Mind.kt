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

    /** Extracts tasks/reflection from [text]; throws on failure so callers can fall back. */
    suspend fun extract(text: String, now: LocalDateTime): MindResult
}

/** The no-model baseline — deterministic parsing, works offline with nothing downloaded. */
object DeterministicMind : MindEngine {
    override val label = "Quiet parser"

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
Set "suggested":true for hedged self-talk ("I should…", "I have to…") the person has not committed to; omit otherwise.
Use the supplied current time to resolve relative times ("tomorrow 5pm" -> next-day 17:00).
Title is imperative, <= 8 words. reflection may be omitted."""
