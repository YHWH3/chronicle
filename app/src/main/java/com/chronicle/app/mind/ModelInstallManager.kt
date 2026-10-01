package com.chronicle.app.mind

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.chronicle.app.media.ParakeetTranscriber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

/**
 * One open transfer. [open] returns once response headers are in; the body
 * streams through [FetchResponse.stream]. [abort] must make an in-flight
 * read fail fast — for HttpURLConnection that is disconnect().
 */
internal fun interface Fetcher {
    fun open(url: String, rangeStart: Long): FetchResponse
}

/** Response metadata plus the body stream. [close] releases the connection. */
internal class FetchResponse(
    /** HTTP status — 200 fresh body, 206 resumed body, 416 already complete. */
    val code: Int,
    /** Full resource length in bytes when the server reports it, else -1. */
    val totalBytes: Long,
    /** Body stream — null when there is nothing to read (416). */
    val stream: InputStream?,
    private val abort: () -> Unit,
) {
    /** Disconnects the underlying connection — unblocks a parked read. */
    fun abort() = abort.invoke()

    fun close() = abort()
}

/** Production [Fetcher] — a plain HttpURLConnection with a Range header. */
internal class HttpFetcher : Fetcher {

    @Volatile
    private var conn: HttpURLConnection? = null

    override fun open(url: String, rangeStart: Long): FetchResponse {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            if (rangeStart > 0) setRequestProperty("Range", "bytes=$rangeStart-")
        }
        conn = c
        val code = c.responseCode
        val total = when (code) {
            206 -> contentRangeTotal(c.getHeaderField("Content-Range"))
                ?: (c.contentLengthLong.takeIf { it >= 0 }?.plus(rangeStart) ?: -1L)
            416 -> contentRangeTotal(c.getHeaderField("Content-Range")) ?: rangeStart
            else -> c.contentLengthLong
        }
        return FetchResponse(
            code = code,
            totalBytes = total,
            stream = if (code == 416) null else runCatching { c.inputStream }.getOrNull(),
            abort = { c.disconnect() },
        )
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 30_000

        /** Parses "bytes 100-999/5000" or "bytes *&#47;5000" → 5000. */
        fun contentRangeTotal(header: String?): Long? =
            header?.substringAfterLast('/')?.toLongOrNull()
    }
}

/**
 * App-scoped owner of model downloads. Constructed once in AppContainer with
 * its own supervisor scope, so a transfer survives the user leaving Settings
 * — install() enqueues and returns; progress flows through [states].
 *
 * Layout inside filesDir/models/:
 *   <kind>.part        partial download — kept on cancel/failure for resume
 *   <kind>.extracting/ unpack staging — validated before it becomes real
 *   <kind>/            the live pack, marked by a `.installed` file
 *
 * A `.part` younger than [PART_MAX_AGE_MS] resumes via HTTP Range (206 →
 * append, 200 → restart, 416 → already complete). Extracted entries are
 * guarded against zip-slip with a canonical-path check that includes the
 * trailing separator. When the pack's spec carries `expectedSha256`, the
 * downloaded bytes are verified before anything is unpacked.
 */
