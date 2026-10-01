package com.chronicle.app.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chronicle.app.core.OperationResult
import com.chronicle.app.core.UiMessage
import com.chronicle.app.core.UiMessageBus
import com.chronicle.app.core.safeSuspend
import com.chronicle.app.core.sanitizeReason
import com.chronicle.app.data.JournalRepository
import com.chronicle.app.data.PrefsSource
import com.chronicle.app.data.SttEngine
import com.chronicle.app.data.TaskRepository
import com.chronicle.app.data.db.TRANSCRIPT_FAILED
import com.chronicle.app.data.db.TRANSCRIPT_NOT_REQUESTED
import com.chronicle.app.data.db.TRANSCRIPT_READY
import com.chronicle.app.domain.JournalEntry
import com.chronicle.app.domain.toDomain
import com.chronicle.app.media.LiveTranscriber
import com.chronicle.app.media.RecordStart
import com.chronicle.app.media.TakeRecorder
import com.chronicle.app.media.WavTranscriber
import com.chronicle.app.mind.DeterministicMind
import com.chronicle.app.mind.MindSource
import com.chronicle.app.reminders.ReminderGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

// ————— Today —————

enum class CaptureMode { SPEAK, WRITE }

enum class RecordingState { IDLE, RECORDING }

enum class ProcessingStep { TRANSCRIBING, SAVING, READING }

/** An intention the mind pulled out of a capture, for the Kept card. */
data class KeptIntention(val title: String, val remindAt: LocalDateTime?)

/** What the save produced — drives the Kept phase. */
data class KeptSummary(
    val reminders: Int,
    val intentions: List<KeptIntention>,
    val reflection: String?,
    val engineLabel: String,
    val entryId: Long,
    /** The take is on disk but speech-to-text failed — card notes it quietly. */
    val transcriptFailed: Boolean = false,
)

