package com.chronicle.app.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chronicle.app.R
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
import com.chronicle.app.mind.MindSource
import com.chronicle.app.mind.MindTask
import com.chronicle.app.reminders.ReminderGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalDateTime

// ————— Journal —————

data class JournalDay(val day: LocalDate, val entries: List<JournalEntry>)

enum class NoteOperation { CLEANUP, TASKS }

data class NoteReview(
    val entryId: Long,
    val original: String,
    val status: String,
    val operation: NoteOperation,
    val busy: Boolean = true,
    val cleaned: String = "",
    val tasks: List<MindTask> = emptyList(),
    val engineLabel: String? = null,
    val usedBasicFallback: Boolean = false,
    val errorRes: Int? = null,
)

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
    val review: NoteReview? = null,
    val cleanupUndoIds: Set<Long> = emptySet(),
)

@OptIn(ExperimentalCoroutinesApi::class) // mapLatest
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
    /** Where entity→domain→day grouping runs — O(entries) per emission (FE-07). */
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val mind: MindSource? = null,
    private val reminders: ReminderGateway? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(JournalUiState())
    val state: StateFlow<JournalUiState> = _state

    init {
        viewModelScope.launch {
            combine(journalRepository.observeAll(), journalRepository.engineLabels, journalRepository.cleanupRevisions) { entries, labels, revisions ->
                Triple(entries, labels, revisions)
            }
                .mapLatest { (list, labels, revisions) ->
                    val days = list.map { it.toDomain().copy(engineLabel = labels[it.id]) }
                        .groupBy { it.day }
                        .toSortedMap(compareByDescending { it })
                        .map { (day, entries) -> JournalDay(day, entries) }
                    days to list.filter { row ->
                        revisions[row.id]?.let { it.applied == row.transcript && it.status == row.transcriptStatus } == true
                    }.map { it.id }.toSet()
                }
                .flowOn(computeDispatcher)
                .collect { (grouped, undoIds) ->
                    _state.value = _state.value.copy(
                        days = grouped,
                        canRetranscribe = wavTranscriber.available,
                        cleanupUndoIds = undoIds,
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

    private var reviewJob: Job? = null
    private val changingNotes = mutableSetOf<Long>()

    /** Select one saved note. Preview only: no note, task, or alarm is changed. */
    fun workOnNote(entry: JournalEntry, operation: NoteOperation) {
        if (entry.transcript.isBlank() || entry.id in _state.value.transcribingIds || entry.id in changingNotes) return
        dismissReview()
        val initial = NoteReview(entry.id, entry.transcript, entry.transcriptStatus.name.lowercase(), operation)
        _state.value = _state.value.copy(review = initial)
        reviewJob = viewModelScope.launch {
            try {
                val source = mind ?: error("Smart to-dos is unavailable")
                val review = when (operation) {
                    NoteOperation.CLEANUP -> {
                        val result = source.cleanup(initial.original, LocalDateTime.now())
                        initial.copy(busy = false, cleaned = result.text, engineLabel = result.actualLabel,
                            usedBasicFallback = result.fallbackReason != null)
                    }
                    NoteOperation.TASKS -> {
                        val result = source.extract(initial.original, LocalDateTime.now())
                        // Suppress already saved tasks from this note, not unrelated notes.
                        val existing = taskRepository?.observeAll()?.first().orEmpty()
                            .filter { it.sourceEntryId == entry.id }
                        initial.copy(busy = false, tasks = result.result.tasks.distinctBy { taskKey(it.title) }
                            .filter { candidate -> existing.none { taskKey(it.title) == taskKey(candidate.title) } },
                            engineLabel = result.actualLabel, usedBasicFallback = result.fallbackReason != null)
                    }
                }
                _state.value = _state.value.copy(review = review)
            } catch (ce: CancellationException) { throw ce
            } catch (_: Throwable) {
                _state.value = _state.value.copy(review = initial.copy(busy = false,
                    errorRes = R.string.note_work_failed))
            }
        }
    }

    fun dismissReview() {
        if (_state.value.review?.entryId in changingNotes) return
        reviewJob?.cancel()
        _state.value = _state.value.copy(review = null)
    }

    fun editCleanup(text: String) {
        val review = _state.value.review ?: return
        if (review.busy) return
        _state.value = _state.value.copy(review = review.copy(cleaned = text, errorRes = null))
    }

    fun applyCleanup() {
        val review = _state.value.review ?: return
        if (review.busy || review.operation != NoteOperation.CLEANUP || review.cleaned.isBlank()) return
        if (review.cleaned == review.original) { dismissReview(); return }
        if (!changingNotes.add(review.entryId)) return
        _state.value = _state.value.copy(review = review.copy(busy = true, errorRes = null))
        viewModelScope.launch {
            try {
                if (journalRepository.applyCleanup(review.entryId, review.original, review.status, review.cleaned.trim())) {
                    _state.value = _state.value.copy(review = null)
                } else {
                    _state.value = _state.value.copy(review = review.copy(errorRes = R.string.note_changed))
                }
            } catch (ce: CancellationException) { throw ce
            } catch (_: Throwable) {
                _state.value = _state.value.copy(review = review.copy(errorRes = R.string.note_apply_failed))
            } finally { changingNotes.remove(review.entryId) }
        }
    }

    fun undoCleanup(entryId: Long) {
        if (entryId in _state.value.transcribingIds) return
        if (!changingNotes.add(entryId)) return
        viewModelScope.launch {
            try {
                if (!journalRepository.undoCleanup(entryId)) uiMessages.send(UiMessage.SaveFailed)
            } catch (ce: CancellationException) { throw ce
            } catch (_: Throwable) { uiMessages.send(UiMessage.SaveFailed)
            } finally { changingNotes.remove(entryId) }
        }
    }

    fun skipNoteTask(index: Int) {
        val review = _state.value.review ?: return
        if (review.busy) return
        _state.value = _state.value.copy(review = review.copy(tasks = review.tasks.filterIndexed { i, _ -> i != index }))
    }

    fun editNoteTask(index: Int, title: String, remindAt: Long?) {
        val review = _state.value.review ?: return
        if (review.busy || title.isBlank() || index !in review.tasks.indices) return
        _state.value = _state.value.copy(review = review.copy(tasks = review.tasks.mapIndexed { i, task ->
            if (i == index) task.copy(title = title.trim(), remindAt = remindAt?.asLocalDateTime()) else task
        }, errorRes = null))
    }

    /** The caller explicitly chooses a plain task or a reminder. No automatic alarms. */
    fun addNoteTask(index: Int, withReminder: Boolean) {
        val review = _state.value.review ?: return
        val candidate = review.tasks.getOrNull(index) ?: return
        val repository = taskRepository ?: return
        if (review.busy || review.operation != NoteOperation.TASKS || !changingNotes.add(review.entryId)) return
        _state.value = _state.value.copy(review = review.copy(busy = true, errorRes = null))
        viewModelScope.launch {
            var added = false
            try {
                val source = journalRepository.byId(review.entryId)
                check(source?.transcript == review.original && source.transcriptStatus == review.status)
                val existing = repository.observeAll().first().any {
                    it.sourceEntryId == review.entryId && taskKey(it.title) == taskKey(candidate.title)
                }
                if (!existing) {
                    val time = if (withReminder) candidate.remindAt?.takeIf { it > LocalDateTime.now() }
                        ?: error("Choose a future reminder time") else null
                    if (time != null) check(reminders != null)
                    val ms = time?.toEpochMs()
                    val id = repository.add(candidate.title, ms, review.entryId)
                    added = true
                    // Remove after persistence, even if alarm scheduling fails:
                    // retry must not duplicate an already stored task.
                    _state.value = _state.value.copy(review = review.copy(tasks = review.tasks.filterIndexed { i, _ -> i != index }))
                    if (ms != null) {
                        reminders?.scheduleTask(id, candidate.title, ms)
                        if (reminders?.notificationsEnabled() == false) uiMessages.send(UiMessage.NotificationsOff)
                    }
                } else {
                    _state.value = _state.value.copy(review = review.copy(tasks = review.tasks.filterIndexed { i, _ -> i != index }))
                }
            } catch (ce: CancellationException) { throw ce
            } catch (_: Throwable) {
                val current = _state.value.review ?: review
                _state.value = _state.value.copy(review = current.copy(busy = false,
                    errorRes = if (added) R.string.note_task_saved_check_time else R.string.note_add_failed))
            } finally {
                changingNotes.remove(review.entryId)
                _state.value.review?.let { current ->
                    _state.value = _state.value.copy(review = current.copy(busy = false))
                }
            }
        }
    }

    private fun taskKey(title: String) = com.chronicle.app.mind.MindRepository.taskKey(title)

    /**
     * Re-runs Parakeet on a take whose transcription failed. The audio file
     * is untouched; on success the transcript and READY status land together.
     */
    fun retryTranscription(entry: JournalEntry) {
        if (entry.id in changingNotes) return
        if (_state.value.review?.entryId == entry.id) dismissReview()
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
        if (entry.id in changingNotes) return
        if (_state.value.review?.entryId == entry.id) dismissReview()
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
