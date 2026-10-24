package com.chronicle.app.mind

import java.time.LocalDateTime
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NoteCleanupCloudTest {
    @Test fun `cleanup sends its own contract and returns text rather than task JSON`() = runTest {
        var captured: ChatMind.RequestSpec? = null
        val engine = ChatMind("test", "https://example.test/v1", "", "test-model", newCall = { spec ->
            captured = spec
            object : ChatMind.BlockingCall {
                override fun run(): ChatMind.CloudReply {
                    val content = JSONObject().put("text", "I paid 25 yesterday.\n\nI need to call Sam.").toString()
                    val body = JSONObject().put("choices", org.json.JSONArray().put(JSONObject()
                        .put("message", JSONObject().put("content", content)))).toString()
                    return ChatMind.CloudReply(200, body)
                }
                override fun cancel() {}
            }
        })
        val cleaned = engine.cleanup("I paid 25 yesterday. I need to call Sam.", LocalDateTime.of(2026, 10, 4, 16, 43))
        assertEquals("I paid 25 yesterday.\n\nI need to call Sam.", cleaned)
        val messages = JSONObject(captured!!.body).getJSONArray("messages")
        assertEquals(NOTE_CLEANUP_PROMPT, messages.getJSONObject(0).getString("content"))
        assertTrue(messages.getJSONObject(1).getString("content").contains("I paid 25"))
        assertFalse(captured!!.headers.containsKey("Authorization"))
    }

    @Test fun `all local prompt formats accept cleanup without changing extraction default`() {
        val now = LocalDateTime.of(2026, 10, 4, 16, 43)
        for (format in PromptFormat.entries) {
            val cleanup = format.render("My note", now, NOTE_CLEANUP_PROMPT)
            assertTrue(cleanup.contains(NOTE_CLEANUP_PROMPT))
            assertFalse(cleanup.contains(MIND_PROMPT))
            assertTrue(format.render("My note", now).contains(MIND_PROMPT))
        }
    }
}
