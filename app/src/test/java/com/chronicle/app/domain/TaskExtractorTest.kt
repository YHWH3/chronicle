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

    @Test
    fun `dont let me forget`() {
        val tasks = TaskExtractor.extract("Don't let me forget my keys.", friday9am)
        assertEquals("My keys", tasks[0].title)
        assertNull(tasks[0].remindAt)
    }

    @Test
    fun `eod resolves to five pm and rolls when passed`() {
        val tasks = TaskExtractor.extract("Remind me to send the report by eod.", friday9am)
        assertEquals("Send the report", tasks[0].title)
        assertEquals(LocalDateTime.of(2026, 9, 25, 17, 0), tasks[0].remindAt)
        val late = LocalDateTime.of(2026, 9, 25, 18, 0)
        val rolled = TaskExtractor.extract("Remind me to send the report by eod.", late)
        assertEquals(LocalDateTime.of(2026, 9, 26, 17, 0), rolled[0].remindAt)
    }

    @Test
    fun `in minutes and days`() {
        assertEquals(
            LocalDateTime.of(2026, 9, 25, 9, 30),
            TaskExtractor.extract("Remind me to check the timer in 30 minutes.", friday9am)[0].remindAt,
        )
        assertEquals(
            LocalDateTime.of(2026, 9, 28, 9, 0),
            TaskExtractor.extract("Remind me to feed the cat in 3 days.", friday9am)[0].remindAt,
        )
    }

    @Test
    fun `in a week without digit gets no time`() {
        val tasks = TaskExtractor.extract("Remind me to call mum in a week.", friday9am)
        assertNull(tasks[0].remindAt)
    }

    @Test
    fun `bare hour above seven stays morning`() {
        // "at 8" reads as 08:00; at 09:00 it has passed, so it rolls to tomorrow.
        val tasks = TaskExtractor.extract("Remind me to stretch at 8.", friday9am)
        assertEquals(LocalDateTime.of(2026, 9, 26, 8, 0), tasks[0].remindAt)
    }

    @Test
    fun `twelve am and pm`() {
        assertEquals(
            LocalDateTime.of(2026, 9, 26, 0, 0),
            TaskExtractor.extract("Remind me to sleep at 12am.", friday9am)[0].remindAt,
        )
        assertEquals(
            LocalDateTime.of(2026, 9, 25, 12, 0),
            TaskExtractor.extract("Remind me to eat at 12pm.", friday9am)[0].remindAt,
        )
    }

    @Test
    fun `midnight rolls to tomorrow`() {
        val tasks = TaskExtractor.extract("Remind me to unplug the tree at midnight.", friday9am)
        assertEquals(LocalDateTime.of(2026, 9, 26, 0, 0), tasks[0].remindAt)
    }

    @Test
    fun `tomorrow morning and day after tomorrow`() {
        assertEquals(
            LocalDateTime.of(2026, 9, 26, 9, 0),
            TaskExtractor.extract("Remind me to run tomorrow morning.", friday9am)[0].remindAt,
        )
        assertEquals(
            LocalDateTime.of(2026, 9, 27, 9, 0),
            TaskExtractor.extract("Remind me to fly the day after tomorrow.", friday9am)[0].remindAt,
        )
    }

    @Test
    fun `next week resolves seven days out`() {
        assertEquals(
            LocalDateTime.of(2026, 10, 2, 9, 0),
            TaskExtractor.extract("Remind me to renew the domain next week.", friday9am)[0].remindAt,
        )
    }

    @Test
    fun `this afternoon resolves and rolls`() {
        assertEquals(
            LocalDateTime.of(2026, 9, 25, 15, 0),
            TaskExtractor.extract("Remind me to sit in the sun this afternoon.", friday9am)[0].remindAt,
        )
        val fourPm = LocalDateTime.of(2026, 9, 25, 16, 0)
        assertEquals(
            LocalDateTime.of(2026, 9, 26, 15, 0),
            TaskExtractor.extract("Remind me to sit in the sun this afternoon.", fourPm)[0].remindAt,
        )
    }

    @Test
    fun `duplicate task upgrades to the timed one`() {
        val tasks = TaskExtractor.extract(
            "Remind me to buy coffee. Remind me to buy coffee tomorrow.",
            friday9am,
        )
        assertEquals(1, tasks.size)
        assertEquals(LocalDateTime.of(2026, 9, 26, 9, 0), tasks[0].remindAt)
    }

    @Test
    fun `note to self and add to list`() {
        assertEquals(
            "Check the locks",
            TaskExtractor.extract("note to self: check the locks", friday9am)[0].title,
        )
        assertEquals(
            "Milk",
            TaskExtractor.extract("add milk to my todo list", friday9am)[0].title,
        )
    }

    @Test
    fun `i should and i must`() {
        assertEquals(
            "Call him back",
            TaskExtractor.extract("I should call him back.", friday9am)[0].title,
        )
        assertEquals(
            "Fix the door",
            TaskExtractor.extract("I must fix the door.", friday9am)[0].title,
        )
    }

    @Test
    fun `title under three chars is dropped`() {
        assertTrue(TaskExtractor.extract("Remind me to go.", friday9am).isEmpty())
    }

    @Test
    fun `on friday from friday picks next friday`() {
        assertEquals(
            LocalDateTime.of(2026, 10, 2, 9, 0),
            TaskExtractor.extract("Remind me to post the letter on Friday.", friday9am)[0].remindAt,
        )
    }

    @Test
    fun `today yields no reminder`() {
        assertNull(TaskExtractor.extract("Remind me to breathe today.", friday9am)[0].remindAt)
    }

    @Test
    fun `time phrase without intent is not a task`() {
        assertTrue(TaskExtractor.extract("The meeting is at 5pm.", friday9am).isEmpty())
    }

    // ————— BE-05: meridians must survive the sentence split —————

    @Test
    fun `dotted am and pm survive sentence splitting`() {
        // Splitting on "." destroys "a.m." — it must be normalized before the split.
        assertEquals(
            LocalDateTime.of(2026, 9, 26, 7, 0),
            TaskExtractor.extract("Remind me to stretch at 7 a.m.", friday9am)[0].remindAt,
        )
        assertEquals(
            LocalDateTime.of(2026, 9, 25, 20, 0),
            TaskExtractor.extract("Remind me to stretch at 8 p.m.", friday9am)[0].remindAt,
        )
        assertEquals(
            LocalDateTime.of(2026, 9, 26, 7, 0),
            TaskExtractor.extract("Remind me to stretch at 7am.", friday9am)[0].remindAt,
        )
        assertEquals(
            LocalDateTime.of(2026, 9, 25, 20, 0),
            TaskExtractor.extract("Remind me to stretch at 8pm.", friday9am)[0].remindAt,
        )
    }

    @Test
    fun `decimal time with meridian survives sentence splitting`() {
        // "5.30pm" → normalized to "5:30pm" before the transcript is split.
        assertEquals(
            LocalDateTime.of(2026, 9, 25, 17, 30),
            TaskExtractor.extract("Remind me to leave at 5.30pm.", friday9am)[0].remindAt,
        )
    }
}
