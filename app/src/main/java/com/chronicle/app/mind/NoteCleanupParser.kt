package com.chronicle.app.mind

import org.json.JSONObject

/** Cleanup is strict: never salvage a truncated reply and replace a whole note with it. */
object NoteCleanupParser {
    fun parse(raw: String): String {
        val trimmed = raw.trim()
        val json = if (trimmed.startsWith("```json") && trimmed.endsWith("```")) {
            trimmed.removePrefix("```json").removeSuffix("```").trim()
        } else trimmed
        val result = JSONObject(json)
        require(result.has("text") && result.get("text") is String) { "The cleanup reply could not be read" }
        return result.getString("text").trim().also {
            require(it.isNotBlank()) { "The model returned an empty note" }
        }
    }
}
