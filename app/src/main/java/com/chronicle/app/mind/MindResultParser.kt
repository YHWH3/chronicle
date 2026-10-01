package com.chronicle.app.mind

import com.chronicle.app.domain.TaskExtractor
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses a model's reply into [MindResult]. Hardened for small local models:
 * strips `<think>` blocks (closed or truncated open) and reasoning preambles,
 * tolerates ```json fences and surrounding prose, finds the JSON object by
 * balanced braces, and salvages bare `"title":"…"` pairs when the JSON is
 * truncated mid-array. Returns null when nothing usable is found.
 */
object MindResultParser {

    private val THINK_BLOCK = Regex("""<think>[\s\S]*?(?:</think>|$)""", RegexOption.IGNORE_CASE)
    private val THINK_TAG = Regex("""</?think>""", RegexOption.IGNORE_CASE)
    private val TITLE_FIELD = Regex("""(?:^|[{\s,])"title"\s*:\s*"((?:[^"\\]|\\.)*)"""", RegexOption.IGNORE_CASE)
    private val REMIND_FIELD = Regex(""""remindAt"\s*:\s*"((?:[^"\\]|\\.)*)"""", RegexOption.IGNORE_CASE)
    private val SENTENCE_BOUNDARY = Regex("""[.!?]""")

    /** "I need to call Sam" → "call Sam" — the action clause beats the pronoun. */
    private val PRONOUN_MODAL = Regex(
        """^(?:i|we)\s+(?:(?:really\s+)?(?:need|have|want|should|must|ought)\s+to|""" +
            """(?:'m|am)\s+supposed\s+to|(?:'ve|have)\s+got\s+to|gotta|will|'ll|""" +
            """(?:'m|am)\s+(?:going\s+to|gonna))\s+(.+)$""",
        RegexOption.IGNORE_CASE,
    )
    /** "I keep forgetting, call Sam" → "call Sam". */
    private val PRONOUN_COMMA = Regex(
        """^(?:i|we|you|he|she|they|it)\b[^,;:]+[,;:]\s+(.+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val LENIENT_DATETIME = Regex(
        """(\d{4}-\d{2}-\d{2})[T ](\d{1,2}):(\d{2})(?::(\d{1,3}))?"""
    )
    private val LENIENT_TIME = Regex("""^(\d{1,2}):(\d{2})(?::(\d{1,3}))?$""")

    fun parse(reply: String, now: LocalDateTime): MindResult? {
        val cleaned = cleanReply(reply)
        // Prefer the cleaned reply; if an unclosed think block swallowed the
        // payload, fall back to the raw reply before giving up.
        val json = locateJson(cleaned) ?: locateJson(reply)
        if (json != null) {
            runCatching { JSONObject(json) }.getOrNull()?.let { obj ->
                val tasks = mutableListOf<MindTask>()
                obj.optJSONArray("tasks")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val t = arr.optJSONObject(i) ?: continue
                        val title = polishTitle(t.optString("title")) ?: continue
                        tasks += MindTask(
                            title = title,
                            remindAt = parseTime(t.opt("remindAt"), now),
                            suggested = t.optBoolean("suggested", false),
                        )
                    }
                }
                val reflection = obj.optString("reflection").takeIf { it.isNotBlank() }
                return MindResult(tasks = tasks, reflection = reflection)
            }
        }
        // Truncated or garbled JSON — salvage title/remindAt field pairs.
        var salvage = cleaned
        var matches = TITLE_FIELD.findAll(salvage).toList()
        if (matches.isEmpty() && salvage != reply) {
            salvage = reply
            matches = TITLE_FIELD.findAll(salvage).toList()
        }
        val tasks = matches.mapNotNull { m ->
            polishTitle(unescape(m.groupValues[1]))?.let { title ->
                val tail = salvage.substring(m.range.last + 1).take(200)
                val remind = REMIND_FIELD.find(tail)?.let { r -> parseTime(unescape(r.groupValues[1]), now) }
                MindTask(title, remind)
            }
        }
        if (json == null && tasks.isEmpty()) return null
        return MindResult(tasks = tasks)
    }

    /** Removes think blocks, stray think tags, and leading whitespace/prose. */
    private fun cleanReply(reply: String): String {
        var t = reply
        t = t.replace(THINK_BLOCK, "")
        t = t.replace(THINK_TAG, "")
        return t.trim()
    }

    private fun locateJson(reply: String): String? {
        val fenceStart = reply.indexOf("```")
        if (fenceStart >= 0) {
            val nl = reply.indexOf('\n', fenceStart)
            val end = reply.indexOf("```", if (nl >= 0) nl else fenceStart + 3)
            if (nl >= 0 && end > nl) return reply.substring(nl + 1, end).trim()
        }
        // Balanced braces from the first '{', skipping string literals — so
        // trailing prose containing '}' can't corrupt the object.
        val start = reply.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until reply.length) {
            val c = reply[i]
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return reply.substring(start, i + 1)
                }
            }
        }
        return null // unbalanced — the regex salvage path may still find fields
    }

    /**
     * Normalizes a model-produced title: keeps the action clause of
     * pronoun-led titles, trims at the first sentence boundary, caps at
     * 8 words / 60 chars. Returns null when nothing usable remains.
     */
    private fun polishTitle(raw: String): String? {
        var t = raw.replace(Regex("""\s+"""), " ").trim()
        PRONOUN_MODAL.find(t)?.let { t = it.groupValues[1].trim() }
            ?: PRONOUN_COMMA.find(t)?.let { t = it.groupValues[1].trim() }
        SENTENCE_BOUNDARY.find(t)?.let { t = t.substring(0, it.range.first) }
        val words = t.split(" ").filter { it.isNotEmpty() }
        if (words.size > 8) t = words.take(8).joinToString(" ")
        if (t.length > 60) t = t.take(60).substringBeforeLast(' ', t.take(60))
        t = t.trim().trimEnd(',', '.', ';', ':')
        if (t.length < 3) return null
        return t.replaceFirstChar { it.uppercase() }
    }

    private fun parseTime(raw: Any?, now: LocalDateTime): LocalDateTime? {
        if (raw == null || raw == JSONObject.NULL) return null
        val s = raw.toString().trim()
        if (s.isEmpty() || s.equals("null", ignoreCase = true)) return null
        // Ordered: strict local → zoned/offset → instant → date-only →
        // lenient clock fix-up → natural language.
        runCatching { return LocalDateTime.parse(s) }
        runCatching { return OffsetDateTime.parse(s).toLocalDateTime() }
        runCatching { return Instant.parse(s).atZone(ZoneId.systemDefault()).toLocalDateTime() }
        runCatching { return LocalDate.parse(s).atTime(9, 0) }
        parseLenient(s, now)?.let { return it }
        return TaskExtractor.resolveTimeText(s, now)
    }

    /**
     * Last-resort parse for malformed ISO shapes ("2026-09-26 17:00", bare
     * "17:30"). Seconds ≥ 60 are rejected — except when the minutes field is
     * 0 ("17:00:75" style padding), where they clamp to 59. Minutes ≥ 60 are
     * always rejected.
     */
    private fun parseLenient(s: String, now: LocalDateTime): LocalDateTime? {
        LENIENT_DATETIME.find(s)?.let { m ->
            val date = runCatching { LocalDate.parse(m.groupValues[1]) }.getOrNull() ?: return@let
            val minute = m.groupValues[3].toInt()
            if (minute > 59) return@let
            val second = fixSeconds(minute, m.groupValues[4]) ?: return@let
            val time = runCatching {
                java.time.LocalTime.of(m.groupValues[2].toInt(), minute, second)
            }.getOrNull() ?: return@let
            return LocalDateTime.of(date, time)
        }
        LENIENT_TIME.find(s)?.let { m ->
            val minute = m.groupValues[2].toInt()
            if (minute > 59) return null
            val second = fixSeconds(minute, m.groupValues[3]) ?: return null
            val time = runCatching {
                java.time.LocalTime.of(m.groupValues[1].toInt(), minute, second)
            }.getOrNull() ?: return null
            var result = LocalDateTime.of(now.toLocalDate(), time)
            if (result <= now) result = result.plusDays(1)
            return result
        }
        return null
    }

    /** Seconds policy: ≥60 rejected, except minutes==0 where they clamp to 59. */
    private fun fixSeconds(minute: Int, secondRaw: String): Int? {
        if (secondRaw.isEmpty()) return 0
        val second = secondRaw.toIntOrNull() ?: return null
        if (second < 60) return second
        return if (minute == 0) 59 else null
    }

    private fun unescape(s: String): String =
        s.replace("\\\"", "\"").replace("\\n", " ").replace("\\\\", "\\")

    /** Builds the chat payload shared by OpenRouter and OpenAI-compatible backends. */
    fun requestBody(model: String, text: String, now: LocalDateTime): JSONObject =
        JSONObject()
            .put("model", model)
            .put("temperature", 0.1)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", MIND_PROMPT))
                .put(JSONObject().put("role", "user")
                    .put("content", "Current time: $now\nCapture:\n$text")))
}
