package com.chronicle.app.ui.vm

import com.chronicle.app.core.UiMessage
import com.chronicle.app.core.UiMessageBus
import com.chronicle.app.data.ChroniclePrefs
import com.chronicle.app.data.JournalRepository
import com.chronicle.app.data.PrefsSource
import com.chronicle.app.data.SttEngine
import com.chronicle.app.data.TaskRepository
import com.chronicle.app.data.db.EntryDao
import com.chronicle.app.data.db.EntryEntity
import com.chronicle.app.data.db.TaskDao
import com.chronicle.app.data.db.TaskEntity
import com.chronicle.app.data.db.TRANSCRIPT_FAILED
import com.chronicle.app.data.db.TRANSCRIPT_READY
import com.chronicle.app.media.LiveTranscriber
import com.chronicle.app.media.RecordStart
import com.chronicle.app.media.TakeRecorder
import com.chronicle.app.media.WavTranscriber
import com.chronicle.app.mind.DeterministicMind
import com.chronicle.app.mind.MindEngine
import com.chronicle.app.mind.MindExecutionResult
import com.chronicle.app.mind.MindResult
import com.chronicle.app.mind.MindSource
import com.chronicle.app.mind.MindTask
import com.chronicle.app.reminders.ReminderGateway
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

// ————— Fakes —————

private class FakeEntryDao(private val events: MutableList<String>) : EntryDao {
    val rows = mutableListOf<EntryEntity>()
    private val all = MutableStateFlow<List<EntryEntity>>(emptyList())
    private var nextId = 1L
    var failInsert = false

    override fun observeAll(): Flow<List<EntryEntity>> = all
    override fun observeDay(day: String): Flow<List<EntryEntity>> = all
    override suspend fun allOnce(): List<EntryEntity> = rows.toList()
    override suspend fun byId(id: Long) = rows.firstOrNull { it.id == id }
    override suspend fun insert(entry: EntryEntity): Long {
        if (failInsert) throw IOException("disk full")
        events += "entry:${entry.transcriptStatus}"
        val row = entry.copy(id = nextId++)
        rows += row
        all.value = rows.toList()
        return row.id
    }
    override suspend fun insertPreservingId(entry: EntryEntity): Long {
        rows.removeIf { it.id == entry.id }
        rows += entry
        all.value = rows.toList()
        return entry.id
    }
    override suspend fun updateTranscript(id: Long, transcript: String, status: String) {
        val i = rows.indexOfFirst { it.id == id }
        if (i >= 0) rows[i] = rows[i].copy(transcript = transcript, transcriptStatus = status)
    }
    override suspend fun updateTranscriptStatus(id: Long, status: String) {
        val i = rows.indexOfFirst { it.id == id }
        if (i >= 0) rows[i] = rows[i].copy(transcriptStatus = status)
    }
    override suspend fun delete(entry: EntryEntity) { rows.removeIf { it.id == entry.id } }
    override suspend fun deleteById(id: Long) { rows.removeIf { it.id == id } }
}

private class FakeTaskDao(private val events: MutableList<String>) : TaskDao {
    val rows = mutableListOf<TaskEntity>()
    private var nextId = 1L
    var failOnTitle: String? = null

