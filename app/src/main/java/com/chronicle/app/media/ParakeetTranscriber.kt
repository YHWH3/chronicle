package com.chronicle.app.media

import android.content.Context
import android.util.Log
import com.chronicle.app.mind.ModelKind
import com.chronicle.app.mind.ModelStore
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** File-first speech-to-text — transcribes a finished take rather than streaming. */
interface WavTranscriber {
    /** True when the engine can actually run (model installed, on disk). */
    val available: Boolean
    /** Transcribes a 16 kHz mono PCM .wav take; throws on failure. */
    suspend fun transcribe(wavFile: File): String
}

/**
 * Reads a PCM16 WAV's `data` chunk in bounded passes — pure JVM so the
 * framing and conversion stay unit-testable without sherpa or Android.
 *
 * Memory rule: never materialise the take as both bytes and floats. At most
 * one [CHUNK_BYTES] byte buffer plus one float buffer of half that length
 * are live at once (~1.5 MB), regardless of take length. Takes longer than
 * [MAX_SAMPLES] samples (~1 h at 16 kHz) are refused up front: the native
 * feature buffer grows with duration too, and a take that long is a sign
 * something else went wrong.
 */
internal object WavPcmStream {

    /** Hard cap — one hour of 16 kHz mono PCM16. */
    const val MAX_SAMPLES = 16_000L * 3_600L

    /** Bytes read per pass — 256K samples, ~16 s of audio. */
    const val CHUNK_BYTES = 512 * 1024

    /**
     * Walks RIFF chunks to `data`, leaves [raf] positioned at the first PCM
     * byte and returns the chunk length in bytes. Throws on a non-WAV or a
     * WAV with no data chunk.
     */
    fun locateData(raf: RandomAccessFile): Long {
        val riff = ByteArray(12)
        raf.readFully(riff)
        require(
            riff[0] == 'R'.code.toByte() && riff[1] == 'I'.code.toByte() &&
                riff[2] == 'F'.code.toByte() && riff[3] == 'F'.code.toByte(),
        ) { "not a RIFF file" }
        while (raf.filePointer + 8 <= raf.length()) {
            val id = ByteArray(4)
            raf.readFully(id)
            val len = raf.readIntLE()
            if (id.contentEquals("data".toByteArray())) return len
            // Chunks are word-aligned — a pad byte follows odd lengths.
            raf.skipBytes((len + (len and 1)).toInt())
        }
        throw IllegalArgumentException("not a pcm wav — no data chunk")
    }

    private fun RandomAccessFile.readIntLE(): Long {
        val b = ByteArray(4)
        readFully(b)
        return (b[0].toLong() and 0xFF) or
            ((b[1].toLong() and 0xFF) shl 8) or
            ((b[2].toLong() and 0xFF) shl 16) or
            ((b[3].toLong() and 0xFF) shl 24)
    }

    /** Decodes [bytes] little-endian PCM16 from [buf] into [out]; returns samples written. */
    fun decodePcm16le(buf: ByteArray, bytes: Int, out: FloatArray): Int {
        var i = 0
        var j = 0
        while (i + 1 < bytes && j < out.size) {
            val s = (buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)
            out[j++] = s / 32768f
            i += 2
        }
        return j
    }

    /**
     * Streams the WAV's PCM through [sink] as decoded float chunks.
     * [sink] receives (buffer, validSamples); the buffer is reused between
     * calls, so consumers must copy or consume it synchronously.
     */
    fun feed(wavFile: File, sink: (FloatArray, Int) -> Unit) {
        RandomAccessFile(wavFile, "r").use { raf ->
            val dataLen = locateData(raf)
            val samples = dataLen / 2
            val minutes = MAX_SAMPLES / 16_000 / 60
            require(samples in 1..MAX_SAMPLES) {
                "audio outside supported range (0–$minutes min)"
            }
            val buf = ByteArray(CHUNK_BYTES)
            val floats = FloatArray(CHUNK_BYTES / 2)
            var remaining = dataLen
            while (remaining > 0) {
                val n = raf.read(buf, 0, minOf(remaining, CHUNK_BYTES.toLong()).toInt())
                if (n <= 0) break
                sink(floats, decodePcm16le(buf, n, floats))
                remaining -= n
            }
        }
    }
}

