package com.chronicle.app.ui.vm

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class TodayRolloverTest {

    @Test
    fun `afternoon time waits until next midnight`() {
        val now = LocalDateTime.of(2026, 9, 27, 15, 30, 0)
        // 15:30 → 24:00 = 8h30m
        assertEquals(8L * 3600_000 + 30L * 60_000, millisUntilNextMidnight(now))
    }

    @Test
    fun `half a second before midnight waits half a second`() {
        val now = LocalDateTime.of(2026, 9, 27, 23, 59, 59, 500_000_000)
        assertEquals(500L, millisUntilNextMidnight(now))
    }

    @Test
    fun `exactly midnight waits a full day`() {
        val now = LocalDateTime.of(2026, 9, 27, 0, 0, 0)
        assertEquals(24L * 3600_000, millisUntilNextMidnight(now))
    }
}