data class TodayUiState(
    val mode: CaptureMode = CaptureMode.SPEAK,
    val recording: RecordingState = RecordingState.IDLE,
    val amplitudes: List<Float> = emptyList(),
    val elapsedMs: Long = 0,
    val liveTranscript: String = "",
    val draftText: String = "",
    val speechAvailable: Boolean = true,
    val micPermissionGranted: Boolean = false,
    /** Set after a save completes — the Kept phase. */
    val kept: KeptSummary? = null,
    val todaysEntries: List<JournalEntry> = emptyList(),
    /** A save is in flight — guards against double-taps duplicating entries. */
    val saving: Boolean = false,
    /** Which speech-to-text engine the user selected. */
    val sttEngine: SttEngine = SttEngine.SYSTEM,
    /** True while the mind model or transcription is chewing on the take. */
    val processing: Boolean = false,
    /** Which leg of the capture pipeline is running, for the step list. */
    val processingStep: ProcessingStep? = null,
    /** Name of the mind backend that handled the last capture (for the hint line). */
    val mindLabel: String = "",
)

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(
    private val journalRepository: JournalRepository,
    private val taskRepository: TaskRepository,
    private val prefsRepository: PrefsSource,
    private val voiceRecorder: TakeRecorder,
    private val wavRecorder: TakeRecorder,
    private val transcriber: LiveTranscriber,
    private val parakeetTranscriber: WavTranscriber,
    private val mindRepository: MindSource,
    private val reminders: ReminderGateway,
    private val uiMessages: UiMessageBus,
    /**
     * The "what day is it" source — default rolls over at local midnight.
     * Tests inject a static flow: the delay-until-midnight loop would keep a
     * test scheduler busy forever.
     */
    private val dateFlow: Flow<LocalDate>? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(TodayUiState(speechAvailable = transcriber.available))
    val state: StateFlow<TodayUiState> = _state

    /** Emits today's date, sleeps until the next local midnight, repeats —
     *  so "Earlier today" rolls over instead of freezing on the open date. */
    private fun currentDateFlow(): Flow<LocalDate> = flow {
        while (true) {
            val now = LocalDateTime.now()
            emit(now.toLocalDate())
            delay(millisUntilNextMidnight(now) + 50L)
        }
    }

    init {
        viewModelScope.launch {
            (dateFlow ?: currentDateFlow())
                .flatMapLatest { journalRepository.observeDay(it) }
                .collect { list ->
                    _state.value = _state.value.copy(
                        todaysEntries = list.reversed().map { it.toDomain() },
                    )
                }
        }
        viewModelScope.launch {
            combine(
                voiceRecorder.amplitudes, voiceRecorder.elapsedMs,
                wavRecorder.amplitudes, wavRecorder.elapsedMs,
            ) { a1, e1, a2, e2 ->
                if (recordingRecorder === wavRecorder) a2 to e2 else a1 to e1
            }.collect { (a, e) ->
                _state.value = _state.value.copy(amplitudes = a, elapsedMs = e)
            }
        }
        viewModelScope.launch {
            combine(transcriber.committed, transcriber.partial) { c, p ->
                (c + p).filter { it.isNotBlank() }.joinToString(" ").trim()
            }.collect { t -> _state.value = _state.value.copy(liveTranscript = t) }
        }
        viewModelScope.launch {
            prefsRepository.prefs.collect { p ->
                _state.value = _state.value.copy(sttEngine = p.sttEngine)
            }
        }
    }

    /** The recorder for the selected engine — wav when Parakeet is installed. */
    private fun activeRecorder(): TakeRecorder =
        if (_state.value.sttEngine == SttEngine.PARAKEET && parakeetTranscriber.available) {
            wavRecorder
        } else {
            voiceRecorder
        }

    /** The recorder handling the current take — chosen at record start. */
    private var recordingRecorder: TakeRecorder? = null

    fun setMode(mode: CaptureMode) {
        if (_state.value.recording == RecordingState.RECORDING) return
        _state.value = _state.value.copy(mode = mode, kept = null)
    }

    fun setDraft(text: String) {
        _state.value = _state.value.copy(draftText = text)
    }

    fun onMicPermissionResult(granted: Boolean) {
        _state.value = _state.value.copy(micPermissionGranted = granted)
    }

    fun toggleRecording() {
        if (_state.value.recording == RecordingState.RECORDING) {
            finishRecording()
            return
        }
        val r = activeRecorder()
        when (r.start()) {
            is RecordStart.Started -> {
                recordingRecorder = r
                if (r !== wavRecorder && transcriber.available) transcriber.start()
                _state.value = _state.value.copy(
                    recording = RecordingState.RECORDING,
                    liveTranscript = "",
                    kept = null,
                    micPermissionGranted = true,
                )
            }
            RecordStart.PermissionMissing -> {
                recordingRecorder = null
                _state.value = _state.value.copy(micPermissionGranted = false)
                uiMessages.send(UiMessage.MicPermanentlyDenied)
            }
            else -> {
                // Busy/Unavailable/InitFailed — stay IDLE, offer a retry.
                recordingRecorder = null
                uiMessages.send(UiMessage.RecorderUnavailable { toggleRecording() })
            }
        }
    }

    fun cancelRecording() {
        val r = recordingRecorder ?: activeRecorder()
        recordingRecorder = null
        r.cancel()
        if (r !== wavRecorder) transcriber.destroyNow()
        _state.value = _state.value.copy(
            recording = RecordingState.IDLE,
            liveTranscript = "",
        )
    }

    fun dismissKept() {
        _state.value = _state.value.copy(kept = null)
    }

    private fun finishRecording() {
        if (_state.value.saving) return
        val recorder = recordingRecorder ?: activeRecorder()
        recordingRecorder = null
        val parakeetTake = recorder === wavRecorder
        val processingStart = System.currentTimeMillis()
        _state.value = _state.value.copy(
            recording = RecordingState.IDLE,
            saving = true,
            processing = true,
            processingStep = ProcessingStep.TRANSCRIBING,
        )

        viewModelScope.launch {
            try {
                // Suspend stop — writers join before the device is released.
                val take = recorder.stop()

                // ————— Transcribe —————
                var transcript = ""
                var transcriptStatus = TRANSCRIPT_NOT_REQUESTED
                if (parakeetTake) {
                    take?.first?.let { file ->
                        when (val r = safeSuspend { parakeetTranscriber.transcribe(file) }) {
                            is OperationResult.Ok -> {
                                transcript = r.value
                                transcriptStatus = TRANSCRIPT_READY
                            }
                            is OperationResult.Failed -> {
                                // The WAV stays on disk; FAILED lets Journal
                                // offer "Transcribe again" later.
                                transcriptStatus = TRANSCRIPT_FAILED
                                uiMessages.send(UiMessage.TranscriptionFailed)
                            }
                        }
                    }
                } else {
                    safeSuspend { transcriber.stop() }
                    transcript = transcriber.transcript.ifBlank { _state.value.liveTranscript }
                    transcriptStatus =
                        if (transcript.isBlank()) TRANSCRIPT_NOT_REQUESTED else TRANSCRIPT_READY
                }

                val (audioPath, duration) =
                    take?.let { it.first.absolutePath to it.second } ?: (null to 0L)
                if (transcript.isBlank() && audioPath == null) {
                    awaitDwell(processingStart)
                    _state.value = _state.value.copy(
                        liveTranscript = "",
                        saving = false,
                        processing = false,
                        processingStep = null,
                    )
                    return@launch
                }

                // ————— Save — the entry lands FIRST so nothing below loses it. —————
                _state.value = _state.value.copy(processingStep = ProcessingStep.SAVING)
                val entryId = try {
                    journalRepository.addVoice(transcript, audioPath, duration, transcriptStatus)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    uiMessages.send(UiMessage.SaveFailed)
                    awaitDwell(processingStart)
                    _state.value = _state.value.copy(
                        liveTranscript = "",
                        saving = false,
                        processing = false,
                        processingStep = null,
                    )
                    return@launch
                }

                // ————— Read intentions — its own step; failure keeps the entry. —————
                _state.value = _state.value.copy(processingStep = ProcessingStep.READING)
                val kept = materializeTasks(transcript, entryId, transcriptStatus)
                awaitDwell(processingStart)
                _state.value = _state.value.copy(
                    liveTranscript = "",
                    saving = false,
                    processing = false,
                    processingStep = null,
                    kept = kept,
                    mindLabel = kept.engineLabel,
                )
            } catch (ce: CancellationException) {
                _state.value = _state.value.copy(
                    liveTranscript = "",
                    saving = false,
                    processing = false,
                    processingStep = null,
                )
                throw ce
            } catch (t: Throwable) {
                uiMessages.send(UiMessage.SaveFailed)
                _state.value = _state.value.copy(
                    liveTranscript = "",
                    saving = false,
                    processing = false,
                    processingStep = null,
                )
            }
        }
    }

    fun saveDraft() {
        if (_state.value.saving) return
        val text = _state.value.draftText.trim()
        if (text.isEmpty()) return
        val processingStart = System.currentTimeMillis()
        _state.value = _state.value.copy(
            saving = true,
            processing = true,
            processingStep = ProcessingStep.SAVING,
        )
        viewModelScope.launch {
            try {
                // Entry first — a failing mind pass can never undo the save.
                val entryId = journalRepository.addText(text, TRANSCRIPT_READY)
                _state.value = _state.value.copy(processingStep = ProcessingStep.READING)
                val kept = materializeTasks(text, entryId)
                awaitDwell(processingStart)
                _state.value = _state.value.copy(
                    draftText = "",
                    saving = false,
                    processing = false,
                    processingStep = null,
                    kept = kept,
                    mindLabel = kept.engineLabel,
                )
            } catch (ce: CancellationException) {
                _state.value = _state.value.copy(
                    saving = false,
                    processing = false,
                    processingStep = null,
                )
                throw ce
            } catch (t: Throwable) {
                uiMessages.send(UiMessage.SaveFailed)
                _state.value = _state.value.copy(
                    saving = false,
                    processing = false,
                    processingStep = null,
                )
            }
        }
    }

    /** The morph Idle→Processing→Kept needs a beat — keep ≥700ms of processing. */
    private suspend fun awaitDwell(startMs: Long) {
        val remaining = 700L - (System.currentTimeMillis() - startMs)
        if (remaining > 0) kotlinx.coroutines.delay(remaining)
    }

    /**
     * Turns the captured words into intentions. Runs AFTER the entry commits —
     * one failing task never takes the others down with it, and the saved
     * entry stays regardless. Reminder logic unchanged.
     */
    private suspend fun materializeTasks(
        text: String,
        entryId: Long,
        transcriptStatus: String = TRANSCRIPT_READY,
    ): KeptSummary {
        val transcriptFailed = transcriptStatus == TRANSCRIPT_FAILED
        if (text.isBlank()) {
            // An audio-only note — nothing was read, so no engine claims credit.
            return KeptSummary(0, emptyList(), null, "", entryId, transcriptFailed)
        }
        val exec = try {
            mindRepository.extract(text, LocalDateTime.now())
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // Even the quiet parser failed — the entry is already persisted,
            // so surface the failure but don't lose the save.
            uiMessages.send(UiMessage.ExtractionFailed(sanitizeReason(t)))
            null
        }
        val engineLabel = exec?.actualLabel ?: DeterministicMind.label
        var scheduled = 0
        var dropped = 0
        val intentions = mutableListOf<KeptIntention>()
        for (task in exec?.result?.tasks.orEmpty()) {
            val remindMs = task.remindAt?.toEpochMs()
            val id = try {
                taskRepository.add(task.title, remindMs, entryId)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                dropped++
                continue
            }
            if (remindMs != null && remindMs > System.currentTimeMillis()) {
                try {
                    reminders.scheduleTask(id, task.title, remindMs)
                    scheduled++
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    // The alarm system refused — the intention itself is kept.
                }
            }
            intentions += KeptIntention(task.title, task.remindAt)
        }
        if (dropped > 0) {
            uiMessages.send(UiMessage.IntentionsPartial(dropped))
        }
        if (scheduled > 0 && !reminders.notificationsEnabled()) {
            uiMessages.send(UiMessage.NotificationsOff)
        }
        return KeptSummary(
            reminders = scheduled,
            intentions = intentions,
            reflection = exec?.result?.reflection,
            engineLabel = engineLabel,
            entryId = entryId,
            transcriptFailed = transcriptFailed,
        )
    }

    override fun onCleared() {
        if (_state.value.recording == RecordingState.RECORDING) {
            val r = recordingRecorder ?: activeRecorder()
            r.cancel()
            if (r !== wavRecorder) transcriber.destroyNow()
        }
    }
}
