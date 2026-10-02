package com.chronicle.app.mind

import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Covers ChatMind without touching the network — the request shape
 * (BE-21 guards: no empty Bearer header, capped transcript) plus the
 * timeout/cancellation contract via a fake [ChatMind.BlockingCall].
 */
class ChatMindTest {

    private val now = LocalDateTime.of(2026, 9, 27, 15, 30)

    private fun mind(
        timeoutMs: Long = ChatMind.USER_FACING_TIMEOUT_MS,
        newCall: (ChatMind.RequestSpec) -> ChatMind.BlockingCall,
    ) = ChatMind(
        label = "Test mind",
        baseUrl = "https://example.test/v1",
        apiKey = "sk-test",
        model = "test-model",
        timeoutMs = timeoutMs,
        newCall = newCall,
    )

    private fun reply(code: Int, body: String) = object : ChatMind.BlockingCall {
        override fun run() = ChatMind.CloudReply(code, body)
        override fun cancel() {}
    }

    /** Blocks until [cancelled] flips — the fake analogue of a hung socket read. */
    private fun stuckCall(cancelled: AtomicBoolean) = object : ChatMind.BlockingCall {
        override fun run(): ChatMind.CloudReply {
            while (!cancelled.get()) Thread.sleep(5)
            return ChatMind.CloudReply(200, "{}")
        }

        override fun cancel() = cancelled.set(true)
    }

    private suspend inline fun <reified T : Throwable> expectFails(
        crossinline block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (t: Throwable) {
            if (t is T) return t
            fail("expected ${T::class.simpleName}, got $t")
        }
        fail("expected ${T::class.simpleName}, nothing was thrown")
        throw AssertionError("unreachable")
    }

    // ————— Request shape —————

    @Test
    fun `blank api key sends no authorization header`() {
        val m = ChatMind("T", "https://api.example.com/v1", apiKey = "   ", model = "m")
        val spec = m.requestSpec("hello", now)
        assertFalse(spec.headers.containsKey("Authorization"))
    }

    @Test
    fun `configured api key is sent as bearer`() {
        val m = ChatMind("T", "https://api.example.com/v1/", apiKey = "sk-abc", model = "m")
        val spec = m.requestSpec("hello", now)
        assertEquals("Bearer sk-abc", spec.headers["Authorization"])
        // A trailing slash on the base URL doesn't double up.
        assertEquals("https://api.example.com/v1/chat/completions", spec.url)
    }

    @Test
    fun `openrouter sends referer headers`() {
        val spec = ChatMind.openRouter("sk-or-x", "vendor/model:free").requestSpec("hi", now)
        assertEquals("https://openrouter.ai/api/v1/chat/completions", spec.url)
        assertEquals("https://chronicle.app", spec.headers["HTTP-Referer"])
        assertEquals("Chronicle", spec.headers["X-Title"])
    }

    @Test
    fun `long transcripts are capped`() {
        val m = ChatMind("T", "https://example.test/v1", "k", "m")
        val spec = m.requestSpec("a".repeat(50_000), now)
        val user = JSONObject(spec.body)
            .getJSONArray("messages").getJSONObject(1).getString("content")
        assertEquals(ChatMind.MAX_TRANSCRIPT_CHARS, user.substringAfter("Capture:\n").length)
    }

    // ————— Failure reasons —————

    @Test
    fun `http failure reports the code and a capped one-line body`() = runTest {
        val m = mind { reply(429, "rate limited\nsecond line " + "x".repeat(500)) }
        val t = expectFails<IllegalStateException> { m.extract("note", now) }
        assertTrue(t.message!!.startsWith("HTTP 429:"))
        assertFalse(t.message!!.contains("\n"))
        assertTrue(t.message!!.length <= "HTTP 429: ".length + ChatMind.MAX_ERROR_BODY_CHARS)
    }

    @Test
    fun `unparseable reply is a distinct failure`() = runTest {
        val m = mind {
            reply(200, """{"choices":[{"message":{"content":"no json here"}}]}""")
        }
        val t = expectFails<IllegalStateException> { m.extract("note", now) }
        assertTrue(t.message!!.startsWith("Unparseable reply:"))
    }

    @Test
    fun `successful reply parses into a result`() = runTest {
        val inner =
            """{"tasks":[{"title":"Call Sam","remindAt":null}],"reflection":"A full day."}"""
        val body = """{"choices":[{"message":{"content":${JSONObject.quote(inner)}}}]}"""
        val result = mind { reply(200, body) }.extract("note", now)
        assertEquals(listOf(MindTask("Call Sam", null)), result.tasks)
        assertEquals("A full day.", result.reflection)
    }

    // ————— Timeout + cancellation —————

    @Test
    fun `the cap turns a stuck call into a timed-out failure`() = runTest {
        val cancelled = AtomicBoolean(false)
        val m = mind(timeoutMs = 60) { stuckCall(cancelled) }
        val t = expectFails<IllegalStateException> { m.extract("hi", now) }
        assertTrue(t.message!!.contains("timed out"))
        assertTrue("cancel() must disconnect the stuck call", cancelled.get())
    }

    @Test
    fun `caller cancellation propagates as CancellationException`() = runTest {
        val cancelled = AtomicBoolean(false)
        val m = mind { stuckCall(cancelled) }
        val outcome = CompletableDeferred<Throwable?>()
        val job = launch(Dispatchers.IO) {
            try {
                m.extract("hi", now)
                outcome.complete(null)
            } catch (t: Throwable) {
                outcome.complete(t)
            }
        }
        withContext(Dispatchers.IO) { Thread.sleep(150) } // let the call get stuck first
        job.cancel()
        job.join()
        val t = outcome.await()
        assertTrue("expected CancellationException, got $t", t is CancellationException)
        assertTrue(cancelled.get())
    }
}
