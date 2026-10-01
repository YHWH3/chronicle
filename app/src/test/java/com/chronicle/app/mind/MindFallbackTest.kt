package com.chronicle.app.mind

import java.io.IOException
import java.time.LocalDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers MindRepository.extractWithFallback — the configured-vs-actual label
 * reporting (BE-20) and cancellation propagation (XC-01).
 */
class MindFallbackTest {

    private val now = LocalDateTime.of(2026, 9, 27, 15, 30)

    private fun fakeEngine(
        label: String,
        block: suspend () -> MindResult,
    ): MindEngine = object : MindEngine {
        override val label = label
        override suspend fun extract(text: String, now: LocalDateTime): MindResult = block()
    }

    @Test
    fun `successful engine reports itself as actual`() = runTest {
        val engine = fakeEngine("Cloud mind") {
            MindResult(tasks = listOf(MindTask("call Sam", null)), reflection = "Busy day.")
        }
        val exec = MindRepository.extractWithFallback(engine, "call Sam", now)
        assertEquals("Cloud mind", exec.configuredLabel)
        assertEquals("Cloud mind", exec.actualLabel)
        assertNull(exec.fallbackReason)
        assertEquals(1, exec.result.tasks.size)
        assertEquals("Busy day.", exec.result.reflection)
    }

    @Test
    fun `failure falls back and reports the quiet parser as actual`() = runTest {
        val engine = fakeEngine("OpenRouter · llama") {
            throw IOException("connect timed out after 15s\nat java.net.Socket...")
        }
        val exec = MindRepository.extractWithFallback(
            engine,
            "remind me to call Sam tomorrow",
            now,
        )
        assertEquals("OpenRouter · llama", exec.configuredLabel)
        assertEquals(DeterministicMind.label, exec.actualLabel)
        assertEquals("connect timed out after 15s", exec.fallbackReason)
        // The deterministic parser still found the intent — words not lost.
        assertTrue(exec.result.tasks.isNotEmpty())
    }

    @Test
    fun `failure with empty message reports the exception class`() = runTest {
        val engine = fakeEngine("On-device") { throw IOException() }
        val exec = MindRepository.extractWithFallback(engine, "note", now)
        assertEquals("IOException", exec.fallbackReason)
        assertEquals(DeterministicMind.label, exec.actualLabel)
    }

    @Test
    fun `cancellation propagates through the fallback wrapper`() = runTest {
        val engine = fakeEngine("On-device") {
            throw CancellationException("VM cleared")
        }
        try {
            MindRepository.extractWithFallback(engine, "anything", now)
            error("expected CancellationException")
        } catch (ce: CancellationException) {
            assertEquals("VM cleared", ce.message)
        }
    }
}
