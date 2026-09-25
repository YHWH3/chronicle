package com.chronicle.app.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Records voice notes to .m4a under filesDir/recordings and exposes a rolling
 * amplitude window for the waveform — [amplitudes] keeps the last [BARS] bars.
 */
class VoiceRecorder(private val context: Context, private val scope: CoroutineScope) {

    private var recorder: MediaRecorder? = null
    private var pollJob: Job? = null
    private var outputFile: File? = null
    private var startedAt: Long = 0

    private val _amplitudes = MutableStateFlow<List<Float>>(List(BARS) { 0f })
    val amplitudes: StateFlow<List<Float>> = _amplitudes

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs

    val isRecording: Boolean get() = recorder != null

    fun start(): File {
        val dir = File(context.filesDir, "recordings").apply { mkdirs() }
        val file = File(dir, "note_${System.currentTimeMillis()}.m4a")
        outputFile = file
        startedAt = System.currentTimeMillis()

        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(96_000)
        r.setAudioSamplingRate(44_100)
        r.setOutputFile(file.absolutePath)
        r.prepare()
        r.start()
        recorder = r

        _elapsedMs.value = 0L
        _amplitudes.value = List(BARS) { 0f }
        pollJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(90)
                val max = recorder?.maxAmplitude ?: 0
                val normalized = (max / 32767f).coerceIn(0.04f, 1f)
                val current = _amplitudes.value.toMutableList()
                current.removeAt(0)
                current.add(normalized)
                _amplitudes.value = current
                _elapsedMs.value = System.currentTimeMillis() - startedAt
            }
        }
        return file
    }

    /** Stops and returns the file + duration, or null if nothing was captured. */
    fun stop(): Pair<File, Long>? {
        val file = outputFile ?: return null
        val r = recorder ?: return null
        val duration = System.currentTimeMillis() - startedAt
        pollJob?.cancel()
        try {
            r.stop()
        } catch (_: RuntimeException) {
            // Stopping too early throws — treat as a failed take.
            file.delete()
            recorder = null
            outputFile = null
            return null
        }
        r.release()
        recorder = null
        outputFile = null
        return file to duration
    }

    fun cancel() {
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

    companion object {
        const val BARS = 32
    }
}
