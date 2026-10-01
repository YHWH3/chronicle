package com.chronicle.app.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import com.chronicle.app.core.UiMessage
import com.chronicle.app.core.UiMessageBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Plays a recorded voice note; exposes playing state and progress for the UI. */
class EntryPlayer(
    private val scope: CoroutineScope,
    private val uiMessages: UiMessageBus? = null,
    /**
     * Supplies AudioManager for a transient-may-duck focus request around
     * playback. Optional so the player stays headless in unit tests — with
     * no Context every focus path is skipped.
     */
    private val context: Context? = null,
) {

    private var player: MediaPlayer? = null
    private var ticker: Job? = null

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    /** True while the player sits paused because focus was lent out. */
    private var pausedForFocus = false

    private val _playingPath = MutableStateFlow<String?>(null)
    val playingPath: StateFlow<String?> = _playingPath

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

    /**
     * A transient loss (call, navigation prompt) pauses; GAIN resumes.
     * A permanent loss is a full stop — the same teardown the UI would do.
     */
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> stop()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> runCatching {
                if (player?.isPlaying == true) {
                    player?.pause()
                    pausedForFocus = true
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (pausedForFocus) {
                pausedForFocus = false
                runCatching { player?.start() }
            }
        }
    }

    fun toggle(path: String) {
        if (_playingPath.value == path) {
            stop()
        } else {
            play(path)
        }
    }

    fun play(path: String) {
        stopInternal()
        val p = MediaPlayer()
        try {
            p.setDataSource(path)
            p.prepare()
        } catch (_: Exception) {
            // Missing or corrupt file — release and tell the user quietly.
            p.release()
            uiMessages?.send(UiMessage.PlaybackFailed)
            return
        }
        // Callbacks carry their MediaPlayer — a queued event from a player a
        // newer play() already replaced must not tear the new one down.
        p.setOnCompletionListener { mp ->
            if (mp === player) stopInternal()
        }
        p.setOnErrorListener { mp, _, _ ->
            // Returning true = consumed; we've already torn everything down.
            if (mp === player) {
                stopInternal()
                uiMessages?.send(UiMessage.PlaybackFailed)
            }
            true
        }
        player = p
        // Grab focus before the first frame so a call/duck-channel app
        // preempts us instead of mixing over (BE-17).
        requestAudioFocus()
        try {
            p.start()
        } catch (_: Exception) {
            // stopInternal covers release + focus abandon + state reset.
            stopInternal()
            uiMessages?.send(UiMessage.PlaybackFailed)
            return
        }
        _playingPath.value = path
        // Ticker and all MediaPlayer calls live on the main dispatcher so
        // reads never race a stop()/release() from the UI thread.
        ticker = scope.launch(Dispatchers.Main.immediate) {
            while (isActive) {
                val mp = player ?: break
                // duration/currentPosition throw on a released instance —
                // if a release snuck past the null check, just drop the tick.
                _progress.value = runCatching {
                    if (mp.duration > 0) mp.currentPosition / mp.duration.toFloat() else 0f
                }.getOrDefault(0f)
                delay(120)
            }
        }
    }

    fun stop() = stopInternal()

    private fun stopInternal() {
        abandonAudioFocus()
        ticker?.cancel()
        player?.let { p ->
            runCatching {
                if (p.isPlaying) p.stop()
            }
            p.release()
        }
        player = null
        _playingPath.value = null
        _progress.value = 0f
    }

    /**
     * Requests AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK for the duration of one
     * playback — voice notes duck under prompts rather than insisting. The
     * grant result is advisory: a denial doesn't gate playback (the user
     * tapped their own recording), the listener still handles losses.
     */
    private fun requestAudioFocus() {
        val ctx = context ?: return
        val manager = audioManager
            ?: ctx.getSystemService(AudioManager::class.java).also { audioManager = it }
            ?: return
        val request = AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = request
        pausedForFocus = false
        runCatching { manager.requestAudioFocus(request) }
    }

    /** Every release path ends the focus hold so the ducking app recovers. */
    private fun abandonAudioFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        pausedForFocus = false
        runCatching { audioManager?.abandonAudioFocusRequest(request) }
    }
}
