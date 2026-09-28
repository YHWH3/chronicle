package com.chronicle.app.ui.vm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chronicle.app.data.JournalRepository
import com.chronicle.app.data.MindBackend
import com.chronicle.app.data.PrefsRepository
import com.chronicle.app.data.SttEngine
import com.chronicle.app.data.TaskRepository
import com.chronicle.app.data.ThemeMode
import com.chronicle.app.domain.JournalEntry
import com.chronicle.app.domain.Story
import com.chronicle.app.domain.StoryComposer
import com.chronicle.app.domain.toDomain
import com.chronicle.app.media.EntryPlayer
import com.chronicle.app.media.ParakeetTranscriber
import com.chronicle.app.media.SpeechTranscriber
import com.chronicle.app.media.TakeRecorder
import com.chronicle.app.media.VoiceRecorder
import com.chronicle.app.media.WavRecorder
import com.chronicle.app.mind.ChatMind
import com.chronicle.app.mind.DeterministicMind
import com.chronicle.app.mind.MindRepository
import com.chronicle.app.mind.ModelKind
import com.chronicle.app.mind.ModelState
import com.chronicle.app.mind.ModelStore
import com.chronicle.app.reminders.ReminderScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import com.chronicle.app.data.db.TaskEntity

fun Long.asLocalDateTime(): LocalDateTime =
    LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(this), ZoneId.systemDefault())

fun LocalDateTime.toEpochMs(): Long =
    atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** Millis from [now] until the next local midnight. */