/**
 * Fully-offline speech-to-text through sherpa-onnx running NVIDIA's
 * Parakeet TDT 0.6B (int8). The model pack is fetched once from Settings
 * and lives in filesDir/models/parakeet_stt/.
 *
 * Unlike [SpeechTranscriber] this works file-first: it transcribes a
 * finished take rather than streaming, so there is no live transcript.
 *
 * Lifecycle: the native engine is created lazily on first transcribe and
 * held until [release]; the install manager calls release +
 * [invalidateAvailabilityCache] when the pack is deleted, so native memory
 * follows the files away.
 */
class ParakeetTranscriber(
    private val context: Context,
    private val modelStore: ModelStore,
) : WavTranscriber {

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    /**
     * Discovery walks the pack's filesystem — cache it. The install manager
     * calls [invalidateAvailabilityCache] when the pack's state changes.
     */
    @Volatile
    private var dirCached = false

    @Volatile
    private var cachedModelDir: File? = null

    /** True when the pack is installed — the engine only works with the model on disk. */
    override val available: Boolean
        get() = modelDir() != null

    /** Drops the cached pack-dir answer so the next [available] re-reads disk. */
    fun invalidateAvailabilityCache() {
        dirCached = false
        cachedModelDir = null
    }

    private fun modelDir(): File? {
        if (dirCached) return cachedModelDir
        val dir = modelStore.installedDir(ModelKind.PARAKEET_STT)?.let { root ->
            // The tarball nests one folder — find where the .onnx files live.
            root.walkTopDown().firstOrNull { f -> f.isFile && f.name == "tokens.txt" }
                ?.parentFile
        }
        if (dir != null) {
            Log.d(TAG, "model dir: ${dir.absolutePath} — ${dir.list()?.joinToString().orEmpty()}")
        }
        cachedModelDir = dir
        dirCached = true
        return dir
    }

    private fun engine(): OfflineRecognizer = recognizer ?: synchronized(this) {
        recognizer ?: run {
            val dir = modelDir() ?: error("Parakeet model not installed")
            val file = { name: String -> File(dir, name).absolutePath }
            val config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = file("encoder.int8.onnx"),
                        decoder = file("decoder.int8.onnx"),
                        joiner = file("joiner.int8.onnx"),
                    ),
                    tokens = file("tokens.txt"),
                    numThreads = 2,
                    modelType = "nemo_transducer",
                    debug = false,
                ),
            )
            OfflineRecognizer(config = config).also { recognizer = it }
        }
    }

    /**
     * Transcribes a 16 kHz mono PCM .wav take. The file streams through the
     * recognizer in bounded chunks — JVM heap stays ~1.5 MB whatever the
     * take length (see [WavPcmStream]); past ~1 h fails with a clean error.
     */
    override suspend fun transcribe(wavFile: File): String = withContext(Dispatchers.Default) {
        val e = engine()
        val stream = e.createStream()
        feedStream(stream, wavFile)
        e.decode(stream)
        e.getResult(stream).text.trim()
    }

    /** Feeds the whole WAV into the sherpa stream, one bounded chunk at a time. */
    private fun feedStream(stream: OfflineStream, wavFile: File) {
        var fed = 0L
        WavPcmStream.feed(wavFile) { floats, n ->
            stream.acceptWaveform(
                if (n == floats.size) floats else floats.copyOf(n),
                SAMPLE_RATE,
            )
            fed += n
        }
        check(fed > 0) { "empty audio" }
    }

    /** Idempotent native release — safe on pack delete, trim, or a second call. */
    fun release() {
        runCatching { recognizer?.release() }
        recognizer = null
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val TAG = "Parakeet"

        /**
         * The files [engine] needs inside the pack — also the list the
         * install manager validates before a download becomes "installed".
         */
        val REQUIRED_MODEL_FILES = listOf(
            "encoder.int8.onnx",
            "decoder.int8.onnx",
            "joiner.int8.onnx",
            "tokens.txt",
        )
    }
}
