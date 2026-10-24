package com.chronicle.app.mind

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Install pipeline correctness on the JVM — real files, fake network.
 * Covers BE-23: .part resume math (206/200/416), part-age restart,
 * zip-slip traversal guard, validation rules, space preflight, cancel
 * keeping the partial download, and checksum verification.
 */
class ModelInstallManagerTest {

    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var store: ModelStore
    private var clock = System.currentTimeMillis()
    private var freeSpace = Long.MAX_VALUE
    private var sha256For: (String) -> String? = { null }

    @After
    fun tearDown() = scope.cancel()

    /** Fake transport — records the requested range and serves canned bytes. */
    private class FakeFetcher(
        private val data: ByteArray,
        private val supportRange: Boolean = true,
        private val streamFor: (ByteArray) -> InputStream = { ByteArrayInputStream(it) },
    ) : Fetcher {
        val rangeRequests = mutableListOf<Long>()

        override fun open(url: String, rangeStart: Long, headers: Map<String, String>): FetchResponse {
            rangeRequests += rangeStart
            val body = when {
                rangeStart > 0 && supportRange && rangeStart < data.size ->
                    data.copyOfRange(rangeStart.toInt(), data.size)
                else -> data
            }
            val code = when {
                !supportRange -> 200
                rangeStart >= data.size && rangeStart > 0 -> 416
                rangeStart > 0 -> 206
                else -> 200
            }
            return FetchResponse(
                code = code,
                totalBytes = data.size.toLong(),
                stream = if (code == 416) null else streamFor(body),
                abort = {},
            )
        }
    }

    private var fetcher: Fetcher = FakeFetcher(ByteArray(0))

    /** Casts [fetcher] to the fake for range assertions — only valid when one is installed. */
    private fun fakeRanges(): List<Long> = (fetcher as FakeFetcher).rangeRequests

    private fun newManager(): ModelInstallManager {
        store = ModelStore(tmp.newFolder("models"))
        return ModelInstallManager(
            store = store,
            scope = scope,
            newFetcher = { fetcher },
            freeSpaceBytes = { freeSpace },
            nowMs = { clock },
            warn = {},
            sha256For = sha256For,
        )
    }

    /** Poll the manager's states until [pred] holds; returns the state. */
    private fun ModelInstallManager.waitFor(
        kind: ModelKind,
        pred: (ModelState) -> Boolean,
    ): ModelState = runBlocking {
        withTimeout(10_000) { states.first { pred(it[kind.packId]!!) } }[kind.packId]!!
    }

