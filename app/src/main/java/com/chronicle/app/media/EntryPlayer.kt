package com.chronicle.app.media

import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Plays a recorded voice note; exposes playing state and progress for the UI. */
class EntryPlayer(private val scope: CoroutineScope) {

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
            p.release()
            return
        }
        p.setOnCompletionListener { stopInternal() }
        player = p
        _playingPath.value = path
        p.start()
        // Ticker and all MediaPlayer calls live on the main dispatcher so
        // reads never race a stop()/release() from the UI thread.
        ticker = scope.launch(Dispatchers.Main.immediate) {
            while (isActive) {
                val mp = player ?: break
                _progress.value = if (mp.duration > 0) mp.currentPosition / mp.duration.toFloat() else 0f
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
