package com.chronicle.app.media

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Shared surface of a take recorder — lets the app swap encoders per engine. */
interface TakeRecorder {
    val amplitudes: StateFlow<List<Float>>
    val elapsedMs: StateFlow<Long>
    val isRecording: Boolean
    fun start(): File
    /** Stops and returns the file + duration, or null if nothing was captured. */
    fun stop(): Pair<File, Long>?
    fun cancel()
}

/**
 * Records 16 kHz mono PCM into a .wav file — the exact input Parakeet/sherpa
 * expects, and playable by MediaPlayer for entry playback.
 */
class WavRecorder(private val context: Context, private val scope: CoroutineScope) : TakeRecorder {

    private var record: AudioRecord? = null
    private var writeJob: Job? = null
    private var pollJob: Job? = null
    private var outputFile: File? = null
    private var startedAt: Long = 0
    @Volatile private var dataBytes: Long = 0
    @Volatile private var peak: Int = 0

    private val _amplitudes = MutableStateFlow<List<Float>>(List(VoiceRecorder.BARS) { 0f })
    override val amplitudes: StateFlow<List<Float>> = _amplitudes

    private val _elapsedMs = MutableStateFlow(0L)
    override val elapsedMs: StateFlow<Long> = _elapsedMs

    override val isRecording: Boolean get() = record != null

    override fun start(): File {
        val dir = File(context.filesDir, "recordings").apply { mkdirs() }
        val file = File(dir, "note_${System.currentTimeMillis()}.wav")
        outputFile = file
        dataBytes = 0
        peak = 0
        startedAt = System.currentTimeMillis()

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val r = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                maxOf(minBuf * 2, 64 * 1024),
            )
        } catch (e: SecurityException) {
            outputFile = null
            file.delete()
            throw SecurityException("microphone permission denied", e)
        }
        check(r.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to init" }
        try {
            file.outputStream().use { it.write(ByteArray(44)) } // header placeholder
            r.startRecording()
        } catch (e: Exception) {
            r.release()
            file.delete()
            outputFile = null
            throw e
        }
        record = r

        _elapsedMs.value = 0L
        _amplitudes.value = List(VoiceRecorder.BARS) { 0f }

        writeJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(8 * 1024)
            file.outputStream().use { out ->
                // Skip past the header placeholder written above.
                out.channel.position(44)
                while (isActive) {
                    val n = r.read(buf, 0, buf.size)
                    if (n > 0) {
                        out.write(buf, 0, n)
                        dataBytes += n
                        peak = maxOf(peak, pcmPeak(buf, n))
                    }
                }
            }
        }

        pollJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(90)
                val normalized = (peak / 32767f).coerceIn(0.04f, 1f)
                peak = 0
                val current = _amplitudes.value.toMutableList()
                current.removeAt(0)
                current.add(normalized)
                _amplitudes.value = current
                _elapsedMs.value = System.currentTimeMillis() - startedAt
            }
        }
        return file
    }

    override fun stop(): Pair<File, Long>? {
        val file = outputFile ?: return null
        val r = record ?: return null
        val duration = System.currentTimeMillis() - startedAt
        pollJob?.cancel()
        r.stop()
        record = null
        writeJob?.let { job ->
            // Give the writer a moment to drain the last buffers, then close.
            Thread.sleep(150)
            job.cancel()
        }
        r.release()
        writeHeader(file)
        outputFile = null
        if (dataBytes == 0L) {
            file.delete()
            return null
        }
        return file to duration
    }

    override fun cancel() {
        pollJob?.cancel()
        writeJob?.cancel()
        record?.let { r ->
            runCatching { r.stop() }
            r.release()
        }
        record = null
        outputFile?.delete()
        outputFile = null
        _amplitudes.value = List(VoiceRecorder.BARS) { 0f }
        _elapsedMs.value = 0L
    }

    private fun writeHeader(file: File) {
        RandomAccessFile(file, "rw").use { raf ->
            val data = raf.length() - 44
            raf.seek(0)
            raf.writeBytes("RIFF")
            raf.writeIntLE((36 + data).toInt())
            raf.writeBytes("WAVEfmt ")
            raf.writeIntLE(16)
            raf.writeShortLE(1) // PCM
            raf.writeShortLE(1) // mono
            raf.writeIntLE(SAMPLE_RATE)
            raf.writeIntLE(SAMPLE_RATE * 2)
            raf.writeShortLE(2)
            raf.writeShortLE(16)
            raf.writeBytes("data")
            raf.writeIntLE(data.toInt())
        }
    }

    private fun pcmPeak(buf: ByteArray, n: Int): Int {
        var max = 0
        var i = 0
        while (i + 1 < n) {
            val s = (buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)
            if (kotlin.math.abs(s) > max) max = kotlin.math.abs(s)
            i += 2
        }
        return max
    }

    private fun RandomAccessFile.writeIntLE(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF)
    }

    private fun RandomAccessFile.writeShortLE(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