    override fun observeAll(): Flow<List<TaskEntity>> = flowOf(rows.toList())
    override suspend fun byId(id: Long) = rows.firstOrNull { it.id == id }
    override suspend fun pendingReminders(after: Long) = rows.filter {
        !it.done && it.remindAt != null && it.remindAt > after
    }
    override suspend fun pendingRemindersDue(now: Long) = rows.filter {
        !it.done && it.remindAt != null && it.notifiedAt == null && it.remindAt <= now
    }
    override suspend fun markNotified(id: Long, now: Long): Int {
        val i = rows.indexOfFirst { it.id == id }
        if (i < 0 || rows[i].notifiedAt != null) return 0
        rows[i] = rows[i].copy(notifiedAt = now)
        return 1
    }
    override suspend fun clearNotified(id: Long) {
        val i = rows.indexOfFirst { it.id == id }
        if (i >= 0) rows[i] = rows[i].copy(notifiedAt = null)
    }
    override suspend fun updateDetails(id: Long, title: String, remindAt: Long?) {
        val i = rows.indexOfFirst { it.id == id }
        if (i >= 0) {
            rows[i] = rows[i].copy(title = title, remindAt = remindAt, notifiedAt = null)
        }
    }
    override suspend fun insert(task: TaskEntity): Long {
        if (task.title == failOnTitle) throw IOException("insert refused")
        events += "task:${task.title}"
        val row = task.copy(id = nextId++)
        rows += row
        return row.id
    }
    override suspend fun update(task: TaskEntity) {
        val i = rows.indexOfFirst { it.id == task.id }
        if (i >= 0) rows[i] = task
    }
    override suspend fun delete(task: TaskEntity) { rows.removeIf { it.id == task.id } }
    override suspend fun deleteById(id: Long) { rows.removeIf { it.id == id } }
    override suspend fun clearSourceEntry(entryId: Long) {
        rows.replaceAll { if (it.sourceEntryId == entryId) it.copy(sourceEntryId = null) else it }
    }
}

private class FakePrefsSource(prefs: ChroniclePrefs = ChroniclePrefs()) : PrefsSource {
    override val prefs = MutableStateFlow(prefs)
}

private class FakeRecorder(
    private val events: MutableList<String>,
    var startResult: RecordStart = RecordStart.Started(File("take.m4a")),
    var stopResult: Pair<File, Long>? = null,
) : TakeRecorder {
    private val _amplitudes = MutableStateFlow(List(32) { 0f })
    private val _elapsed = MutableStateFlow(0L)
    override val amplitudes: StateFlow<List<Float>> = _amplitudes
    override val elapsedMs: StateFlow<Long> = _elapsed
    override val isRecording: Boolean get() = false
    override fun start(): RecordStart {
        events += "record-start"
        return startResult
    }
    override suspend fun stop(): Pair<File, Long>? {
        events += "record-stop"
        return stopResult
    }
    override fun cancel() {}
}

private class FakeLiveTranscriber(var text: String = "call Sam tomorrow") : LiveTranscriber {
    override val available = true
    override val partial = MutableStateFlow("")
    override val committed = MutableStateFlow<List<String>>(emptyList())
    override val transcript get() = text
    override fun start() {}
    override suspend fun stop() {}
    override fun destroyNow() {}
}

private class FakeWavTranscriber : WavTranscriber {
    override var available = false
    var onTranscribe: suspend (File) -> String = { "ok" }
    override suspend fun transcribe(wavFile: File): String = onTranscribe(wavFile)
}

private class FakeMind(
    private val events: MutableList<String>,
    var onExtract: suspend (String) -> MindResult = { MindResult(emptyList()) },
) : MindSource {
    override fun resolve(prefs: ChroniclePrefs): MindEngine = DeterministicMind
    override suspend fun extract(text: String, now: LocalDateTime): MindExecutionResult {
        events += "extract"
        return MindExecutionResult(onExtract(text), "Fake cloud", "Fake cloud", null)
    }
}

private class FakeReminders : ReminderGateway {
    val scheduled = mutableListOf<Long>()
    var notificationsEnabled = true
    override fun scheduleTask(taskId: Long, title: String, atMillis: Long) { scheduled += taskId }
    override fun cancelTask(taskId: Long) {}
    override fun notifyTask(taskId: Long, title: String) {}
    override fun cancelNotification(taskId: Long) {}
    override fun scheduleDailyNudge(minutesAfterMidnight: Int) {}
    override fun cancelDailyNudge() {}
    override fun notificationsEnabled() = notificationsEnabled
}

// ————— The pipeline —————

