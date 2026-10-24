package com.chronicle.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class TaskExtractorGoldenTest {
    private val now = LocalDateTime.of(2026, 10, 4, 16, 43)
    private fun check(input: String, vararg expected: ExtractedTask) {
        fun normal(title: String) = title.lowercase().replace(Regex("\\s+"), " ").trim()
        val actual = TaskExtractor.extract(input, now)
        assertEquals(input, expected.map { normal(it.title) }, actual.map { normal(it.title) })
        assertEquals(input, expected.map { it.remindAt }, actual.map { it.remindAt })
        assertEquals(input, expected.map { it.suggested }, actual.map { it.suggested })
    }
    private fun task(title: String, at: String? = null, suggested: Boolean = false) =
        ExtractedTask(title, at?.let(LocalDateTime::parse), suggested = suggested)

    @Test fun G1() = check("remind me to call mum tomorrow at 5", task("Call mum", "2026-10-05T17:00"))
    @Test fun G2() = check("I need to actually do some tasks , mainly to go to the bank and pick up the parcel",
        task("Go to the bank"), task("Pick up the parcel"))
    @Test fun G3() = check("I need to buy milk , eggs and bread", task("Buy milk, eggs and bread"))
    @Test fun G4() = check("um so basically I gotta email Sarah about the invoice by Friday",
        task("Email Sarah about the invoice", "2026-10-09T09:00"))
    @Test fun G5() = check("I should have called her yesterday")
    @Test fun G6() = check("I should probably call the dentist", task("Call the dentist", suggested = true))
    @Test fun G7() = check("walked the coast road, cold wind")
    @Test fun G8() = check("remind me to stretch tonight at 9", task("Stretch", "2026-10-04T21:00"))
    @Test fun G9() = check("remind me in half an hour to take the pizza out", task("Take the pizza out", "2026-10-04T17:13"))
    @Test fun G10() = check("don't let me forget to renew my passport next week", task("Renew my passport", "2026-10-11T09:00"))
    @Test fun G11() = check("I'm supposed to meet Raj for lunch on Tuesday at 1", task("Meet Raj for lunch", "2026-10-06T13:00"))
    @Test fun G12() = check("I need to pay rent and then call the landlord about the leak", task("Pay rent"), task("Call the landlord about the leak"))
    @Test fun G13() = check("the meeting went well, I need to send the notes to the team tomorrow morning",
        task("Send the notes to the team", "2026-10-05T09:00"))
    @Test fun G14() = check("can you remind me at 7am to go for a run", task("Go for a run", "2026-10-05T07:00"))
    @Test fun G15() = check("note to self: back up the laptop", task("Back up the laptop"))
    @Test fun G16() = check("I need to call the bank at 10:30 tomorrow", task("Call the bank", "2026-10-05T10:30"))
    @Test fun G17() = check("remind me to text Ana in 2 hours and to water the plants tonight",
        task("Text Ana", "2026-10-04T18:43"), task("Water the plants", "2026-10-04T20:00"))
    @Test fun G18() = check("remind me to call john tomorrow at 3 I also need to book the car service",
        task("Call john", "2026-10-05T15:00"), task("Book the car service"))
    @Test fun G19() = check("I really need to sort out my taxes this week", task("Sort out my taxes"))
    @Test fun G20() = check("uh um")
    @Test fun G21() = check("I need to go to the gym, like, three times this week", task("Go to the gym"))
    @Test fun G22() = check("remind me to call mum tomorrow at 5 a.m.", task("Call mum", "2026-10-05T05:00"))
}
