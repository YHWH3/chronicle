package com.chronicle.app.media

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Live speech-to-text while a take is being recorded. */
interface LiveTranscriber {
    val available: Boolean
    val partial: StateFlow<String>
    val committed: StateFlow<List<String>>
    /** Everything recognized so far — committed segments plus the live partial. */
    val transcript: String
    fun start()
    /** Graceful stop — awaits the final result (bounded), then destroys. */
    suspend fun stop()
    /** Immediate teardown for cancel paths — drops the tail of the take. */
    fun destroyNow()
}

/**
 * Live speech-to-text using Android's SpeechRecognizer — on-device
 * recognition only, so speech never leaves the device. When on-device
 * recognition is unavailable, [available] is false and the app falls
 * back to audio-only capture.
 *
 * BE-15: the recognizer is per-session. Callbacks close over a [Session]
 * token — never over a mutable field — so a late callback from a destroyed
 * recognizer can't corrupt the next session or resurrect a finished one.
 * [stop] awaits the session's final onResults/onError on a bounded
 * semaphore instead of sleeping blindly, so the tail of the take is
 * transcribed as fast as the recognizer delivers it.
 */
class SpeechTranscriber(private val context: Context) : LiveTranscriber {

    /**
     * One recognizer's lifecycle token. Every callback guards on [active];
     * [done] completes — normally, never exceptionally — the moment the
     * session should end (final result/error while finishing, or a fatal
     * error mid-take), which is what [stop] awaits.
     */
    private inner class Session(val recognizer: SpeechRecognizer) {
        @Volatile var active = true
        @Volatile var finishing = false
        /** Back-to-back benign errors — reset by any real speech event. */
        @Volatile var consecutiveErrors = 0
        val done = CompletableDeferred<Unit>()
    }

    private var session: Session? = null

    private val _partial = MutableStateFlow("")
    override val partial: StateFlow<String> = _partial

    private val _committed = MutableStateFlow<List<String>>(emptyList())
    override val committed: StateFlow<List<String>> = _committed

    /**
     * The last non-benign error code ([SpeechRecognizer.ERROR_*]), or null.
     * Concrete-class diagnostic surface — [LiveTranscriber] stays stable for
     * test fakes; callers already see the failure via an empty transcript.
     */
    private val _recognitionError = MutableStateFlow<Int?>(null)
    val recognitionError: StateFlow<Int?> = _recognitionError

    override val transcript: String
        get() = (_committed.value + _partial.value)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()

    /** True only when on-device recognition exists — voice never leaves the device. */
    override val available: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isRecognitionAvailable(context) &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    override fun start() {
        if (!available) return
        teardown() // a previous session dies before a new recognizer spawns
        _partial.value = ""
        _committed.value = emptyList()
        _recognitionError.value = null

        // On-device only — a network recognizer would send speech off-device.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val s = Session(SpeechRecognizer.createOnDeviceSpeechRecognizer(context))
        s.recognizer.setRecognitionListener(listenerFor(s))
        session = s
        listen(s)
    }

    /**
     * Stops listening and awaits the final onResults/onError — bounded by
     * [FINAL_RESULT_TIMEOUT_MS] so a wedged recognizer can't stall the save
     * pipeline — then destroys the session even if the await is cancelled.
     */
    override suspend fun stop() {
        val s = session ?: return
        s.finishing = true
        runCatching { s.recognizer.stopListening() }
        try {
            withTimeoutOrNull(FINAL_RESULT_TIMEOUT_MS) { s.done.await() }
        } finally {
            // The mic must be freed even if this coroutine was cancelled.
            withContext(NonCancellable) { teardown(s) }
        }
    }

    /** Immediate teardown for cancel paths — drops the tail of the take. */
    override fun destroyNow() {
        teardown()
    }

    private fun listenerFor(s: Session) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (!s.active) return
            // A live pass proves the recognizer is healthy — reset the streak.
            s.consecutiveErrors = 0
        }

        override fun onBeginningOfSpeech() {
            if (s.active) s.consecutiveErrors = 0
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            if (!s.active) return
            val benign = error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (benign && !s.finishing && s.consecutiveErrors < MAX_CONSECUTIVE_ERRORS) {
                // A finished pass while the take continues — keep listening.
                // Each pass spans seconds, so the bound still tolerates well
                // over a minute of pure silence before giving up.
                s.consecutiveErrors++
                listen(s)
            } else {
                if (!benign) _recognitionError.value = error
                s.done.complete(Unit)
                if (!s.finishing) {
                    // Fatal mid-take (permissions, hardware, service death):
                    // free the mic now — committed segments stay as the
                    // transcript and the audio keeps recording regardless.
                    teardown(s)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            if (!s.active) return
            s.consecutiveErrors = 0
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotBlank()) {
                _committed.value = _committed.value + text
            }
            _partial.value = ""
            if (s.finishing) {
                s.done.complete(Unit)
            } else {
                // Keep listening — a voice note can outlive one pass.
                listen(s)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!s.active) return
            _partial.value = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
        }
    }

    private fun listen(s: Session) {
        if (!s.active) return
        runCatching { s.recognizer.startListening(recognizeIntent()) }
    }

    private fun recognizeIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Explicit locale — without it the recognizer picks per-service.
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }

    /**
     * Deactivates and destroys [s] (default: the current session). Idempotent;
     * completing [Session.done] first unblocks any awaiting [stop] cleanly.
     */
    private fun teardown(s: Session? = session) {
        s ?: return
        s.active = false
        s.done.complete(Unit)
        runCatching { s.recognizer.stopListening() }
        runCatching { s.recognizer.destroy() }
        if (session === s) session = null
    }

    private companion object {
        /** Upper bound for awaiting the final result after stopListening. */
        const val FINAL_RESULT_TIMEOUT_MS = 5_000L

        /** Benign passes tolerated back-to-back before the session gives up. */
        const val MAX_CONSECUTIVE_ERRORS = 20
    }
}
