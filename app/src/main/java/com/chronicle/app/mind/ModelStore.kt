package com.chronicle.app.mind

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

/** Which downloadable pack a model state refers to. */
enum class ModelKind(val title: String, val sizeNote: String) {
    PARAKEET_STT("Parakeet TDT 0.6B · speech to text", "~250 MB"),
    LOCAL_LLM("Llama 3.2 3B · on-device mind", "~2 GB"),
}

sealed interface ModelState {
    data object NotInstalled : ModelState
    /** progress is -1 when the server won't report a total size. */
    data class Downloading(val progress: Float, val doneBytes: Long) : ModelState
    data object Extracting : ModelState
    data object Installed : ModelState
    data class Failed(val reason: String) : ModelState
}

/**
 * Downloads and unpacks model packs into filesDir/models/<kind>/.
 * A pack is "installed" when its marker file exists. Downloads write to a
 * .part file and rename/extract atomically so a killed app never leaves a
 * half-installed model.
 */
class ModelStore(private val context: Context) {

    private val root = File(context.filesDir, "models")

    private val _states = MutableStateFlow(
        ModelKind.entries.associateWith { initialState(it) },
    )
    val states: StateFlow<Map<ModelKind, ModelState>> = _states

    private val jobs = mutableMapOf<ModelKind, Job>()

    fun dir(kind: ModelKind): File = File(root, kind.name.lowercase())

    fun installedDir(kind: ModelKind): File? =
        dir(kind).takeIf { File(it, MARKER).exists() }

    /** The installed .task file for the local LLM, if present. */
    fun localLlmFile(): File? = installedDir(ModelKind.LOCAL_LLM)
        ?.listFiles { f -> f.extension.equals("task", ignoreCase = true) }
        ?.firstOrNull()

    private fun initialState(kind: ModelKind): ModelState =
        if (installedDir(kind) != null) ModelState.Installed else ModelState.NotInstalled

    private fun set(kind: ModelKind, s: ModelState) {
        _states.value = _states.value + (kind to s)
    }

    fun registerJob(kind: ModelKind, job: Job) {
        jobs[kind] = job
    }

    /**
     * Downloads [url] into the pack dir. If the URL names a tar archive
     * (.tar.bz2/.tar.gz/.tgz) it is extracted in place; anything else is
     * stored under its URL filename.
     */
    suspend fun install(kind: ModelKind, url: String) {
        if (_states.value[kind] is ModelState.Downloading) return
        val packDir = dir(kind).apply { mkdirs() }
        val fileName = url.substringBefore('?').substringAfterLast('/').ifBlank { "model.bin" }
        val tmp = File(packDir, "download.part")
        set(kind, ModelState.Downloading(0f, 0))
        try {
            download(url, tmp) { done, total ->
                set(
                    kind,
                    ModelState.Downloading(
                        progress = if (total > 0) done.toFloat() / total else -1f,
                        doneBytes = done,
                    ),
                )
            }
            if (isArchive(fileName)) {
                set(kind, ModelState.Extracting)
                unpack(tmp, packDir)
                tmp.delete()
            } else {
                tmp.renameTo(File(packDir, fileName))
            }
            File(packDir, MARKER).writeText("ok")
            set(kind, ModelState.Installed)
        } catch (c: CancellationException) {
            tmp.delete()
            set(kind, ModelState.NotInstalled)
            throw c
        } catch (t: Throwable) {
            tmp.delete()
            set(kind, ModelState.Failed(t.message ?: "download failed"))
        }
    }

    fun cancel(kind: ModelKind) {
        jobs[kind]?.cancel()
    }

    fun delete(kind: ModelKind) {
        jobs[kind]?.cancel()
        dir(kind).deleteRecursively()
        set(kind, ModelState.NotInstalled)
    }

    private fun isArchive(name: String): Boolean =
        name.endsWith(".tar.bz2") || name.endsWith(".tar.gz") || name.endsWith(".tgz")

    private suspend fun download(
        url: String,
        dest: File,
        onProgress: (done: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            val total = conn.contentLengthLong
            var done = 0L
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun unpack(archive: File, dest: File) {
        FileInputStream(archive).buffered().use { fis ->
            fis.mark(4)
            val magic = ByteArray(2)
            fis.read(magic)
            fis.reset()
            val isBz2 = magic[0] == 'B'.code.toByte() && magic[1] == 'Z'.code.toByte()
            val plain = if (isBz2) BZip2CompressorInputStream(fis) else GzipCompressorInputStream(fis)
            TarArchiveInputStream(plain).use { tar ->
                generateSequence { tar.nextEntry }.forEach { entry ->
                    val out = File(dest, entry.name).canonicalFile
                    check(out.path.startsWith(dest.canonicalPath)) { "zip-slip in model archive" }
                    if (entry.isDirectory) out.mkdirs()
                    else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { tar.copyTo(it) }
                    }
                }
            }
        }
    }

    private companion object {
        const val MARKER = ".installed"
    }
}
