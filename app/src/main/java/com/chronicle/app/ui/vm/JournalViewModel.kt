package com.chronicle.app.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chronicle.app.core.OperationResult
import com.chronicle.app.core.UiMessage
import com.chronicle.app.core.UiMessageBus
import com.chronicle.app.core.safeSuspend
import com.chronicle.app.data.JournalRepository
import com.chronicle.app.data.TaskRepository
import com.chronicle.app.data.db.EntryEntity
import com.chronicle.app.data.db.TRANSCRIPT_FAILED
import com.chronicle.app.data.db.TRANSCRIPT_PENDING
import com.chronicle.app.data.db.TRANSCRIPT_READY
import com.chronicle.app.domain.JournalEntry
import com.chronicle.app.domain.toDomain
import com.chronicle.app.media.EntryPlayer
import com.chronicle.app.media.WavTranscriber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalDate

// ————— Journal —————

data class JournalDay(val day: LocalDate, val entries: List<JournalEntry>)

data class JournalUiState(
    val days: List<JournalDay> = emptyList(),
    val playingPath: String? = null,
    val progress: Float = 0f,
    /** Parakeet pack installed — FAILED entries can offer "Transcribe again". */
    val canRetranscribe: Boolean = false,
    /** Entries currently being re-transcribed — guards double-taps. */
    val transcribingIds: Set<Long> = emptySet(),
    /**
     * The most recently deleted row, held for undo. The DB row is gone; the
     * audio file still exists until the undo window closes.
     */
    val pendingDelete: EntryEntity? = null,
)

class JournalViewModel(
    private val journalRepository: JournalRepository,
    private val player: EntryPlayer,
    private val wavTranscriber: WavTranscriber,
    private val uiMessages: UiMessageBus,
    /**
     * Nullable so existing call sites compile before wiring lands —
     * sourceEntryId detachment activates once the nav host passes the
     * task repository in.
     */
    private val taskRepository: TaskRepository? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(JournalUiState())
    val state: StateFlow<JournalUiState> = _state

    init {
        viewModelScope.launch {
            journalRepository.observeAll().collect { list ->
                val grouped = list.map { it.toDomain() }
                    .groupBy { it.day }
                    .toSortedMap(compareByDescending { it })
                    .map { (day, entries) -> JournalDay(day, entries) }
                _state.value = _state.value.copy(
                    days = grouped,
                    canRetranscribe = wavTranscriber.available,
                )
            }
        }
        viewModelScope.launch {
            combine(player.playingPath, player.progress) { p, pr -> p to pr }
                .collect { (p, pr) ->
                    _state.value = _state.value.copy(playingPath = p, progress = pr)
                }
        }
    }

    fun togglePlay(path: String) = player.toggle(path)

    /**
     * Re-runs Parakeet on a take whose transcription failed. The audio file
     * is untouched; on success the transcript and READY status land together.
     */
    fun retryTranscription(entry: JournalEntry) {
        val path = entry.audioPath ?: return
        if (!wavTranscriber.available) return
        if (entry.id in _state.value.transcribingIds) return
        _state.value = _state.value.copy(
            transcribingIds = _state.value.transcribingIds + entry.id,
        )
        viewModelScope.launch {
            try {
                journalRepository.updateTranscriptStatus(entry.id, TRANSCRIPT_PENDING)
                when (val r = safeSuspend { wavTranscriber.transcribe(java.io.File(path)) }) {
                    is OperationResult.Ok -> {
                        if (r.value.isBlank()) {
                            journalRepository.updateTranscript(entry.id, "", TRANSCRIPT_FAILED)
                            uiMessages.send(UiMessage.TranscriptionFailed)
                        } else {
                            journalRepository.updateTranscript(entry.id, r.value, TRANSCRIPT_READY)
                        }
                    }
                    is OperationResult.Failed -> {
                        journalRepository.updateTranscriptStatus(entry.id, TRANSCRIPT_FAILED)
                        uiMessages.send(UiMessage.TranscriptionFailed)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                uiMessages.send(UiMessage.SaveFailed)
            } finally {
                _state.value = _state.value.copy(
                    transcribingIds = _state.value.transcribingIds - entry.id,
                )
            }
        }
    }

    /**
     * Soft delete: the row disappears at once but the entity is kept for
     * undo and the audio file is NOT removed yet. The undo window closes —
     * and the file is finally deleted — on the next delete or when the VM
     * is cleared (leaving the screen). Chosen over a timer: there is no
     * clock to race, and a take can never be lost while its undo is shown.
     */
    fun delete(entry: JournalEntry) {
        viewModelScope.launch {
            try {
                if (player.playingPath.value == entry.audioPath) player.stop()
                // Closing the previous undo window commits its audio file.
                discardPendingFile()
                val held = journalRepository.byId(entry.id)
                journalRepository.delete(entry.id)
                // Intentions extracted from the entry stand on their own —
                // just stop pointing at a row that's gone.
                taskRepository?.clearSourceEntry(entry.id)
                _state.value = _state.value.copy(pendingDelete = held)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                uiMessages.send(UiMessage.SaveFailed)
            }
        }
    }

    /**
     * Restores the soft-deleted row — same id, same audio path, transcript
     * status untouched. Tasks that referenced it stay detached (their
     * sourceEntryId was cleared on delete and isn't resurrected).
     */
    fun undoDelete() {
        val held = _state.value.pendingDelete ?: return
        _state.value = _state.value.copy(pendingDelete = null)
        viewModelScope.launch {
            try {
                journalRepository.restore(held)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                uiMessages.send(UiMessage.SaveFailed)
            }
        }
    }

    /** The undo window is over — the kept audio file can finally go. */
    private fun discardPendingFile() {
        val held = _state.value.pendingDelete ?: return
        _state.value = _state.value.copy(pendingDelete = null)
        held.audioPath?.let { path ->
            runCatching { java.io.File(path).delete() }
        }
    }

    override fun onCleared() {
        player.stop()
        discardPendingFile()
    }
}
