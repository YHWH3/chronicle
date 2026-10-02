package com.chronicle.app.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Records voice notes to .m4a under filesDir/recordings and exposes a rolling
 * amplitude window for the waveform — [amplitudes] keeps the last [BARS] bars.
 *
 * [start] never throws — it returns a typed [RecordStart] the UI can react
 * to. [stop] suspends so the amplitude poller is fully joined before the
 * recorder is released.
 */
class VoiceRecorder(private val context: Context, private val scope: CoroutineScope) : TakeRecorder {

    private var recorder: MediaRecorder? = null
    private var pollJob: Job? = null
    private var outputFile: File? = null
    private var startedAt: Long = 0

    private val _amplitudes = MutableStateFlow<List<Float>>(List(BARS) { 0f })
    override val amplitudes: StateFlow<List<Float>> = _amplitudes

    private val _elapsedMs = MutableStateFlow(0L)
    override val elapsedMs: StateFlow<Long> = _elapsedMs

    override val isRecording: Boolean get() = recorder != null

    override fun start(): RecordStart {
        val dir = File(context.filesDir, "recordings").apply { mkdirs() }
        val file = File(dir, "note_${System.currentTimeMillis()}.m4a")
        outputFile = file
        // Monotonic — wall-clock edits can't bend the duration.
        startedAt = SystemClock.elapsedRealtime()

        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(96_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
        } catch (e: SecurityException) {
            r.release()
            discard(file)
            return RecordStart.PermissionMissing
        } catch (e: IllegalStateException) {
            // start() on a busy/mis-sequenced recorder — mic is held elsewhere.
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
            return RecordStart.InitFailed(e.message ?: "MediaRecorder start failed")
        }
        recorder = r

        _elapsedMs.value = 0L
        _amplitudes.value = List(BARS) { 0f }
        pollJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(90)
                // Guarded — cancel()/stop() may race this read with release().
                val max = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                val normalized = (max / 32767f).coerceIn(0.04f, 1f)
                val current = _amplitudes.value.toMutableList()
                current.removeAt(0)
                current.add(normalized)
                _amplitudes.value = current
                _elapsedMs.value = SystemClock.elapsedRealtime() - startedAt
            }
        }
        return RecordStart.Started(file)
    }

    /** Stops and returns the file + duration, or null if nothing was captured. */
    override suspend fun stop(): Pair<File, Long>? {
        val file = outputFile ?: return null
        val r = recorder ?: return null
        val duration = SystemClock.elapsedRealtime() - startedAt
        // Cancel AND join — the poller can be mid-maxAmplitude when release lands.
        pollJob?.cancel()
        pollJob?.join()
        try {
            r.stop()
        } catch (_: RuntimeException) {
            // Stopping too early throws — treat as a failed take.
            file.delete()
            return null
        } finally {
            r.release()
            recorder = null
            outputFile = null
        }
        return file to duration
    }

    override fun cancel() {
        pollJob?.cancel()
        recorder?.let { r ->
            runCatching { r.stop() }
            r.release()
        }
        recorder = null
        outputFile?.delete()
        outputFile = null
        _amplitudes.value = List(BARS) { 0f }
        _elapsedMs.value = 0L
    }

    private fun discard(file: File) {
        file.delete()
        outputFile = null
    }

    companion object {
        const val BARS = 32
    }
}
