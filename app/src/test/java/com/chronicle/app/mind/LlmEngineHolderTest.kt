package com.chronicle.app.mind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BE-19 — the holder caches one engine per model path, never rebuilds while
 * the key is stable, and actually frees the native wrapper on release.
 */
class LlmEngineHolderTest {
    @Test fun `only a change inside the cached pack releases it`() {
        val holder = LlmEngineHolder { path -> FakeEngine(path) }
        val engine = holder.acquire("/models/llm/qwen-other/model.task")
        holder.releaseIfDirectory("/models/llm/qwen")
        org.junit.Assert.assertFalse(engine.closed)
        assertSame(engine, holder.acquire(engine.path))
        holder.releaseIfDirectory("/models/llm/qwen-other")
        assertTrue(engine.closed)
    }

    private class FakeEngine(val path: String) : ReleasableEngine {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    private class Counter {
        var created = 0
        val made = mutableListOf<FakeEngine>()
        fun make(path: String): FakeEngine = FakeEngine(path).also {
            created++
            made += it
        }
    }

    @Test
    fun `same path returns the same cached engine`() {
        val c = Counter()
        val holder = LlmEngineHolder { path -> c.make(path) }
        val a = holder.acquire("/models/llama.task")
        val b = holder.acquire("/models/llama.task")
        assertSame(a, b)
        assertEquals(1, c.created)
    }

    @Test
    fun `a new path releases the stale engine and builds once`() {
        val c = Counter()
        val holder = LlmEngineHolder { path -> c.make(path) }
        val first = holder.acquire("/models/a.task")
        val second = holder.acquire("/models/b.task")
        assertNotSame(first, second)
        assertTrue(first.closed)
        assertEquals(2, c.created)
    }

    @Test
    fun `release closes the engine and the next acquire rebuilds`() {
        val c = Counter()
        val holder = LlmEngineHolder { path -> c.make(path) }
        val first = holder.acquire("/models/a.task")
        holder.release()
        assertTrue(first.closed)
        val second = holder.acquire("/models/a.task")
        assertNotSame(first, second)
        assertEquals(2, c.created)
    }

    @Test
    fun `release on an empty holder is a no-op`() {
        val holder = LlmEngineHolder { path -> FakeEngine(path) }
        holder.release() // must not throw
        holder.release()
    }
}
