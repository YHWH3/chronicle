package com.chronicle.app.mind

import com.chronicle.app.domain.TaskExtractor
import java.time.LocalDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SmartMergeTest {
    private val now = LocalDateTime.of(2026, 10, 4, 16, 43)
    @Test fun `explicit reminders omitted by the model are added back`() {
        val rules = TaskExtractor.extract("remind me to call mum tomorrow at 5. I need to buy milk.", now)
        val merged = MindRepository.mergeTasks(MindResult(emptyList()), rules, now)
        assertEquals(listOf("Call mum"), merged.tasks.map { it.title })
        assertEquals(now.toLocalDate().plusDays(1).atTime(17, 0), merged.tasks.single().remindAt)
    }
    @Test fun `model title and future time win and out of range dates are re-resolved`() {
        val result = MindRepository.mergeTasks(MindResult(listOf(
            MindTask("Call mum", now.plusHours(2)), MindTask("Water plants tomorrow at 5", now.minusDays(1)),
            MindTask("Buy milk", now.plusDays(365)),
        )), emptyList(), now)
        assertEquals(now.plusHours(2), result.tasks[0].remindAt)
        assertEquals(now.toLocalDate().plusDays(1).atTime(17, 0), result.tasks[1].remindAt)
        assertNull(result.tasks[2].remindAt)
    }
    @Test fun `generation timeout falls back but load time is excluded`() = runTest {
        val engine = object : MindEngine {
            override val label = "slow"
            override suspend fun prepare() { delay(15_000) }
            override suspend fun extract(text: String, now: LocalDateTime): MindResult {
                delay(11_000)
                return MindResult(emptyList())
            }
        }
        val execution = MindRepository.extractWithFallback(engine, "remind me to call mum", now)
        assertEquals(DeterministicMind.label, execution.actualLabel)
        assertEquals("Call mum", execution.result.tasks.single().title)
        assertEquals("Smart to-dos took too long", execution.fallbackReason)
    }
}
