package com.chronicle.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class TaskExtractorTest {

    private val friday9am = LocalDateTime.of(2026, 9, 25, 9, 0) // Friday

    @Test
    fun `remind me to with time`() {
        val tasks = TaskExtractor.extract("Remind me to call the dentist tomorrow at 5pm.", friday9am)
        assertEquals(1, tasks.size)
        assertEquals("Call the dentist", tasks[0].title)
        assertEquals(LocalDateTime.of(2026, 9, 26, 17, 0), tasks[0].remindAt)
    }

    @Test
    fun `remind me to without time`() {
        val tasks = TaskExtractor.extract("Remind me to water the fig tree.", friday9am)
        assertEquals(1, tasks.size)
        assertEquals("Water the fig tree", tasks[0].title)
        assertNull(tasks[0].remindAt)
    }

    @Test
    fun `i need to on weekday`() {
        val tasks = TaskExtractor.extract("I need to send the invoice on Monday.", friday9am)
        assertEquals("Send the invoice", tasks[0].title)
        assertEquals(LocalDateTime.of(2026, 9, 28, 9, 0), tasks[0].remindAt)
    }

    @Test
    fun `in N hours`() {
        val tasks = TaskExtractor.extract("Remind me to take the bread out in 2 hours.", friday9am)
        assertEquals(LocalDateTime.of(2026, 9, 25, 11, 0), tasks[0].remindAt)
    }

    @Test
    fun `tonight rolls to tomorrow when passed`() {
        val night = LocalDateTime.of(2026, 9, 25, 21, 30)
        val tasks = TaskExtractor.extract("Remind me to journal tonight.", night)
        // 20:00 already passed → next day 20:00
        assertEquals(LocalDateTime.of(2026, 9, 26, 20, 0), tasks[0].remindAt)
    }

    @Test
    fun `past explicit time rolls to next day`() {
        val tasks = TaskExtractor.extract("Remind me to stretch at 7am.", friday9am)
        assertEquals(LocalDateTime.of(2026, 9, 26, 7, 0), tasks[0].remindAt)
    }

    @Test
    fun `multiple sentences yield multiple tasks`() {
        val tasks = TaskExtractor.extract(
            "Today was long. Remind me to buy coffee tomorrow. I need to email Sam tonight.",
            friday9am,
        )
        assertEquals(2, tasks.size)
        assertEquals("Buy coffee", tasks[0].title)
        assertEquals("Email Sam", tasks[1].title)
    }

    @Test
    fun `narration is not a task`() {
        val tasks = TaskExtractor.extract("I walked along the river and watched the boats.", friday9am)
        assertTrue(tasks.isEmpty())
    }

    @Test
    fun `todo with colon`() {
        val tasks = TaskExtractor.extract("todo: pick up dry cleaning", friday9am)
        assertEquals("Pick up dry cleaning", tasks[0].title)
        assertNull(tasks[0].remindAt)
    }

    @Test
    fun `noon resolves`() {
        val tasks = TaskExtractor.extract("Remind me to check the oven at noon.", friday9am)
        assertEquals(LocalDateTime.of(2026, 9, 25, 12, 0), tasks[0].remindAt)
    }
}
