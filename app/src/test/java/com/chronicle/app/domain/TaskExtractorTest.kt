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

    // ————— BE-06: day-part phrases constrain bare clock hours —————

    @Test
    fun `morning keeps a bare hour in am`() {
        // "at 7" alone reads as 19:00 — "morning" overrules that heuristic.
        assertEquals(
            LocalDateTime.of(2026, 9, 26, 7, 0),
            TaskExtractor.extract("Remind me to run tomorrow morning at 7.", friday9am)[0].remindAt,
        )
        assertEquals(
            LocalDateTime.of(2026, 9, 25, 8, 0),
            TaskExtractor.extract("Remind me to stretch at 8 in the morning.", LocalDateTime.of(2026, 9, 25, 6, 0))[0].remindAt,
        )
    }

    @Test
    fun `afternoon pushes a bare hour into pm`() {
        assertEquals(
            LocalDateTime.of(2026, 9, 26, 15, 0),
            TaskExtractor.extract("Remind me to nap tomorrow afternoon at 3.", friday9am)[0].remindAt,
        )
    }

    @Test
    fun `evening and night push bare hours into the night range`() {
        assertEquals(
            LocalDateTime.of(2026, 9, 26, 18, 0),
            TaskExtractor.extract("Remind me to eat tomorrow evening at 6.", friday9am)[0].remindAt,
        )
        // Without "tonight" this hour would stay 10:00 AM.
        assertEquals(
            LocalDateTime.of(2026, 9, 25, 22, 0),
            TaskExtractor.extract("Remind me to lock up tonight at 10.", friday9am)[0].remindAt,
        )
        assertEquals(
            LocalDateTime.of(2026, 9, 25, 21, 0),
            TaskExtractor.extract("Remind me to call her tonight at 9.", friday9am)[0].remindAt,
        )
    }

    @Test
    fun `tonight at a past hour is flagged ambiguous instead of rolled`() {
        // "tonight" can't roll into tomorrow — the speaker's slot is unclear.
        val late = LocalDateTime.of(2026, 9, 25, 21, 30)
        val task = TaskExtractor.extract("Remind me to call her tonight at 9.", late)[0]
        assertNull(task.remindAt)
        assertTrue(task.timeAmbiguous)
    }

    @Test
    fun `today and this-afternoon past hours are ambiguous`() {
        assertTrue(
            TaskExtractor.extract(
                "Remind me to call her today at 8.",
                friday9am,
            )[0].timeAmbiguous,
        )
        val fourPm = LocalDateTime.of(2026, 9, 25, 16, 0)
        val task = TaskExtractor.extract(
            "Remind me to sit in the sun this afternoon at 3.",
            fourPm,
        )[0]
        assertNull(task.remindAt)
        assertTrue(task.timeAmbiguous)
    }

    @Test
    fun `past hours without a same-day anchor still roll`() {
        // No "today"/"tonight" — free to roll into tomorrow.
        val task = TaskExtractor.extract("Remind me to stretch at 7am.", friday9am)[0]
        assertEquals(LocalDateTime.of(2026, 9, 26, 7, 0), task.remindAt)
        assertTrue(!task.timeAmbiguous)
    }

    @Test
    fun `bare hour keeps the one-to-seven pm rule with no day-part`() {
        // No morning/evening context → the existing 1..7 → PM heuristic stands.
        assertEquals(
            LocalDateTime.of(2026, 9, 25, 19, 0),
            TaskExtractor.extract("Remind me to stretch at 7.", friday9am)[0].remindAt,
        )
        assertEquals(
            LocalDateTime.of(2026, 9, 25, 10, 0),
            TaskExtractor.extract("Remind me to stretch at 10.", LocalDateTime.of(2026, 9, 25, 8, 0))[0].remindAt,
        )
    }

    // ————— BE-09: richer natural time phrases —————

    @Test
    fun `worded durations resolve`() {
        assertEquals(
            LocalDateTime.of(2026, 9, 25, 10, 0),
            TaskExtractor.extract("Remind me to check the oven in an hour.", friday9am)[0].remindAt,
        )
        assertEquals(
            LocalDateTime.of(2026, 9, 25, 9, 30),
            TaskExtractor.extract("Remind me to stretch in half an hour.", friday9am)[0].remindAt,
        )
        assertEquals(
            LocalDateTime.of(2026, 9, 25, 9, 1),
            TaskExtractor.extract("Remind me to breathe in a minute.", friday9am)[0].remindAt,
        )
    }

    @Test
    fun `abbreviated duration units resolve`() {
        assertEquals(
            LocalDateTime.of(2026, 9, 25, 9, 5),
            TaskExtractor.extract("Remind me to check the timer in 5 mins.", friday9am)[0].remindAt,
        )
        assertEquals(
            LocalDateTime.of(2026, 9, 25, 9, 5),
            TaskExtractor.extract("Remind me to check the timer in 5 min.", friday9am)[0].remindAt,
        )
        assertEquals(
            LocalDateTime.of(2026, 9, 25, 11, 0),
            TaskExtractor.extract("Remind me to leave in 2 hrs.", friday9am)[0].remindAt,
        )
        assertEquals(
            LocalDateTime.of(2026, 9, 25, 10, 0),
            TaskExtractor.extract("Remind me to leave in 1 hr.", friday9am)[0].remindAt,
        )
    }

    @Test
    fun `bare friday and this friday pick the coming friday`() {
        val thursday = LocalDateTime.of(2026, 9, 24, 9, 0)
        assertEquals(
            LocalDateTime.of(2026, 9, 25, 9, 0),
            TaskExtractor.extract("Remind me to post the letter this Friday.", thursday)[0].remindAt,
        )
        assertEquals(
            LocalDateTime.of(2026, 9, 25, 9, 0),
            TaskExtractor.extract("Remind me to post the letter Friday.", thursday)[0].remindAt,
        )
    }

    @Test
    fun `weekday today still anchors then rolls a week when passed`() {
        // Said early in the day, "this Friday" can mean today.
        val friday8am = LocalDateTime.of(2026, 9, 25, 8, 0)
        assertEquals(
            LocalDateTime.of(2026, 9, 25, 9, 0),
            TaskExtractor.extract("Remind me to post the letter this Friday.", friday8am)[0].remindAt,
        )
        // At 9am the implied slot has passed — roll a full week, never to Saturday.
        assertEquals(
            LocalDateTime.of(2026, 10, 2, 9, 0),
            TaskExtractor.extract("Remind me to post the letter on Friday.", friday9am)[0].remindAt,
        )
    }

    @Test
    fun `next friday is next week's`() {
        val thursday = LocalDateTime.of(2026, 9, 24, 9, 0)
        assertEquals(
            LocalDateTime.of(2026, 10, 2, 9, 0),
            TaskExtractor.extract("Remind me to post the letter next Friday.", thursday)[0].remindAt,
        )
        assertEquals(
            LocalDateTime.of(2026, 10, 2, 9, 0),
            TaskExtractor.extract("Remind me to post the letter next Friday.", friday9am)[0].remindAt,
        )
    }

    @Test
    fun `bare meridian time resolves without at`() {
        assertEquals(
            LocalDateTime.of(2026, 9, 26, 17, 0),
            TaskExtractor.extract("Remind me to call Sam tomorrow 5pm.", friday9am)[0].remindAt,
        )
        assertEquals(
            LocalDateTime.of(2026, 9, 26, 7, 0),
            TaskExtractor.extract("Remind me to stretch, 7am.", friday9am)[0].remindAt,
        )
    }

    // ————— BE-07: several intents in one sentence —————

    @Test
    fun `two intents in one sentence yield two tasks`() {
        val tasks = TaskExtractor.extract(
            "I need to call Sam tomorrow at 5 and I should buy milk.",
            friday9am,
        )
        assertEquals(2, tasks.size)
        assertEquals("Call Sam", tasks[0].title)
        assertEquals(LocalDateTime.of(2026, 9, 26, 17, 0), tasks[0].remindAt)
        assertEquals("Buy milk", tasks[1].title)
        assertNull(tasks[1].remindAt)
    }

    @Test
    fun `then but because bound a capture`() {
        val tasks = TaskExtractor.extract(
            "I need to finish the slides then I should call mum.",
            friday9am,
        )
        assertEquals(2, tasks.size)
        assertEquals("Finish the slides", tasks[0].title)
        assertEquals("Call mum", tasks[1].title)

        val single = TaskExtractor.extract(
            "Remind me to buy milk because we are out.",
            friday9am,
        )
        assertEquals("Buy milk", single[0].title)

        val but = TaskExtractor.extract(
            "Remind me to email her but not before lunch.",
            friday9am,
        )
        assertEquals("Email her", but[0].title)
    }

    @Test
    fun `second intent does not steal the first intents time`() {
        val tasks = TaskExtractor.extract(
            "Remind me to call Sam tomorrow and I need to buy milk.",
            friday9am,
        )
        assertEquals(2, tasks.size)
        assertEquals(LocalDateTime.of(2026, 9, 26, 9, 0), tasks[0].remindAt)
        assertNull(tasks[1].remindAt)
    }

    @Test
    fun `long captures are capped near eight words`() {
        val tasks = TaskExtractor.extract(
            "I need to pick up the dry cleaning from the shop on the corner near the bank before they close.",
            friday9am,
        )
        assertTrue(tasks[0].title.split(" ").size <= 8)
        assertTrue(tasks[0].title.length <= 60)
        assertEquals("Pick up the dry cleaning from the shop", tasks[0].title)
    }

    // ————— BE-08: apostrophes and new intent forms —————

    @Test
    fun `curly apostrophes are normalized`() {
        val tasks = TaskExtractor.extract("I’ve got to call Sam.", friday9am)
        assertEquals("Call Sam", tasks[0].title)
        assertEquals(
            "My keys",
            TaskExtractor.extract("Don’t let me forget my keys.", friday9am)[0].title,
        )
    }

    @Test
    fun `new intent forms extract`() {
        assertEquals(
            "Finish the report",
            TaskExtractor.extract("I've got to finish the report.", friday9am)[0].title,
        )
        assertEquals(
            "Finish the report",
            TaskExtractor.extract("I'm supposed to finish the report.", friday9am)[0].title,
        )
        assertEquals(
            "Fix the bike",
            TaskExtractor.extract("i gotta fix the bike.", friday9am)[0].title,
        )
    }

    // ————— BE-10: weak intents are suggestions; modal-past is excluded —————

    @Test
    fun `weak intents are flagged suggested`() {
        assertTrue(
            TaskExtractor.extract("I should call him back.", friday9am)[0].suggested,
        )
        assertTrue(
            TaskExtractor.extract("I must fix the door.", friday9am)[0].suggested,
        )
        assertTrue(
            TaskExtractor.extract("I have to pay the rent.", friday9am)[0].suggested,
        )
    }

    @Test
    fun `strong intents stay firm`() {
        assertTrue(
            !TaskExtractor.extract("Remind me to call him back.", friday9am)[0].suggested,
        )
        assertTrue(
            !TaskExtractor.extract("I need to fix the door.", friday9am)[0].suggested,
        )
        assertTrue(
            !TaskExtractor.extract("I've got to pay the rent.", friday9am)[0].suggested,
        )
        assertTrue(
            !TaskExtractor.extract("i gotta fix the bike.", friday9am)[0].suggested,
        )
    }

    @Test
    fun `modal perfect and past obligations are excluded`() {
        assertTrue(
            TaskExtractor.extract("I should have called her.", friday9am).isEmpty(),
        )
        assertTrue(
            TaskExtractor.extract("I must have left it at work.", friday9am).isEmpty(),
        )
        assertTrue(
            TaskExtractor.extract("I had to work late.", friday9am).isEmpty(),
        )
        assertTrue(
            TaskExtractor.extract("I needed to sleep.", friday9am).isEmpty(),
        )
        assertTrue(
            TaskExtractor.extract("I should’ve called her.", friday9am).isEmpty(),
        )
    }

    @Test
    fun `should have a meeting is still a task`() {
        // "have" here is possession, not a perfect auxiliary.
        val tasks = TaskExtractor.extract("I should have a meeting with Sam.", friday9am)
        assertEquals(1, tasks.size)
        assertEquals("Have a meeting with Sam", tasks[0].title)
    }
}
