package com.chronicle.app.media

import android.content.Context
import com.chronicle.app.mind.ModelKind
import com.chronicle.app.mind.ModelStore
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
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
 * Fully-offline speech-to-text through sherpa-onnx running NVIDIA's
 * Parakeet TDT 0.6B (int8). The model pack is fetched once from Settings
 * and lives in filesDir/models/parakeet_stt/.
 *
 * Unlike [SpeechTranscriber] this works file-first: it transcribes a
 * finished take rather than streaming, so there is no live transcript.
 */
class ParakeetTranscriber(
    private val context: Context,
    private val modelStore: ModelStore,
) : WavTranscriber {

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    /** True when the pack is installed — the engine only works with the model on disk. */
    override val available: Boolean
        get() = modelDir() != null

    private fun modelDir(): File? = modelStore.installedDir(ModelKind.PARAKEET_STT)
        ?.let { dir ->
            // The tarball nests one folder — find where the .onnx files live.
            dir.walkTopDown().firstOrNull { f -> f.isFile && f.name == "tokens.txt" }?.parentFile
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

    /** Transcribes a 16 kHz mono PCM .wav take. */
    override suspend fun transcribe(wavFile: File): String = withContext(Dispatchers.Default) {
        val samples = readWavPcm16(wavFile)
        check(samples.isNotEmpty()) { "empty audio" }
        val stream = engine().createStream()
        stream.acceptWaveform(samples, SAMPLE_RATE)
        val e = engine()
        e.decode(stream)
        e.getResult(stream).text.trim()
    }

    fun release() {
        runCatching { recognizer?.release() }
        recognizer = null
    }

    /** Reads PCM16 samples from a WAV file produced by [WavRecorder]. */
    private fun readWavPcm16(file: File): FloatArray {
        RandomAccessFile(file, "r").use { raf ->
            val riff = ByteArray(12)
            raf.readFully(riff)
            // Walk chunks to "data".
            var dataLen = -1L
            while (raf.filePointer + 8 <= raf.length()) {
                val id = ByteArray(4)
                raf.readFully(id)
                val len = raf.readIntLE()
                if (id.contentEquals("data".toByteArray())) {
                    dataLen = len
                    break
                }
                raf.skipBytes(len.toInt())
            }
            check(dataLen >= 0) { "not a pcm wav" }
            val bytes = ByteArray(dataLen.toInt())
            raf.readFully(bytes)
            val out = FloatArray(bytes.size / 2)
            var i = 0
            var j = 0
            while (i + 1 < bytes.size) {
                val s = (bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)
                out[j++] = s / 32768f
                i += 2
            }
            return out
        }
    }

    private fun RandomAccessFile.readIntLE(): Long {
        val b = ByteArray(4)
        readFully(b)
        return (b[0].toLong() and 0xFF) or
            ((b[1].toLong() and 0xFF) shl 8) or
            ((b[2].toLong() and 0xFF) shl 16) or
            ((b[3].toLong() and 0xFF) shl 24)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}
