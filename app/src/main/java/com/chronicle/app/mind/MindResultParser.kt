package com.chronicle.app.mind

import com.chronicle.app.domain.TaskExtractor
import java.time.LocalDateTime
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses a model's reply into [MindResult]. Tolerates ```json fences and
 * surrounding prose; returns null when no usable object is found.
 */
object MindResultParser {

    fun parse(reply: String, now: LocalDateTime): MindResult? {
        val json = locateJson(reply) ?: return null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val tasks = mutableListOf<MindTask>()
        obj.optJSONArray("tasks")?.let { arr ->
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val title = t.optString("title").trim()
                if (title.length < 3) continue
                tasks += MindTask(title = title, remindAt = parseTime(t, now))
            }
        }
        val reflection = obj.optString("reflection").takeIf { it.isNotBlank() }
        return MindResult(tasks = tasks, reflection = reflection)
    }

    private fun locateJson(reply: String): String? {
        val fenceStart = reply.indexOf("```")
        if (fenceStart >= 0) {
            val nl = reply.indexOf('\n', fenceStart)
            val end = reply.indexOf("```", if (nl >= 0) nl else fenceStart + 3)
            if (nl >= 0 && end > nl) return reply.substring(nl + 1, end).trim()
        }
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        return if (start in 0..end) reply.substring(start, end + 1) else null
    }

    private fun parseTime(t: JSONObject, now: LocalDateTime): LocalDateTime? {
        val raw = t.opt("remindAt") ?: return null
        if (raw == JSONObject.NULL) return null
        val s = raw.toString().trim()
        if (s.isEmpty() || s.equals("null", ignoreCase = true)) return null
        runCatching { return LocalDateTime.parse(s) }
        // Natural-language fallback ("tomorrow 5pm") through the deterministic resolver.
        return TaskExtractor.resolveTimeText(s, now)
    }

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
