package com.chronicle.app.mind

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MindResultParserTest {

    private val friday9am = LocalDateTime.of(2026, 9, 25, 9, 0)

    @Test
    fun `clean json parses`() {
        val reply = """{"tasks":[{"title":"Call the dentist","remindAt":"2026-09-26T09:00"}],
            "reflection":"A day that kept its promises."}"""
        val result = MindResultParser.parse(reply, friday9am)!!
        assertEquals(1, result.tasks.size)
        assertEquals("Call the dentist", result.tasks[0].title)
        assertEquals(LocalDateTime.of(2026, 9, 26, 9, 0), result.tasks[0].remindAt)
        assertEquals("A day that kept its promises.", result.reflection)
    }

    @Test
    fun `fenced json parses`() {
        val reply = "Here you go:\n```json\n{\"tasks\":[{\"title\":\"Water the plants\",\"remindAt\":null}]}\n```"
        val result = MindResultParser.parse(reply, friday9am)!!
        assertEquals("Water the plants", result.tasks[0].title)
        assertNull(result.tasks[0].remindAt)
    }

    @Test
    fun `prose around json is tolerated`() {
        val reply = "Sure! {\"tasks\":[{\"title\":\"Buy coffee\",\"remindAt\":null}]} hope that helps"
        val result = MindResultParser.parse(reply, friday9am)!!
        assertEquals("Buy coffee", result.tasks[0].title)
    }

    @Test
    fun `natural language remindAt falls back to deterministic resolver`() {
        val reply = """{"tasks":[{"title":"Take out bins","remindAt":"tomorrow 5pm"}]}"""
        val result = MindResultParser.parse(reply, friday9am)!!
        // "tomorrow" resolves to next day 09:00 through the deterministic path.
        assertEquals(LocalDateTime.of(2026, 9, 26, 9, 0), result.tasks[0].remindAt)
    }

    @Test
    fun `missing tasks key yields empty list`() {
        val result = MindResultParser.parse("""{"reflection":"quiet day"}""", friday9am)!!
        assertTrue(result.tasks.isEmpty())
    }

    @Test
    fun `no json returns null`() {
        assertNull(MindResultParser.parse("I cannot parse this.", friday9am))
        assertNull(MindResultParser.parse("", friday9am))
    }

    @Test
    fun `short titles are dropped`() {
        val reply = """{"tasks":[{"title":"go","remindAt":null},{"title":"Call him","remindAt":null}]}"""
        val result = MindResultParser.parse(reply, friday9am)!!
        assertEquals(1, result.tasks.size)
        assertEquals("Call him", result.tasks[0].title)
    }

    @Test
    fun `request body is openai shaped`() {
        val body = MindResultParser.requestBody("m", "hello", friday9am)
        assertEquals("m", body.getString("model"))
        val messages = body.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertTrue(
            messages.getJSONObject(1).getString("content").contains("hello"),
        )
    }
}