internal fun millisUntilNextMidnight(now: LocalDateTime): Long =
    Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay()).toMillis()

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
    private val context: Context,
    private val journalRepository: JournalRepository,
    private val taskRepository: TaskRepository,
    private val prefsRepository: PrefsRepository,
    private val voiceRecorder: VoiceRecorder,
    private val wavRecorder: WavRecorder,
    private val transcriber: SpeechTranscriber,
    private val parakeetTranscriber: ParakeetTranscriber,
    private val mindRepository: MindRepository,
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
            currentDateFlow()
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

    private fun usesParakeet() = activeRecorder() === wavRecorder

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
        } else {
            val r = activeRecorder()
            recordingRecorder = r
            r.start()
            if (!usesParakeet() && transcriber.available) transcriber.start()
            _state.value = _state.value.copy(
                recording = RecordingState.RECORDING,
                liveTranscript = "",
                kept = null,
            )
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
        val take = recordingRecorder?.stop() ?: activeRecorder().stop()
        val processingStart = System.currentTimeMillis()
        _state.value = _state.value.copy(
            recording = RecordingState.IDLE,
            saving = true,
            processing = true,
            processingStep = ProcessingStep.TRANSCRIBING,
        )

        viewModelScope.launch {
            val parakeetTake = recordingRecorder === wavRecorder
            // Waits briefly for the final recognition results before snapshotting.
            val transcript = if (parakeetTake) {
                take?.first?.let { file ->
                    runCatching { parakeetTranscriber.transcribe(file) }.getOrElse { "" }
                }.orEmpty()
            } else {
                transcriber.stop()
                transcriber.transcript.ifBlank { _state.value.liveTranscript }
            }
            try {
                val (audioPath, duration) = take?.let { it.first.absolutePath to it.second } ?: (null to 0L)
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
                _state.value = _state.value.copy(processingStep = ProcessingStep.SAVING)
                val entryId = journalRepository.addVoice(transcript, audioPath, duration)
                _state.value = _state.value.copy(processingStep = ProcessingStep.READING)
                val kept = materializeTasks(transcript, entryId)
                awaitDwell(processingStart)
                _state.value = _state.value.copy(
                    liveTranscript = "",
                    saving = false,
                    processing = false,
                    processingStep = null,
                    kept = kept,
                    mindLabel = engine.label,
                )
            } catch (t: Throwable) {
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
                val entryId = journalRepository.addText(text)
                _state.value = _state.value.copy(processingStep = ProcessingStep.READING)
                val kept = materializeTasks(text, entryId)
                awaitDwell(processingStart)
                _state.value = _state.value.copy(
                    draftText = "",
                    saving = false,
                    processing = false,
                    processingStep = null,
                    kept = kept,
                    mindLabel = engine.label,
                )
            } catch (t: Throwable) {
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

    private var engine: com.chronicle.app.mind.MindEngine = com.chronicle.app.mind.DeterministicMind
    /** Turns the captured words into reminders. Reminder logic unchanged. */
    private suspend fun materializeTasks(
        text: String,
        entryId: Long,
    ): KeptSummary {
        if (text.isBlank()) {
            return KeptSummary(0, emptyList(), null, engine.label, entryId)
        }
        val (result, resolved) = mindRepository.extract(text, LocalDateTime.now())
        engine = resolved
        var reminders = 0
        val intentions = mutableListOf<KeptIntention>()
        for (task in result.tasks) {
            val remindMs = task.remindAt?.toEpochMs()
            val id = taskRepository.add(task.title, remindMs, entryId)
            if (remindMs != null && remindMs > System.currentTimeMillis()) {
                ReminderScheduler.scheduleTask(context, id, task.title, remindMs)
                reminders++
            }
            intentions += KeptIntention(task.title, task.remindAt)
        }
        return KeptSummary(
            reminders = reminders,
            intentions = intentions,
            reflection = result.reflection,
            engineLabel = engine.label,
            entryId = entryId,
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
        viewModelScope.launch {
            if (player.playingPath.value == entry.audioPath) player.stop()
            journalRepository.delete(entry.id)
            entry.audioPath?.let { path -> runCatching { java.io.File(path).delete() } }
        }
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
    /** An add is in flight — guards against double-taps duplicating tasks. */
    val adding: Boolean = false,
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
        if (_state.value.adding) return
        val title = _state.value.draft.trim()
        if (title.isEmpty()) return
        val remindAt = _state.value.draftReminder.resolve(LocalDateTime.now())
        _state.value = _state.value.copy(adding = true)
        viewModelScope.launch {
            try {
                val remindMs = remindAt?.toEpochMs()
                val id = taskRepository.add(title, remindMs)
                if (remindMs != null) {
                    ReminderScheduler.scheduleTask(context, id, title, remindMs)
                }
                _state.value = _state.value.copy(
                    draft = "",
                    draftReminder = ReminderChoice.NONE,
                    adding = false,
                )
            } catch (t: Throwable) {
                _state.value = _state.value.copy(adding = false)
            }
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
    val mindBackend: MindBackend = MindBackend.QUIET_PARSER,
    val openRouterKey: String = "",
    val openRouterModel: String = "",
    val customBaseUrl: String = "",
    val customModel: String = "",
    val customKey: String = "",
    val sttEngine: SttEngine = SttEngine.SYSTEM,
    val parakeetUrl: String = "",
    val llmUrl: String = "",
    val models: Map<ModelKind, ModelState> = emptyMap(),
    // Derived display state — mirrors MindRepository.resolve() exactly.
    val effectiveMindLabel: String = "",
    /** True when the resolved engine is a network backend (entry text leaves). */
    val mindLeavesPhone: Boolean = false,
    /** True when a non-quiet selection fell back to the Quiet parser. */
    val mindFellBack: Boolean = false,
    /** False when Android reports no on-device speech recognition. */
    val systemSttAvailable: Boolean = true,
)

class SettingsViewModel(
    private val context: Context,
    private val prefsRepository: PrefsRepository,
    private val modelStore: ModelStore,
    private val mindRepository: MindRepository,
    private val transcriber: SpeechTranscriber,
) : ViewModel() {

    private val _state = MutableStateFlow(
        SettingsUiState(systemSttAvailable = transcriber.available),
    )
    val state: StateFlow<SettingsUiState> = _state

    init {
        viewModelScope.launch {
            prefsRepository.prefs.collect { p ->
                val engine = mindRepository.resolve(p)
                _state.value = _state.value.copy(
                    themeMode = p.themeMode,
                    dailyNudgeEnabled = p.dailyNudgeEnabled,
                    dailyNudgeMinutes = p.dailyNudgeMinutes,
                    mindBackend = p.mindBackend,
                    openRouterKey = p.openRouterKey,
                    openRouterModel = p.openRouterModel,
                    customBaseUrl = p.customBaseUrl,
                    customModel = p.customModel,
                    customKey = p.customKey,
                    sttEngine = p.sttEngine,
                    parakeetUrl = p.parakeetUrl,
                    llmUrl = p.llmUrl,
                    effectiveMindLabel = engine.label,
                    mindLeavesPhone = engine is ChatMind,
                    mindFellBack = engine === DeterministicMind &&
                        p.mindBackend != MindBackend.QUIET_PARSER,
                )
            }
        }
        viewModelScope.launch {
            modelStore.states.collect { m ->
                _state.value = _state.value.copy(models = m)
            }
        }
    }

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

    fun setMindBackend(backend: MindBackend) {
        viewModelScope.launch { prefsRepository.setMindBackend(backend) }
    }

    fun setSttEngine(engine: SttEngine) {
        viewModelScope.launch { prefsRepository.setSttEngine(engine) }
    }

    fun setOpenRouter(key: String, model: String) {
        viewModelScope.launch { prefsRepository.setOpenRouter(key, model) }
    }

    fun setCustomEndpoint(baseUrl: String, model: String, key: String) {
        viewModelScope.launch { prefsRepository.setCustomEndpoint(baseUrl, model, key) }
    }

    fun setModelUrls(parakeetUrl: String, llmUrl: String) {
        viewModelScope.launch { prefsRepository.setModelUrls(parakeetUrl, llmUrl) }
    }

    fun installModel(kind: ModelKind) {
        val url = when (kind) {
            ModelKind.PARAKEET_STT -> _state.value.parakeetUrl
            ModelKind.LOCAL_LLM -> _state.value.llmUrl
        }
        val job = viewModelScope.launch { modelStore.install(kind, url) }
        modelStore.registerJob(kind, job)
    }

    fun cancelModelDownload(kind: ModelKind) = modelStore.cancel(kind)

    fun deleteModel(kind: ModelKind) = modelStore.delete(kind)
}
