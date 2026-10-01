package com.chronicle.app.media

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Shared surface of a take recorder — lets the app swap encoders per engine. */
interface TakeRecorder {
    val amplitudes: StateFlow<List<Float>>
    val elapsedMs: StateFlow<Long>
    val isRecording: Boolean
    /** Starts a take; never throws — failures arrive as typed [RecordStart]s. */
    fun start(): RecordStart
    /**
     * Stops and returns the file + duration, or null if nothing was captured.
     * Suspends so the writer/sampler jobs are fully joined before release.
     */
    suspend fun stop(): Pair<File, Long>?
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
    /** Signals the write loop to drain and exit — set before AudioRecord.stop(). */
    @Volatile private var stopping = false

    private val _amplitudes = MutableStateFlow<List<Float>>(List(VoiceRecorder.BARS) { 0f })
    override val amplitudes: StateFlow<List<Float>> = _amplitudes

    private val _elapsedMs = MutableStateFlow(0L)
    override val elapsedMs: StateFlow<Long> = _elapsedMs

    override val isRecording: Boolean get() = record != null

    override fun start(): RecordStart {
        val dir = File(context.filesDir, "recordings").apply { mkdirs() }
        val file = File(dir, "note_${System.currentTimeMillis()}.wav")
        outputFile = file
        dataBytes = 0
        peak = 0
        stopping = false
        startedAt = SystemClock.elapsedRealtime()

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
            discard(file)
            return RecordStart.PermissionMissing
        } catch (e: RuntimeException) {
            discard(file)
            return RecordStart.InitFailed(e.message ?: "AudioRecord init failed")
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            discard(file)
            return RecordStart.InitFailed("AudioRecord failed to initialize")
        }
        try {
            file.outputStream().use { it.write(ByteArray(44)) } // header placeholder
            r.startRecording()
        } catch (e: SecurityException) {
            r.release()
            discard(file)
            return RecordStart.PermissionMissing
        } catch (e: IllegalStateException) {
            // Mic held by another app/call.
            r.release()
            discard(file)
            return RecordStart.MicrophoneBusy
        } catch (e: IOException) {
            r.release()
            discard(file)
            return RecordStart.Unavailable
        } catch (e: RuntimeException) {
            r.release()
            discard(file)
            return RecordStart.InitFailed(e.message ?: "startRecording failed")
        }
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            r.release()
            discard(file)
            return RecordStart.Unavailable
        }
        record = r

        _elapsedMs.value = 0L
        _amplitudes.value = List(VoiceRecorder.BARS) { 0f }

        writeJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(8 * 1024)
            try {
                file.outputStream().use { out ->
                    // Skip past the header placeholder written above.
                    out.channel.position(44)
                    while (isActive && !stopping) {
                        // read() unblocks with an error once AudioRecord.stop()
                        // lands, and a released recorder throws — both end the loop.
                        val n = try {
                            r.read(buf, 0, buf.size)
                        } catch (e: Exception) {
                            -1
                        }
                        if (n > 0) {
                            out.write(buf, 0, n)
                            dataBytes += n
                            peak = maxOf(peak, WavPcm.pcmPeak(buf, n))
                        } else if (n < 0) {
                            break
                        }
                    }
                    out.flush()
                }
            } catch (e: Exception) {
                // File gone or disk error — dataBytes stays 0, stop() drops the take.
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
                _elapsedMs.value = SystemClock.elapsedRealtime() - startedAt
            }
        }
        return RecordStart.Started(file)
    }

    /**
     * Signal the writer → stop AudioRecord (unblocks its read) → join the
     * writer → fix the header → release. No sleeps, no racing release.
     */
    override suspend fun stop(): Pair<File, Long>? {
        val file = outputFile ?: return null
        val r = record ?: return null
        val duration = SystemClock.elapsedRealtime() - startedAt
        pollJob?.cancel()
        pollJob?.join()
        stopping = true
        runCatching { r.stop() }
        // Header + release run even if stop() itself is cancelled — a partial
        // take must still be a valid WAV and the device must be freed.
        withContext(NonCancellable) {
            withTimeoutOrNull(1_500) { writeJob?.join() }
            writeHeader(file)
        }
        r.release()
        record = null
        outputFile = null
        if (dataBytes == 0L) {
            file.delete()
            return null
        }
        return file to duration
    }

    override fun cancel() {
        stopping = true
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

    private fun discard(file: File) {
        file.delete()
        outputFile = null
    }

    private fun writeHeader(file: File) {
        RandomAccessFile(file, "rw").use { raf ->
            val data = raf.length() - 44
            raf.seek(0)
            raf.write(WavPcm.header(SAMPLE_RATE, data))
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
