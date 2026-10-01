package com.chronicle.app.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chronicle.app.core.OperationResult
import com.chronicle.app.core.UiMessage
import com.chronicle.app.core.UiMessageBus
import com.chronicle.app.core.safeSuspend
import com.chronicle.app.data.JournalRepository
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
)

class JournalViewModel(
    private val journalRepository: JournalRepository,
    private val player: EntryPlayer,
    private val wavTranscriber: WavTranscriber,
    private val uiMessages: UiMessageBus,
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

    fun delete(entry: JournalEntry) {
        viewModelScope.launch {
            try {
                if (player.playingPath.value == entry.audioPath) player.stop()
                journalRepository.delete(entry.id)
                entry.audioPath?.let { path ->
                    runCatching { java.io.File(path).delete() }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                uiMessages.send(UiMessage.SaveFailed)
            }
        }
    }

    override fun onCleared() = player.stop()
}