/**
 * Save-pipeline ordering and failure tolerance (FE-01, FE-03, XC-01/02):
 * the entry commits before the mind runs; extraction and task failures
 * surface as messages instead of eating the save.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SavePipelineTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private lateinit var events: MutableList<String>
    private lateinit var entryDao: FakeEntryDao
    private lateinit var taskDao: FakeTaskDao
    private lateinit var mind: FakeMind
    private lateinit var reminders: FakeReminders
    private lateinit var uiMessages: UiMessageBus
    private lateinit var messages: MutableList<UiMessage>
    private lateinit var voiceRecorder: FakeRecorder
    private lateinit var wavRecorder: FakeRecorder
    private lateinit var liveTranscriber: FakeLiveTranscriber
    private lateinit var wavTranscriber: FakeWavTranscriber
    private var prefs = ChroniclePrefs()

    private fun vm(): TodayViewModel = TodayViewModel(
        JournalRepository(entryDao),
        TaskRepository(taskDao),
        FakePrefsSource(prefs),
        voiceRecorder,
        wavRecorder,
        liveTranscriber,
        wavTranscriber,
        mind,
        reminders,
        uiMessages,
        // Static date: the production rollover flow delay()s forever, which
        // would keep runTest's end-of-test advanceUntilIdle spinning.
        dateFlow = flowOf(LocalDate.now()),
    )

    @Before
    fun setUp() {
        events = mutableListOf()
        entryDao = FakeEntryDao(events)
        taskDao = FakeTaskDao(events)
        mind = FakeMind(events)
        reminders = FakeReminders()
        uiMessages = UiMessageBus()
        messages = mutableListOf()
        voiceRecorder = FakeRecorder(events)
        wavRecorder = FakeRecorder(events)
        liveTranscriber = FakeLiveTranscriber()
        wavTranscriber = FakeWavTranscriber()
    }

    private fun kotlinx.coroutines.test.TestScope.collectMessages() {
        backgroundScope.launch { uiMessages.messages.collect { messages += it } }
        // The VM runs on Unconfined and can emit before the background
        // collector's first dispatch — start it now or messages are lost
        // (SharedFlow has no replay).
        runCurrent()
    }

    /**
     * Runs queued work and covers the processing dwell + transcriber stops —
     * bounded on purpose: the date-rollover flow reschedules delay() forever,
     * so advanceUntilIdle would never return.
     */
    private suspend fun kotlinx.coroutines.test.TestScope.settle() {
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
    }

    @Test
    fun `entry persists before extraction and survives its failure`() = runTest {
        collectMessages()
        mind.onExtract = { throw IOException("cloud unreachable") }
        val v = vm()
        v.setDraft("a quiet morning, nothing due")
        v.saveDraft()
        settle()

        // Ordering: the entry row landed before the mind was asked.
        assertEquals("entry:ready", events.first())
        assertEquals(listOf("entry:ready", "extract"), events)
        assertEquals(1, entryDao.rows.size)
        assertEquals("a quiet morning, nothing due", entryDao.rows[0].transcript)
        // The save survives — kept card shown, failure surfaced quietly.
        assertEquals("Quiet parser", v.state.value.kept?.engineLabel)
        assertTrue(messages.any { it is UiMessage.ExtractionFailed })
        assertTrue(taskDao.rows.isEmpty())
    }

    @Test
    fun `one failing intention does not kill the others`() = runTest {
        collectMessages()
        mind.onExtract = {
            MindResult(listOf(MindTask("bad", null), MindTask("good", null)))
        }
        taskDao.failOnTitle = "bad"
        val v = vm()
        v.setDraft("a day")
        v.saveDraft()
        settle()

        assertEquals(listOf("task:good"), events.filter { it.startsWith("task") })
        assertEquals(1, taskDao.rows.size)
        assertEquals("good", taskDao.rows[0].title)
        // Only the persisted intention is credited on the Kept card.
        assertEquals(listOf("good"), v.state.value.kept?.intentions?.map { it.title })
        assertTrue(messages.any { it is UiMessage.IntentionsPartial })
    }

    @Test
    fun `parakeet failure keeps the audio and marks the entry failed`() = runTest {
        collectMessages()
        prefs = ChroniclePrefs(sttEngine = SttEngine.PARAKEET)
        wavTranscriber.available = true
        wavTranscriber.onTranscribe = { throw IOException("decoder exploded") }
        val wavFile = File.createTempFile("take", ".wav")
        wavRecorder.stopResult = wavFile to 1500L

        val v = vm()
        settle() // let prefs land so activeRecorder() picks wav
        v.toggleRecording() // starts
        v.toggleRecording() // stops -> pipeline
        settle()

        assertEquals(1, entryDao.rows.size)
        val row = entryDao.rows[0]
        assertEquals("voice", row.kind)
        assertEquals(wavFile.absolutePath, row.audioPath)
        assertEquals(TRANSCRIPT_FAILED, row.transcriptStatus)
        assertEquals("", row.transcript)
        // Kept card notes the failure; the toast explains it too.
        assertEquals(true, v.state.value.kept?.transcriptFailed)
        assertTrue(messages.any { it is UiMessage.TranscriptionFailed })
    }

    @Test
    fun `successful transcribe marks the entry ready and keeps order`() = runTest {
        collectMessages()
        prefs = ChroniclePrefs(sttEngine = SttEngine.PARAKEET)
        wavTranscriber.available = true
        wavTranscriber.onTranscribe = { "buy milk" }
        val wavFile = File.createTempFile("take", ".wav")
        wavRecorder.stopResult = wavFile to 900L
        mind.onExtract = {
            MindResult(listOf(MindTask("buy milk", LocalDateTime.now().plusHours(1))))
        }

        val v = vm()
        settle()
        v.toggleRecording()
        v.toggleRecording()
        settle()

        // record-stop -> entry insert -> extract -> task insert
        val idx = { k: String -> events.indexOfFirst { it.startsWith(k) } }
        assertTrue(idx("record-stop") < idx("entry"))
        assertTrue(idx("entry") < idx("extract"))
        assertTrue(idx("extract") < idx("task:"))
        assertEquals(TRANSCRIPT_READY, entryDao.rows[0].transcriptStatus)
        assertEquals("buy milk", entryDao.rows[0].transcript)
        assertEquals(1, taskDao.rows.size)
        assertEquals(1, reminders.scheduled.size) // future reminder armed
    }

    @Test
    fun `busy microphone surfaces a retryable message and stays idle`() = runTest {
        collectMessages()
        voiceRecorder.startResult = RecordStart.MicrophoneBusy
        val v = vm()
        v.toggleRecording()
        settle()

        assertEquals(RecordingState.IDLE, v.state.value.recording)
        assertTrue(messages.filterIsInstance<UiMessage.RecorderUnavailable>().isNotEmpty())
        assertTrue(entryDao.rows.isEmpty())
    }

    @Test
    fun `missing permission surfaces the settings message`() = runTest {
        collectMessages()
        voiceRecorder.startResult = RecordStart.PermissionMissing
        val v = vm()
        v.toggleRecording()
        settle()

        assertEquals(RecordingState.IDLE, v.state.value.recording)
        assertTrue(messages.any { it is UiMessage.MicPermanentlyDenied })
    }

    @Test
    fun `save failure surfaces and does not wedge the pipeline`() = runTest {
        collectMessages()
        entryDao.failInsert = true
        val v = vm()
        v.setDraft("a day")
        v.saveDraft()
        settle()

        assertTrue(v.state.value.saving.not())
        assertTrue(messages.any { it is UiMessage.SaveFailed })
        assertTrue(entryDao.rows.isEmpty())
        // Not wedged — a second save after recovery works.
        entryDao.failInsert = false
        v.setDraft("a day")
        v.saveDraft()
        settle()
        assertEquals(1, entryDao.rows.size)
    }
}
