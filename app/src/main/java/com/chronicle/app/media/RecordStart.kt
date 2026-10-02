package com.chronicle.app.media

import java.io.File

/**
 * The outcome of asking a [TakeRecorder] to start — never throws. The UI
 * maps each failure to a recoverable message instead of crashing.
 */
sealed interface RecordStart {
    /** Recording is live; [file] is the take being written. */
    data class Started(val file: File) : RecordStart

    /** RECORD_AUDIO permission denied or revoked — fixable in Settings. */
    data object PermissionMissing : RecordStart

    /** Another app/call holds the microphone. */
    data object MicrophoneBusy : RecordStart

    /** The audio subsystem couldn't deliver a usable recorder. */
    data object Unavailable : RecordStart

    /** Construction/setup failed for a less-common reason. */
    data class InitFailed(val reason: String) : RecordStart
}
