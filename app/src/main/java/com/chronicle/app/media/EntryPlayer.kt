package com.chronicle.app.media

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
) {

    private var player: MediaPlayer? = null
    private var ticker: Job? = null

    private val _playingPath = MutableStateFlow<String?>(null)
    val playingPath: StateFlow<String?> = _playingPath

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

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
        try {
            p.start()
        } catch (_: Exception) {
            p.release()
            player = null
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
}
