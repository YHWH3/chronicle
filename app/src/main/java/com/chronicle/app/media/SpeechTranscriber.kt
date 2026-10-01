package com.chronicle.app.media

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Live speech-to-text using Android's SpeechRecognizer — on-device
 * recognition only, so speech never leaves the device. When on-device
 * recognition is unavailable, [available] is false and the app falls
 * back to audio-only capture.
 */
class SpeechTranscriber(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial

    private val _committed = MutableStateFlow<List<String>>(emptyList())
    val committed: StateFlow<List<String>> = _committed

    val transcript: String
        get() = (_committed.value + _partial.value)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()

    /** True only when on-device recognition exists — voice never leaves the device. */
    val available: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isRecognitionAvailable(context) &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private var finishing = false

    fun start() {
        if (!available) return
        stopInternal()
        finishing = false
        _partial.value = ""
        _committed.value = emptyList()

        // On-device only — a network recognizer would send speech off-device.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)

        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                // Restart once for benign timeouts to keep a long take alive.
                if (finishing) return
                if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                ) {
                    recognizer?.let { startListening(it) }
                }
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotBlank()) {
                    _committed.value = _committed.value + text
                }
                _partial.value = ""
                // Keep listening — a voice note can outlive one recognition pass.
                if (!finishing) recognizer?.let { startListening(it) }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                _partial.value = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        recognizer = r
        startListening(r)
    }

    private fun startListening(r: SpeechRecognizer) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }
        r.startListening(intent)
    }

    /**
     * Stops listening, gives the service a bounded window to emit the final
     * onResults (so the tail of the take is transcribed), then destroys.
     */
    suspend fun stop() {
        val r = recognizer ?: return
        finishing = true
        r.stopListening()
        delay(600)
        r.destroy()
        if (recognizer === r) recognizer = null
    }

    /** Immediate teardown for cancel paths — drops the tail of the take. */
    fun destroyNow() {
        finishing = true
        stopInternal()
    }

    private fun stopInternal() {
        recognizer?.let { r ->
            r.stopListening()
            r.destroy()
        }
        recognizer = null
    }
}