class ModelInstallManager internal constructor(
    internal val store: ModelStore,
    private val scope: CoroutineScope,
    private val newFetcher: () -> Fetcher = ::HttpFetcher,
    private val freeSpaceBytes: (File) -> Long = { StatFs(it.path).availableBytes },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val warn: (String) -> Unit = { Log.w(TAG, it) },
    /** Expected digest lookup — the kind's spec by default; tests pin their own. */
    private val sha256For: (ModelKind) -> String? = { it.expectedSha256 },
) {

    constructor(context: Context, scope: CoroutineScope) :
        this(ModelStore(context), scope)

    private val _states = MutableStateFlow(
        ModelKind.entries.associateWith {
            if (store.installedDir(it) != null) ModelState.Installed else ModelState.NotInstalled
        },
    )
    val states: StateFlow<Map<ModelKind, ModelState>> = _states

    private val jobs = ConcurrentHashMap<ModelKind, Job>()
    private val liveCalls = ConcurrentHashMap<ModelKind, FetchResponse>()

    /**
     * Fired after a pack becomes installed or is deleted — the listener
     * releases whatever native engine was holding the files.
     */
    private val changeListeners = CopyOnWriteArrayList<(ModelKind, Boolean) -> Unit>()

    fun addModelChangeListener(listener: (kind: ModelKind, installed: Boolean) -> Unit) {
        changeListeners += listener
    }

    // ————— Disk surface (delegated to the store) —————

    fun dir(kind: ModelKind): File = store.dir(kind)
    fun installedDir(kind: ModelKind): File? = store.installedDir(kind)
    fun localLlmFile(): File? = store.localLlmFile()

    private fun partFile(kind: ModelKind) =
        File(store.root, store.dirName(kind) + PART_SUFFIX)

    private fun extractingDir(kind: ModelKind) =
        File(store.root, store.dirName(kind) + EXTRACTING_SUFFIX)

    private fun set(kind: ModelKind, s: ModelState) {
        _states.value = _states.value + (kind to s)
    }

    /**
     * Queues a download on the manager's own scope and returns immediately —
     * callers never own the transfer, so navigating away cannot kill it.
     * A `.part` file left by an earlier attempt resumes automatically.
     */
    fun install(kind: ModelKind, url: String?) {
        val clean = url?.trim().orEmpty()
        if (clean.isEmpty()) {
            set(kind, ModelState.Failed("No download URL — paste a model link first."))
            return
        }
        if (jobs[kind]?.isActive == true) return
        jobs[kind] = scope.launch { runInstall(kind, clean) }
    }

    /** Stops an in-flight transfer within ~1s; the `.part` stays for resume. */
    fun cancel(kind: ModelKind) {
        jobs[kind]?.cancel()
        liveCalls[kind]?.abort()
    }

    /**
     * Removes everything — installed pack, `.part`, extraction staging — and
     * notifies listeners so any loaded engine is released before the files
     * disappear. Suspends only to join the cancelled job.
     */
    suspend fun delete(kind: ModelKind) {
        jobs[kind]?.cancel()
        liveCalls[kind]?.abort()
        jobs[kind]?.join()
        val had = store.installedDir(kind) != null
        dir(kind).deleteRecursively()
        partFile(kind).delete()
        extractingDir(kind).deleteRecursively()
        set(kind, ModelState.NotInstalled)
        changeListeners.forEach { it(kind, false) }
        if (had) warn("${store.dirName(kind)} deleted")
    }

    private suspend fun runInstall(kind: ModelKind, url: String) {
        val packDir = dir(kind)
        val part = partFile(kind)
        val extracting = extractingDir(kind)
        store.root.mkdirs()
        set(kind, ModelState.Downloading(0f, 0, -1))
        try {
            val fileName = url.substringBefore('?').substringAfterLast('/')
                .ifBlank { "model.bin" }
            val total = download(kind, url, part)
            coroutineContext.ensureActive()

            // A spec-pinned digest is verified on the downloaded bytes before
            // anything is unpacked.
            sha256For(kind)?.let { expected ->
                val actual = sha256(part)
                if (!actual.equals(expected, ignoreCase = true)) {
                    part.delete()
                    throw IOException("checksum mismatch — got ${actual.take(12)}…")
                }
            }

            set(kind, ModelState.Extracting)
            extracting.deleteRecursively()
            extracting.mkdirs()
            withContext(Dispatchers.IO) {
                if (isArchive(fileName)) {
                    unpack(part, extracting)
                } else {
                    val out = File(extracting, fileName)
                    if (!part.renameTo(out)) part.copyTo(out, overwrite = true)
                }
            }
            part.delete()

            validate(kind, extracting, total)
            packDir.deleteRecursively()
            check(extracting.renameTo(packDir)) { "couldn't move model into place" }
            store.writeMarker(packDir)
            set(kind, ModelState.Installed)
            changeListeners.forEach { it(kind, true) }
        } catch (c: CancellationException) {
            // Keep the .part — the next install() resumes from it.
            set(kind, ModelState.NotInstalled)
            throw c
        } catch (t: Throwable) {
            if (!coroutineContext.isActive) {
                set(kind, ModelState.NotInstalled)
                throw CancellationException(t.message).apply { initCause(t) }
            }
            extracting.deleteRecursively()
            set(kind, ModelState.Failed(t.message?.lineSequence()?.firstOrNull()
                ?: "download failed"))
        }
    }

    /**
     * Streams [url] into [part], resuming from the file's current length when
     * it is fresh enough to trust. Returns the total byte count the server
     * reported (-1 when unknown).
     */
    private suspend fun download(kind: ModelKind, url: String, part: File): Long =
        withContext(Dispatchers.IO) {
            var resumeAt = resumeOffset(part)
            val call = newFetcher().open(url, resumeAt)
            liveCalls[kind] = call
            try {
                when {
                    call.code == 416 -> {
                        // Range unsatisfiable — the server is telling us the
                        // local bytes are the whole resource already.
                        return@withContext call.totalBytes.takeIf { it > 0 } ?: part.length()
                    }
                    call.code in 200..299 -> Unit
                    else -> throw IOException("server answered HTTP ${call.code}")
                }
                val append = call.code == 206 && resumeAt > 0
                if (!append) {
                    resumeAt = 0L
                    part.delete()
                }
                val total = call.totalBytes
                if (total > 0) ensureSpace(total)
                val stream = call.stream ?: throw IOException("empty response body")
                FileOutputStream(part, append).use { out ->
                    stream.use { input ->
                        var done = resumeAt
                        val buf = ByteArray(COPY_BUFFER)
                        while (true) {
                            ensureActive()
                            val n = try {
                                input.read(buf)
                            } catch (e: IOException) {
                                // abort() on cancel surfaces here as IOException;
                                // it must stay a cancellation, not a failure.
                                ensureActive()
                                throw e
                            }
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            set(
                                kind,
                                ModelState.Downloading(
                                    progress = if (total > 0) {
                                        (done.toFloat() / total).coerceIn(0f, 1f)
                                    } else -1f,
                                    doneBytes = done,
                                    totalBytes = total,
                                ),
                            )
                        }
                        if (total > 0 && done < total) {
                            throw IOException("download truncated at $done of $total bytes")
                        }
                        total.takeIf { it > 0 } ?: done
                    }
                }
            } finally {
                liveCalls.remove(kind)
                call.close()
            }
        }

    /** Fresh `.part` resumes; anything older than [PART_MAX_AGE_MS] restarts. */
    private fun resumeOffset(part: File): Long {
        if (!part.exists()) return 0L
        val ageMs = nowMs() - part.lastModified()
        return if (ageMs <= PART_MAX_AGE_MS) part.length() else {
            part.delete()
            0L
        }
    }

    /** Fails early when the device clearly can't hold download + unpacked pack. */
    private fun ensureSpace(contentLength: Long) {
        val usable = freeSpaceBytes(store.root)
        val needed = (contentLength * SPACE_MARGIN).toLong()
        if (usable < needed) {
            throw IOException(
                "Need ${needed / 1_000_000} MB free — only ${usable / 1_000_000} MB available",
            )
        }
    }

    /**
     * Extracts a .tar.bz2/.tar.gz/.tgz into [dest]. Every entry is resolved
     * against the canonical destination + separator so `../` escapes and
     * absolute paths are rejected — not merely paths that happen to share a
     * string prefix with the destination.
     */
    private fun unpack(archive: File, dest: File) {
        val destPrefix = dest.canonicalPath + File.separator
        FileInputStream(archive).buffered().use { fis ->
            fis.mark(4)
            val magic = ByteArray(2)
            fis.read(magic)
            fis.reset()
            val plain = if (magic[0] == 'B'.code.toByte() && magic[1] == 'Z'.code.toByte()) {
                BZip2CompressorInputStream(fis)
            } else {
                GzipCompressorInputStream(fis)
            }
            TarArchiveInputStream(plain).use { tar ->
                generateSequence { tar.nextEntry }.forEach { entry ->
                    val out = File(dest, entry.name)
                    val outPath = out.canonicalPath
                    check(outPath == dest.canonicalPath || outPath.startsWith(destPrefix)) {
                        "zip-slip in model archive: ${entry.name}"
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { tar.copyTo(it) }
                    }
                }
            }
        }
    }

    /**
     * The pack must contain what its engine actually loads — checked on the
     * staging dir before it is renamed into place. Size sanity uses the
     * download's Content-Length (extracted bytes for a compressed archive are
     * larger than the archive, so a half-size floor stays lenient).
     */
    private fun validate(kind: ModelKind, dir: File, expectedBytes: Long) {
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        when (kind) {
            ModelKind.PARAKEET_STT -> {
                val missing = ParakeetTranscriber.REQUIRED_MODEL_FILES
                    .filter { req -> files.none { it.name == req } }
                check(missing.isEmpty()) {
                    "incomplete pack — missing ${missing.joinToString()}"
                }
            }
            ModelKind.LOCAL_LLM -> {
                val model = files.firstOrNull { ModelStore.isLlmBundle(it) }
                checkNotNull(model) { "no .task/.litertlm bundle in pack" }
                if (expectedBytes > 0) {
                    check(model.length() >= expectedBytes / 2) {
                        "model file smaller than expected (${model.length()} < ${expectedBytes / 2})"
                    }
                }
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(COPY_BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isArchive(name: String): Boolean =
        name.endsWith(".tar.bz2") || name.endsWith(".tar.gz") || name.endsWith(".tgz")

    private companion object {
        const val TAG = "ModelInstall"
        const val COPY_BUFFER = 256 * 1024
        const val PART_SUFFIX = ".part"
        const val EXTRACTING_SUFFIX = ".extracting"
        /** Bytes we want free beyond the download — covers the unpacked copy. */
        const val SPACE_MARGIN = 1.3f
        /** Resume windows close after a day — archives may be replaced upstream. */
        const val PART_MAX_AGE_MS = 24L * 60 * 60 * 1000
    }
}
