package com.chronicle.app.mind

import org.junit.Assert.*
import org.junit.Test
import java.net.URL
import java.net.HttpURLConnection
import java.io.ByteArrayInputStream
import kotlinx.coroutines.test.runTest

class HfDownloadTest {
    @Test fun `abort interrupts a connection still waiting for response headers`() {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.Semaphore(0)
        val fetcher = HttpFetcher { url ->
            object : HttpURLConnection(url) {
                @Volatile var aborted = false
                override fun connect() {}
                override fun usingProxy() = false
                override fun disconnect() { aborted = true; release.release() }
                override fun getResponseCode(): Int {
                    started.countDown()
                    release.acquire()
                    if (aborted) throw java.io.IOException("closed")
                    return 200
                }
            }
        }
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Boolean> {
                runCatching { fetcher.open("https://huggingface.co/file", 0, emptyMap()) }.isFailure
            }
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
            fetcher.abort()
            assertTrue(result.get(2, java.util.concurrent.TimeUnit.SECONDS))
        } finally { release.release(); executor.shutdownNow() }
    }
    private class Connection(url: URL, val status: Int, val location: String? = null) : HttpURLConnection(url) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf(1, 2))
        override fun getHeaderField(name: String?) = if (name == "Location") location else null
        override fun getContentLengthLong() = 2L
    }

    @Test fun `HF gets Bearer but signed CDN does not and both get the Range`() {
        val hops = mutableListOf<Connection>()
        val fetcher = HttpFetcher { url ->
            Connection(url, if (hops.isEmpty()) 302 else 206,
                if (hops.isEmpty()) "https://cdn.example/model?signature=private" else null)
                .also { hops += it }
        }
        fetcher.open("https://huggingface.co/repo/resolve/main/model.task", 10,
            mapOf("Authorization" to "Bearer test")).close()
        assertEquals(2, hops.size)
        assertEquals("Bearer test", hops[0].getRequestProperty("Authorization"))
        assertNull(hops[1].getRequestProperty("Authorization"))
        assertEquals("bytes=10-", hops[0].getRequestProperty("Range"))
        assertEquals("bytes=10-", hops[1].getRequestProperty("Range"))
        assertTrue(hops.all { !it.instanceFollowRedirects })
    }

    @Test fun `redirect loops and HTTPS downgrades are refused`() {
        val loop = HttpFetcher { Connection(it, 302, "/same") }
        assertTrue(runCatching { loop.open("https://huggingface.co/same", 0, emptyMap()) }.isFailure)
        val downgrade = HttpFetcher { Connection(it, 302, "http://cdn.example/file") }
        assertTrue(runCatching { downgrade.open("https://huggingface.co/file", 0, emptyMap()) }.isFailure)
    }

    @Test fun `access watcher waits for licence then grants without following a redirect`() = runTest {
        val responses = ArrayDeque(listOf(HfResponse(401, "GatedRepo"), HfResponse(403, "GatedRepo"), HfResponse(302)))
        val watcher = HfAccessWatcher(HfHttp { method, url, bearer, form ->
            assertEquals("HEAD", method)
            assertTrue(url.startsWith("https://huggingface.co/"))
            assertEquals("test", bearer)
            assertTrue(form.isEmpty())
            responses.removeFirst()
        })
        val model = SmartModels.all[1]
        assertEquals(HfAccess.WAITING_FOR_LICENCE, watcher.check(model, "test"))
        assertEquals(HfAccess.WAITING_FOR_LICENCE, watcher.check(model, "test"))
        assertEquals(HfAccess.GRANTED, watcher.check(model, "test"))
    }

    @Test fun `token rejection is distinct from repo gating and server failure`() {
        assertEquals(HfAccess.TOKEN_REJECTED, classifyHfAccess(HfResponse(401)))
        assertEquals(HfAccess.FAILED, classifyHfAccess(HfResponse(500)))
        assertEquals(HfAccess.GRANTED, classifyHfAccess(HfResponse(200)))
    }
}
