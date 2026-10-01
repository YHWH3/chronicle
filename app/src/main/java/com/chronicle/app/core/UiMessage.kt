package com.chronicle.app.core

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * A one-shot, quiet message surfaced at the root of the app — a frosted pill
 * that auto-dismisses. ViewModels emit; the host collects once. Errors must
 * never die silently, but they also never interrupt: no dialogs, no toasts
 * stacking up — repeated identical messages are deduped by the host.
 */
sealed class UiMessage {

    /** The single quiet sentence shown to the user. */
    abstract val text: String

    /** Label for the trailing action affordance, if any. */
    open val actionLabel: String? get() = null

    /**
     * Runs when the action is tapped. When null and [opensAppSettings] is
     * true, the host opens this app's system settings page instead.
     */
    open val onAction: (() -> Unit)? get() = null

    /** True when the action should open the app's system settings page. */
    open val opensAppSettings: Boolean get() = false

    /** The microphone couldn't start — busy, unplugged, or driver error. */
    class RecorderUnavailable(val retry: (() -> Unit)? = null) : UiMessage() {
        override val text = "Microphone is busy or unavailable."
        override val actionLabel: String? get() = if (retry != null) "Retry" else null
        override val onAction: (() -> Unit)? get() = retry
    }

    /** RECORD_AUDIO was denied permanently — the user must flip it in Settings. */
    data object MicPermanentlyDenied : UiMessage() {
        override val text = "Microphone permission is off."
        override val actionLabel = "Settings"
        override val opensAppSettings = true
    }

    /** Speech-to-text failed; the audio file itself is safe on disk. */
    data object TranscriptionFailed : UiMessage() {
        override val text = "Transcription failed — your audio is kept."
    }

    /** Persisting an entry or a task failed outright. */
    data object SaveFailed : UiMessage() {
        override val text = "Couldn't save — try again."
    }

    /** The mind pass failed entirely; the entry is already safely stored. */
    data class ExtractionFailed(val reason: String?) : UiMessage() {
        override val text = "Couldn't read intentions — entry saved."
    }

    /** Some extracted intentions could not be persisted; [failed] of them lost. */
    data class IntentionsPartial(val failed: Int) : UiMessage() {
        override val text = "Couldn't save every intention — the entry is kept."
    }

    /** Audio playback failed (corrupt file, missing file, codec). */
    data object PlaybackFailed : UiMessage() {
        override val text = "Couldn't play that recording."
    }

    /** A reminder was stored but notifications are off — it can never fire. */
    data object NotificationsOff : UiMessage() {
        override val text = "Reminder saved — notifications are off."
        override val actionLabel = "Settings"
        override val opensAppSettings = true
    }
}

/**
 * The app-scoped channel for [UiMessage]s. One per [com.chronicle.app.AppContainer];
 * the root composable is the single collector.
 */
class UiMessageBus {

    private val _messages = MutableSharedFlow<UiMessage>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<UiMessage> = _messages

    /** Emits without suspending — safe from any call site. Drops oldest when flooded. */
    fun send(message: UiMessage) {
        _messages.tryEmit(message)
    }

    suspend fun emit(message: UiMessage) {
        _messages.emit(message)
    }
}