    private fun tarGz(entries: List<Pair<String, ByteArray>>): ByteArray {
        val bos = ByteArrayOutputStream()
        TarArchiveOutputStream(GzipCompressorOutputStream(bos)).use { tar ->
            for ((name, bytes) in entries) {
                val e = TarArchiveEntry(name)
                e.size = bytes.size.toLong()
                tar.putArchiveEntry(e)
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun partFile(mgr: ModelInstallManager, kind: ModelKind): File =
        File(store.root, store.dirName(kind) + ".part")

    // ————— Tests —————

    @Test
    fun `blank url fails with guidance instead of a download attempt`() {
        val mgr = newManager()
        mgr.install(ModelKind.LOCAL_LLM, "")
        val s = mgr.waitFor(ModelKind.LOCAL_LLM) { it is ModelState.Failed }
        assertTrue((s as ModelState.Failed).reason.contains("URL"))
        assertTrue(fakeRanges().isEmpty())
    }

    @Test
    fun `fresh install of a plain model file lands and gets marked`() {
        val bytes = ByteArray(100_000) { it.toByte() }
        fetcher = FakeFetcher(bytes)
        val mgr = newManager()

        mgr.install(ModelKind.LOCAL_LLM, "https://x.test/llama.task")
        assertEquals(ModelState.Installed, mgr.waitFor(ModelKind.LOCAL_LLM) {
            it is ModelState.Installed || it is ModelState.Failed
        })

        val dir = store.installedDir(ModelKind.LOCAL_LLM)!!
        assertTrue(File(dir, ".installed").exists())
        val landed = dir.walkTopDown().first { it.name == "llama.task" }
        assertArrayEquals(bytes, landed.readBytes())
        assertFalse(partFile(mgr, ModelKind.LOCAL_LLM).exists())
        assertEquals(listOf(0L), fakeRanges())
    }

    @Test
    fun `fresh part file resumes with a Range header and appends`() {
        val bytes = ByteArray(200_000) { (it * 7).toByte() }
        fetcher = FakeFetcher(bytes)
        val mgr = newManager()
        store.root.mkdirs()
        // Simulate an interrupted download: first 80 KB already on disk, fresh.
        partFile(mgr, ModelKind.LOCAL_LLM).writeBytes(bytes.copyOf(80_000))

        mgr.install(ModelKind.LOCAL_LLM, "https://x.test/llama.task")
        assertEquals(ModelState.Installed, mgr.waitFor(ModelKind.LOCAL_LLM) {
            it is ModelState.Installed || it is ModelState.Failed
        })
        assertEquals(listOf(80_000L), fakeRanges())
        val landed = store.dir(ModelKind.LOCAL_LLM).walkTopDown()
            .first { it.name == "llama.task" }
        assertArrayEquals(bytes, landed.readBytes())
    }

    @Test
    fun `a server that ignores Range restarts the download`() {
        val bytes = ByteArray(120_000) { it.toByte() }
        fetcher = FakeFetcher(bytes, supportRange = false)
        val mgr = newManager()
        store.root.mkdirs()
        partFile(mgr, ModelKind.LOCAL_LLM).writeBytes(bytes.copyOf(50_000))

        mgr.install(ModelKind.LOCAL_LLM, "https://x.test/llama.task")
        assertEquals(ModelState.Installed, mgr.waitFor(ModelKind.LOCAL_LLM) {
            it is ModelState.Installed || it is ModelState.Failed
        })
        val landed = store.dir(ModelKind.LOCAL_LLM).walkTopDown()
            .first { it.name == "llama.task" }
        assertArrayEquals(bytes, landed.readBytes())
    }

    @Test
    fun `a day-old part file is not trusted and restarts`() {
        val bytes = ByteArray(90_000) { it.toByte() }
        fetcher = FakeFetcher(bytes)
        val mgr = newManager()
        store.root.mkdirs()
        val part = partFile(mgr, ModelKind.LOCAL_LLM)
        part.writeBytes(bytes.copyOf(50_000))
        part.setLastModified(clock - 48L * 60 * 60 * 1000)

        mgr.install(ModelKind.LOCAL_LLM, "https://x.test/llama.task")
        mgr.waitFor(ModelKind.LOCAL_LLM) { it is ModelState.Installed || it is ModelState.Failed }
        assertEquals(listOf(0L), fakeRanges())
    }

    @Test
    fun `416 means the part is already complete`() {
        val bytes = ByteArray(60_000) { it.toByte() }
        fetcher = FakeFetcher(bytes)
        val mgr = newManager()
        store.root.mkdirs()
        partFile(mgr, ModelKind.LOCAL_LLM).writeBytes(bytes)

        mgr.install(ModelKind.LOCAL_LLM, "https://x.test/llama.task")
        assertEquals(ModelState.Installed, mgr.waitFor(ModelKind.LOCAL_LLM) {
            it is ModelState.Installed || it is ModelState.Failed
        })
        val landed = store.dir(ModelKind.LOCAL_LLM).walkTopDown()
            .first { it.name == "llama.task" }
        assertArrayEquals(bytes, landed.readBytes())
    }

    @Test
    fun `zip-slip entries are rejected and nothing escapes the pack dir`() {
        val archive = tarGz(listOf("../evil.txt" to "boo".toByteArray()))
        fetcher = FakeFetcher(archive)
        val mgr = newManager()

        mgr.install(ModelKind.PARAKEET_STT, "https://x.test/pack.tar.gz")
        val s = mgr.waitFor(ModelKind.PARAKEET_STT) {
            it is ModelState.Failed || it is ModelState.Installed
        }
        assertTrue(s is ModelState.Failed)
        assertFalse(File(store.root.parentFile, "evil.txt").exists())
        assertFalse(File(store.root, "evil.txt").exists())
    }

    @Test
    fun `parakeet pack without the required model files fails validation`() {
        val archive = tarGz(listOf("pack/readme.txt" to "hi".toByteArray()))
        fetcher = FakeFetcher(archive)
        val mgr = newManager()

        mgr.install(ModelKind.PARAKEET_STT, "https://x.test/pack.tar.gz")
        val s = mgr.waitFor(ModelKind.PARAKEET_STT) {
            it is ModelState.Failed || it is ModelState.Installed
        }
        assertTrue(s is ModelState.Failed)
        assertTrue((s as ModelState.Failed).reason.contains("missing"))
        assertFalse(store.installedDir(ModelKind.PARAKEET_STT) != null)
    }

    @Test
    fun `a complete parakeet archive validates and installs`() {
        val onnx = ByteArray(1_000) { 3 }
        val archive = tarGz(
            listOf(
                "sherpa/encoder.int8.onnx" to onnx,
                "sherpa/decoder.int8.onnx" to onnx,
                "sherpa/joiner.int8.onnx" to onnx,
                "sherpa/tokens.txt" to "a\nb\n".toByteArray(),
            ),
        )
        fetcher = FakeFetcher(archive)
        val mgr = newManager()

        mgr.install(ModelKind.PARAKEET_STT, "https://x.test/pack.tar.gz")
        assertEquals(ModelState.Installed, mgr.waitFor(ModelKind.PARAKEET_STT) {
            it is ModelState.Installed || it is ModelState.Failed
        })
        val dir = store.installedDir(ModelKind.PARAKEET_STT)!!
        assertTrue(dir.walkTopDown().any { it.name == "tokens.txt" })
    }

    @Test
    fun `llama pack without a runnable bundle fails validation`() {
        val archive = tarGz(listOf("llama/readme.txt" to "not a model".toByteArray()))
        fetcher = FakeFetcher(archive)
        val mgr = newManager()

        mgr.install(ModelKind.LOCAL_LLM, "https://x.test/pack.tar.gz")
        val s = mgr.waitFor(ModelKind.LOCAL_LLM) {
            it is ModelState.Failed || it is ModelState.Installed
        }
        assertTrue(s is ModelState.Failed)
        assertTrue((s as ModelState.Failed).reason.contains(".task"))
    }

    @Test
    fun `llama pack whose bundle is under half the declared size fails`() {
        // A 4 KB model inside an archive that weighs ~24 KB on the wire:
        // the incompressible filler makes the declared total more than 2×
        // the unpacked bundle, so validation rejects it.
        val tinyModel = ByteArray(4_000) { 1 }
        val filler = ByteArray(20_000).also { java.util.Random(42).nextBytes(it) }
        val archive = tarGz(
            listOf(
                "pack/llama.task" to tinyModel,
                "pack/filler.bin" to filler,
            ),
        )
        fetcher = FakeFetcher(archive)
        val mgr = newManager()
        mgr.install(ModelKind.LOCAL_LLM, "https://x.test/pack.tar.gz")
        val s = mgr.waitFor(ModelKind.LOCAL_LLM) {
            it is ModelState.Failed || it is ModelState.Installed
        }
        assertTrue(s is ModelState.Failed)
    }

    @Test
    fun `declared download size that never arrives fails as truncated`() {
        // Server declares more than it sends — the copy loop must not pretend success.
        fetcher = object : Fetcher {
            override fun open(url: String, rangeStart: Long, headers: Map<String, String>) = FetchResponse(
                code = 200,
                totalBytes = 200_000,
                stream = ByteArrayInputStream(ByteArray(30_000)),
                abort = {},
            )
        }
        val mgr = newManager()
        mgr.install(ModelKind.LOCAL_LLM, "https://x.test/x.task")
        val s = mgr.waitFor(ModelKind.LOCAL_LLM) {
            it is ModelState.Failed || it is ModelState.Installed
        }
        assertTrue(s is ModelState.Failed)
        assertTrue((s as ModelState.Failed).reason.contains("truncated"))
    }

    @Test
    fun `checksum mismatch drops the part and fails`() {
        val bytes = ByteArray(50_000) { 9 }
        fetcher = FakeFetcher(bytes)
        sha256For = { "deadbeef".repeat(8) }
        val mgr = newManager()

        mgr.install(ModelKind.LOCAL_LLM, "https://x.test/llama.task")
        val s = mgr.waitFor(ModelKind.LOCAL_LLM) {
            it is ModelState.Failed || it is ModelState.Installed
        }
        assertTrue(s is ModelState.Failed)
        assertTrue((s as ModelState.Failed).reason.contains("checksum"))
        assertFalse(partFile(mgr, ModelKind.LOCAL_LLM).exists())
    }

    @Test
    fun `correct checksum installs`() {
        val bytes = ByteArray(50_000) { 9 }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
        sha256For = { digest }
        fetcher = FakeFetcher(bytes)
        val mgr = newManager()

        mgr.install(ModelKind.LOCAL_LLM, "https://x.test/llama.task")
        assertEquals(ModelState.Installed, mgr.waitFor(ModelKind.LOCAL_LLM) {
            it is ModelState.Installed || it is ModelState.Failed
        })
    }

    @Test
    fun `low disk space fails before a byte is written`() {
        val bytes = ByteArray(100_000) { 0 }
        fetcher = FakeFetcher(bytes)
        freeSpace = 1_000
        val mgr = newManager()

        mgr.install(ModelKind.LOCAL_LLM, "https://x.test/llama.task")
        val s = mgr.waitFor(ModelKind.LOCAL_LLM) {
            it is ModelState.Failed || it is ModelState.Installed
        }
        assertTrue(s is ModelState.Failed)
        assertTrue((s as ModelState.Failed).reason.contains("Need"))
    }

    @Test
    fun `cancel keeps the part file for a later resume`() {
        val bytes = ByteArray(400_000) { it.toByte() }
        // A slow stream — yields a byte at a time, gated on a latch abort opens.
        val gate = java.util.concurrent.Semaphore(0)
        fetcher = FakeFetcher(
            bytes,
            streamFor = { data ->
                object : InputStream() {
                    var pos = 0
                    var aborted = false
                    override fun read(): Int = -1
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (pos == 0) gate.acquire() // block until abort
                        if (aborted) return -1
                        val n = minOf(len, data.size - pos)
                        System.arraycopy(data, pos, b, off, n)
                        pos += n
                        return n
                    }
                }
            },
        )
        val mgr = newManager()
        store.root.mkdirs()

        mgr.install(ModelKind.LOCAL_LLM, "https://x.test/llama.task")
        // Wait until the download is actually in flight, then cancel.
        mgr.waitFor(ModelKind.LOCAL_LLM) { it is ModelState.Downloading }
        mgr.cancel(ModelKind.LOCAL_LLM)
        gate.release()

        val s = mgr.waitFor(ModelKind.LOCAL_LLM) { it is ModelState.NotInstalled }
        assertEquals(ModelState.NotInstalled, s)
        assertTrue(partFile(mgr, ModelKind.LOCAL_LLM).exists())
    }

    @Test
    fun `connection setup is cancellable before response headers arrive`() {
        val gate = java.util.concurrent.Semaphore(0)
        val started = java.util.concurrent.CountDownLatch(1)
        fetcher = Fetcher { _, _, _ ->
            started.countDown()
            gate.acquire()
            FetchResponse(200, 1, ByteArrayInputStream(byteArrayOf(1)), abort = {})
        }
        val mgr = newManager()
        mgr.install(ModelKind.LOCAL_LLM, "https://x.test/model.task")
        try {
            assertEquals(ModelState.Connecting, mgr.waitFor(ModelKind.LOCAL_LLM) { it == ModelState.Connecting })
            assertTrue(partFile(mgr, ModelKind.LOCAL_LLM).exists())
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
            mgr.cancel(ModelKind.LOCAL_LLM)
        } finally { gate.release() }
        assertEquals(ModelState.NotInstalled, mgr.waitFor(ModelKind.LOCAL_LLM) { it == ModelState.NotInstalled })
        assertTrue(partFile(mgr, ModelKind.LOCAL_LLM).exists())
    }

    @Test
    fun `retry clears failure without discarding partial bytes`() {
        fetcher = Fetcher { _, _, _ -> FetchResponse(200, 100, ByteArrayInputStream(byteArrayOf(1)), abort = {}) }
        val mgr = newManager()
        mgr.install(ModelKind.LOCAL_LLM, "https://x.test/model.task")
        mgr.waitFor(ModelKind.LOCAL_LLM) { it is ModelState.Failed }
        assertEquals(1L, partFile(mgr, ModelKind.LOCAL_LLM).length())
        mgr.clearFailure(ModelKind.LOCAL_LLM.packId)
        assertEquals(ModelState.NotInstalled, mgr.states.value[ModelKind.LOCAL_LLM.packId])
        assertEquals(1L, partFile(mgr, ModelKind.LOCAL_LLM).length())
    }

    @Test
    fun `delete removes pack part and staging and fires the listener`() {
        val bytes = ByteArray(60_000) { it.toByte() }
        fetcher = FakeFetcher(bytes)
        val mgr = newManager()
        var heard: Pair<String, Boolean>? = null
        mgr.addModelChangeListener { k, installed -> heard = k to installed }

        mgr.install(ModelKind.LOCAL_LLM, "https://x.test/llama.task")
        mgr.waitFor(ModelKind.LOCAL_LLM) { it is ModelState.Installed }
        assertEquals(ModelKind.LOCAL_LLM.packId to true, heard)

        runBlocking { mgr.delete(ModelKind.LOCAL_LLM) }
        assertEquals(ModelKind.LOCAL_LLM.packId to false, heard)
        assertFalse(store.dir(ModelKind.LOCAL_LLM).exists())
        assertFalse(partFile(mgr, ModelKind.LOCAL_LLM).exists())
        assertEquals(ModelState.NotInstalled, mgr.states.value[ModelKind.LOCAL_LLM.packId])
    }

    @Test
    fun `install while a transfer is running does not double-fetch`() {
        val bytes = ByteArray(50_000) { 2 }
        val gate = java.util.concurrent.Semaphore(0)
        fetcher = FakeFetcher(
            bytes,
            streamFor = { data ->
                object : InputStream() {
                    var pos = 0
                    override fun read(): Int = -1
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (pos == 0) {
                            try {
                                gate.acquire()
                            } catch (e: InterruptedException) {
                                throw CancellationException()
                            }
                        }
                        val n = minOf(len, data.size - pos)
                        if (n <= 0) return -1
                        System.arraycopy(data, pos, b, off, n)
                        pos += n
                        return n
                    }
                }
            },
        )
        val mgr = newManager()
        mgr.install(ModelKind.LOCAL_LLM, "https://x.test/llama.task")
        mgr.waitFor(ModelKind.LOCAL_LLM) { it is ModelState.Downloading }
        mgr.install(ModelKind.LOCAL_LLM, "https://x.test/other.task")
        assertEquals(1, fakeRanges().size)
        mgr.cancel(ModelKind.LOCAL_LLM)
        gate.release()
        mgr.waitFor(ModelKind.LOCAL_LLM) { it is ModelState.NotInstalled }
    }
}
