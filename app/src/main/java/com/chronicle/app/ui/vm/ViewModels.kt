package com.chronicle.app.ui.vm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chronicle.app.data.JournalRepository
import com.chronicle.app.data.PrefsRepository
import com.chronicle.app.data.TaskRepository
import com.chronicle.app.data.ThemeMode
import com.chronicle.app.domain.JournalEntry
import com.chronicle.app.domain.Story
import com.chronicle.app.domain.StoryComposer
import com.chronicle.app.domain.TaskExtractor
import com.chronicle.app.domain.toDomain
import com.chronicle.app.media.EntryPlayer
import com.chronicle.app.media.SpeechTranscriber
import com.chronicle.app.media.VoiceRecorder
import com.chronicle.app.reminders.ReminderScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import com.chronicle.app.data.db.TaskEntity

fun Long.asLocalDateTime(): LocalDateTime =
    LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(this), ZoneId.systemDefault())

fun LocalDateTime.toEpochMs(): Long =
    atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

// ————— Today —————

enum class CaptureMode { SPEAK, WRITE }

enum class RecordingState { IDLE, RECORDING }

data class TodayUiState(
    val mode: CaptureMode = CaptureMode.SPEAK,
    val recording: RecordingState = RecordingState.IDLE,
    val amplitudes: List<Float> = emptyList(),
    val elapsedMs: Long = 0,
    val liveTranscript: String = "",
    val draftText: String = "",
    val speechAvailable: Boolean = true,
    val micPermissionGranted: Boolean = false,
    /** One-shot confirmation shown after a save: "Kept — 2 reminders set". */
    val confirmation: String? = null,
    val todaysEntries: List<JournalEntry> = emptyList(),
)

class TodayViewModel(
    private val context: Context,
    private val journalRepository: JournalRepository,
    private val taskRepository: TaskRepository,
    private val voiceRecorder: VoiceRecorder,
    private val transcriber: SpeechTranscriber,
) : ViewModel() {

    private val _state = MutableStateFlow(TodayUiState(speechAvailable = transcriber.available))
    val state: StateFlow<TodayUiState> = _state

    init {
        viewModelScope.launch {
            journalRepository.observeDay(LocalDate.now()).collect { list ->
                _state.value = _state.value.copy(
                    todaysEntries = list.reversed().map { it.toDomain() },
                )
            }
        }
        viewModelScope.launch {
            combine(voiceRecorder.amplitudes, voiceRecorder.elapsedMs) { a, e -> a to e }
                .collect { (a, e) ->
                    _state.value = _state.value.copy(amplitudes = a, elapsedMs = e)
                }
        }
        viewModelScope.launch {
            combine(transcriber.committed, transcriber.partial) { c, p ->
                (c + p).filter { it.isNotBlank() }.joinToString(" ").trim()
            }.collect { t -> _state.value = _state.value.copy(liveTranscript = t) }
        }
    }

    fun setMode(mode: CaptureMode) {
        if (_state.value.recording == RecordingState.RECORDING) return
        _state.value = _state.value.copy(mode = mode, confirmation = null)
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
        } else {
            voiceRecorder.start()
            if (transcriber.available) transcriber.start()
            _state.value = _state.value.copy(
                recording = RecordingState.RECORDING,
                liveTranscript = "",
                confirmation = null,
            )
        }
    }

    fun cancelRecording() {
        voiceRecorder.cancel()
        transcriber.stop()
        _state.value = _state.value.copy(
            recording = RecordingState.IDLE,
            liveTranscript = "",
        )
    }

    private fun finishRecording() {
        val take = voiceRecorder.stop()
        transcriber.stop()
        val transcript = _state.value.liveTranscript.ifBlank { transcriber.transcript }
        _state.value = _state.value.copy(recording = RecordingState.IDLE, liveTranscript = "")

        viewModelScope.launch {
            val (audioPath, duration) = take?.let { it.first.absolutePath to it.second } ?: (null to 0L)
            if (transcript.isBlank() && audioPath == null) return@launch
            val entryId = journalRepository.addVoice(transcript, audioPath, duration)
            val created = materializeTasks(transcript, entryId)
            _state.value = _state.value.copy(confirmation = confirmationText(created))
        }
    }

    fun saveDraft() {
        val text = _state.value.draftText.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            val entryId = journalRepository.addText(text)
            val created = materializeTasks(text, entryId)
            _state.value = _state.value.copy(draftText = "", confirmation = confirmationText(created))
        }
    }

    /** Turns the captured words into reminders. Returns how many were scheduled. */
    private suspend fun materializeTasks(text: String, entryId: Long): Int {
        val extracted = TaskExtractor.extract(text, LocalDateTime.now())
        var reminders = 0
        for (task in extracted) {
            val remindMs = task.remindAt?.toEpochMs()
            val id = taskRepository.add(task.title, remindMs, entryId)
            if (remindMs != null && remindMs > System.currentTimeMillis()) {
                ReminderScheduler.scheduleTask(context, id, task.title, remindMs)
                reminders++
            }
        }
        return reminders
    }

    private fun confirmationText(reminders: Int): String = when (reminders) {
        0 -> "Kept."
        1 -> "Kept — 1 reminder set."
        else -> "Kept — $reminders reminders set."
    }

    override fun onCleared() {
        if (_state.value.recording == RecordingState.RECORDING) {
            voiceRecorder.cancel()
            transcriber.stop()
        }
    }
}

