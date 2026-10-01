package com.chronicle.app.mind

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BE-22 hardening: think blocks, truncated replies, lenient timestamps,
 * pronoun-led titles, and the suggested flag.
 */
class MindResultParserHardeningTest {

    private val friday9am = LocalDateTime.of(2026, 9, 25, 9, 0)

    @Test
    fun `think block is stripped before parsing`() {
        val reply = """<think>The user wants milk. Tasks: buy milk tomorrow.</think>
            {"tasks":[{"title":"Buy milk","remindAt":null}]}"""
        val result = MindResultParser.parse(reply, friday9am)!!
        assertEquals("Buy milk", result.tasks[0].title)
    }

    @Test
    fun `unclosed think block is stripped`() {
        val reply = """<think>Reasoning forever… no close tag
            {"tasks":[{"title":"Buy milk","remindAt":null}]}"""
        val result = MindResultParser.parse(reply, friday9am)!!
        assertEquals("Buy milk", result.tasks[0].title)
    }

    @Test
    fun `truncated json is salvaged by field regex`() {
        // Model ran out of tokens mid-array — titles still survive.
        val reply = """{"tasks":[{"title":"Buy milk","remindAt":null},{"title":"Call Sam","remi"""
        val result = MindResultParser.parse(reply, friday9am)!!
        assertEquals(listOf("Buy milk", "Call Sam"), result.tasks.map { it.title })
    }

    @Test
    fun `offset datetime and instant parse`() {
        val reply = """{"tasks":[
            {"title":"A task","remindAt":"2026-09-26T17:00:00+02:00"},
            {"title":"B task","remindAt":"2026-09-26T15:00:00Z"}]}"""
        val result = MindResultParser.parse(reply, friday9am)!!
        assertEquals(LocalDateTime.of(2026, 9, 26, 17, 0), result.tasks[0].remindAt)
        assertTrue(result.tasks[1].remindAt != null)
    }

    @Test
    fun `bare date resolves to nine am`() {
        val reply = """{"tasks":[{"title":"A task","remindAt":"2026-09-28"}]}"""
        val result = MindResultParser.parse(reply, friday9am)!!
        assertEquals(LocalDateTime.of(2026, 9, 28, 9, 0), result.tasks[0].remindAt)
    }

    @Test
    fun `seconds over 59 are clamped when minutes are zero`() {
        // Some models pad ":00" minutes with a bogus seconds field.
        val reply = """{"tasks":[{"title":"A task","remindAt":"2026-09-26T17:00:75"}]}"""
        val result = MindResultParser.parse(reply, friday9am)!!
        assertEquals(LocalDateTime.of(2026, 9, 26, 17, 0, 59), result.tasks[0].remindAt)
    }

    @Test
    fun `seconds over 59 with nonzero minutes are rejected`() {
        val reply = """{"tasks":[{"title":"A task","remindAt":"2026-09-26T17:30:75"}]}"""
        val result = MindResultParser.parse(reply, friday9am)!!
        assertNull(result.tasks[0].remindAt)
    }

    @Test
    fun `pronoun led title keeps the action clause`() {
        val reply = """{"tasks":[{"title":"I need to call Sam tomorrow","remindAt":null}]}"""
        val result = MindResultParser.parse(reply, friday9am)!!
        assertEquals("Call Sam tomorrow", result.tasks[0].title)
    }

    @Test
    fun `long titles trim at sentence boundary then word cap`() {
        val reply = """{"tasks":[{"title":"Call Sam. Then buy milk and bread","remindAt":null},
            {"title":"one two three four five six seven eight nine ten","remindAt":null}]}"""
        val result = MindResultParser.parse(reply, friday9am)!!
        assertEquals("Call Sam", result.tasks[0].title)
        assertEquals("One two three four five six seven eight", result.tasks[1].title)
    }

    @Test
    fun `suggested flag passes through`() {
        val reply = """{"tasks":[
            {"title":"Buy milk","remindAt":null,"suggested":true},
            {"title":"Call Sam","remindAt":null}]}"""
        val result = MindResultParser.parse(reply, friday9am)!!
        assertTrue(result.tasks[0].suggested)
        assertTrue(!result.tasks[1].suggested)
    }

    @Test
    fun `deterministic mind marks weak intents suggested`() = kotlinx.coroutines.test.runTest {
        val result = DeterministicMind.extract("I should buy milk. Remind me to call Sam.", friday9am)
        assertEquals(2, result.tasks.size)
        assertTrue(result.tasks[0].suggested)
        assertTrue(!result.tasks[1].suggested)
    }
}