// ————— Journal —————

data class JournalDay(val day: LocalDate, val entries: List<JournalEntry>)

data class JournalUiState(
    val days: List<JournalDay> = emptyList(),
    val playingPath: String? = null,
    val progress: Float = 0f,
)

class JournalViewModel(
    private val journalRepository: JournalRepository,
    private val player: EntryPlayer,
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
                _state.value = _state.value.copy(days = grouped)
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

    fun delete(entry: JournalEntry) {
        viewModelScope.launch { journalRepository.delete(entry.id) }
    }

    override fun onCleared() = player.stop()
}

// ————— Story —————

data class StoryUiState(
    val story: Story? = null,
    val selectedChapter: Int? = null,
)

class StoryViewModel(private val journalRepository: JournalRepository) : ViewModel() {

    private val _state = MutableStateFlow(StoryUiState())
    val state: StateFlow<StoryUiState> = _state

    init {
        viewModelScope.launch {
            journalRepository.observeAll().collect { list ->
                val story = StoryComposer.compose(
                    list.map { it.toDomain() },
                    LocalDate.now(),
                )
                _state.value = _state.value.copy(story = story)
            }
        }
    }

    fun selectChapter(number: Int?) {
        _state.value = _state.value.copy(selectedChapter = number)
    }
}

// ————— Tasks —————

data class TasksUiState(
    val open: List<TaskEntity> = emptyList(),
    val done: List<TaskEntity> = emptyList(),
    val draft: String = "",
    val draftReminder: ReminderChoice = ReminderChoice.NONE,
)

enum class ReminderChoice(val label: String) {
    NONE("No reminder"),
    IN_ONE_HOUR("In an hour"),
    TONIGHT("Tonight"),
    TOMORROW("Tomorrow"),
    ;

    fun resolve(now: LocalDateTime): LocalDateTime? = when (this) {
        NONE -> null
        IN_ONE_HOUR -> now.plusHours(1)
        TONIGHT -> now.withHour(20).withMinute(0).let { if (it <= now) it.plusDays(1) else it }
        TOMORROW -> now.plusDays(1).withHour(9).withMinute(0)
    }
}

class TasksViewModel(
    private val context: Context,
    private val taskRepository: TaskRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TasksUiState())
    val state: StateFlow<TasksUiState> = _state

    init {
        viewModelScope.launch {
            taskRepository.observeAll().collect { list ->
                _state.value = _state.value.copy(
                    open = list.filter { !it.done },
                    done = list.filter { it.done },
                )
            }
        }
    }

    fun setDraft(text: String) {
        _state.value = _state.value.copy(draft = text)
    }

    fun setReminderChoice(choice: ReminderChoice) {
        _state.value = _state.value.copy(draftReminder = choice)
    }

    fun addTask() {
        val title = _state.value.draft.trim()
        if (title.isEmpty()) return
        val remindAt = _state.value.draftReminder.resolve(LocalDateTime.now())
        viewModelScope.launch {
            val remindMs = remindAt?.toEpochMs()
            val id = taskRepository.add(title, remindMs)
            if (remindMs != null) {
                ReminderScheduler.scheduleTask(context, id, title, remindMs)
            }
            _state.value = _state.value.copy(draft = "", draftReminder = ReminderChoice.NONE)
        }
    }

    fun toggleDone(task: TaskEntity) {
        viewModelScope.launch {
            val nowDone = !task.done
            taskRepository.setDone(task, nowDone)
            if (nowDone) {
                ReminderScheduler.cancelTask(context, task.id)
                com.chronicle.app.reminders.Notifier.cancel(context, task.id)
            } else {
                task.remindAt?.let {
                    if (it > System.currentTimeMillis()) {
                        ReminderScheduler.scheduleTask(context, task.id, task.title, it)
                    }
                }
            }
        }
    }

    fun delete(task: TaskEntity) {
        viewModelScope.launch {
            ReminderScheduler.cancelTask(context, task.id)
            taskRepository.delete(task.id)
        }
    }
}

// ————— Settings —————

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dailyNudgeEnabled: Boolean = false,
    val dailyNudgeMinutes: Int = 20 * 60,
)

class SettingsViewModel(
    private val context: Context,
    private val prefsRepository: PrefsRepository,
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = prefsRepository.prefs
        .map { SettingsUiState(it.themeMode, it.dailyNudgeEnabled, it.dailyNudgeMinutes) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { prefsRepository.setThemeMode(mode) }
    }

    fun setDailyNudge(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepository.setDailyNudge(enabled)
            val minutes = state.value.dailyNudgeMinutes
            if (enabled) ReminderScheduler.scheduleDailyNudge(context, minutes)
            else ReminderScheduler.cancelDailyNudge(context)
        }
    }

    fun setDailyNudgeMinutes(minutes: Int) {
        viewModelScope.launch {
            prefsRepository.setDailyNudgeMinutes(minutes)
            if (state.value.dailyNudgeEnabled) {
                ReminderScheduler.scheduleDailyNudge(context, minutes)
            }
        }
    }
}
