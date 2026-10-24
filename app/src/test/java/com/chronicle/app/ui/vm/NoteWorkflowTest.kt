package com.chronicle.app.ui.vm

import com.chronicle.app.R
import com.chronicle.app.core.UiMessageBus
import com.chronicle.app.data.*
import com.chronicle.app.data.db.*
import com.chronicle.app.domain.toDomain
import com.chronicle.app.media.EntryPlayer
import com.chronicle.app.media.WavTranscriber
import com.chronicle.app.mind.*
import com.chronicle.app.reminders.ReminderGateway
import java.io.File
import java.time.LocalDateTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NoteWorkflowTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private class Entries : EntryDao {
        val rows = MutableStateFlow(listOf(EntryEntity(id = 7, createdAt = 0, day = "2026-10-04",
            kind = "text", transcript = "  I need to call Sam .  ", transcriptStatus = TRANSCRIPT_READY)))
        override fun observeAll(): Flow<List<EntryEntity>> = rows
        override fun observeDay(day: String): Flow<List<EntryEntity>> = rows
        override suspend fun allOnce() = rows.value
        override suspend fun byId(id: Long) = rows.value.find { it.id == id }
        override suspend fun insert(entry: EntryEntity): Long { rows.value += entry; return entry.id }
        override suspend fun insertPreservingId(entry: EntryEntity) = insert(entry)
        override suspend fun updateTranscript(id: Long, transcript: String, status: String) {
            rows.value = rows.value.map { if (it.id == id) it.copy(transcript = transcript, transcriptStatus = status) else it }
        }
        override suspend fun updateTranscriptStatus(id: Long, status: String) {
            rows.value = rows.value.map { if (it.id == id) it.copy(transcriptStatus = status) else it }
        }
        override suspend fun delete(entry: EntryEntity) = deleteById(entry.id)
        override suspend fun deleteById(id: Long) { rows.value = rows.value.filter { it.id != id } }
    }

    private class Tasks : TaskDao {
        val rows = MutableStateFlow<List<TaskEntity>>(emptyList())
        override fun observeAll(): Flow<List<TaskEntity>> = rows
        override suspend fun byId(id: Long) = rows.value.find { it.id == id }
        override suspend fun pendingReminders(after: Long) = emptyList<TaskEntity>()
        override suspend fun pendingRemindersDue(now: Long) = emptyList<TaskEntity>()
        override suspend fun markNotified(id: Long, now: Long) = 0
        override suspend fun clearNotified(id: Long) {}
        override suspend fun updateDetails(id: Long, title: String, remindAt: Long?) {}
        override suspend fun insert(task: TaskEntity): Long {
            val id = (rows.value.maxOfOrNull { it.id } ?: 0) + 1
            rows.value += task.copy(id = id)
            return id
        }
        override suspend fun update(task: TaskEntity) {}
        override suspend fun delete(task: TaskEntity) = deleteById(task.id)
        override suspend fun deleteById(id: Long) { rows.value = rows.value.filter { it.id != id } }
        override suspend fun clearSourceEntry(entryId: Long) {}
    }

    private class Alarms : ReminderGateway {
        val scheduled = mutableListOf<Long>()
        var fail = false
        override fun scheduleTask(taskId: Long, title: String, atMillis: Long) {
            if (fail) error("alarm service unavailable")
            scheduled += taskId
        }
        override fun cancelTask(taskId: Long) {}
        override fun notifyTask(taskId: Long, title: String) {}
        override fun cancelNotification(taskId: Long) {}
        override fun scheduleDailyNudge(minutesAfterMidnight: Int) {}
        override fun cancelDailyNudge() {}
        override fun notificationsEnabled() = true
    }

    private class Source : MindSource {
        val pending = CompletableDeferred<Unit>()
        var wait = false
        val candidates = listOf(MindTask("Call Sam", LocalDateTime.now().plusDays(1)), MindTask("Pay rent", null))
        override fun resolve(prefs: ChroniclePrefs) = DeterministicMind
        override suspend fun extract(text: String, now: LocalDateTime): MindExecutionResult {
            if (wait) pending.await()
            return MindExecutionResult(MindResult(candidates), "test model", "test model", null)
        }
        override suspend fun cleanup(text: String, now: LocalDateTime) = NoteCleanupResult("I need to call Sam.", "test model")
    }

    private fun TestScope.vm(entries: Entries, tasks: Tasks = Tasks(), source: Source = Source(), alarms: Alarms = Alarms()) =
        JournalViewModel(JournalRepository(entries), EntryPlayer(backgroundScope), object : WavTranscriber {
            override val available = false
            override suspend fun transcribe(wavFile: File) = ""
        }, UiMessageBus(), TaskRepository(tasks), StandardTestDispatcher(testScheduler), source, alarms)

    @Test fun `cleanup is preview only until apply and undo restores exact original`() = runTest {
        val entries = Entries()
        val original = entries.rows.value.single().transcript
        val vm = vm(entries)
        runCurrent()
        vm.workOnNote(entries.rows.value.single().toDomain(), NoteOperation.CLEANUP)
        runCurrent()
        assertEquals(original, entries.rows.value.single().transcript)
        assertEquals("I need to call Sam.", vm.state.value.review!!.cleaned)
        vm.applyCleanup()
        runCurrent()
        assertEquals("I need to call Sam.", entries.rows.value.single().transcript)
        assertTrue(7L in vm.state.value.cleanupUndoIds)
        vm.undoCleanup(7)
        runCurrent()
        assertEquals(original, entries.rows.value.single().transcript)
        assertFalse(7L in vm.state.value.cleanupUndoIds)
    }

    @Test fun `changed or deleted note cannot be overwritten by stale cleanup`() = runTest {
        val entries = Entries()
        val vm = vm(entries)
        runCurrent()
        vm.workOnNote(entries.rows.value.single().toDomain(), NoteOperation.CLEANUP)
        runCurrent()
        entries.updateTranscript(7, "Newer transcription", TRANSCRIPT_READY)
        vm.applyCleanup()
        runCurrent()
        assertEquals("Newer transcription", entries.rows.value.single().transcript)
        assertEquals(R.string.note_changed, vm.state.value.review!!.errorRes)
        assertFalse(7L in vm.state.value.cleanupUndoIds)
    }

    @Test fun `undo does not overwrite a newer transcription`() = runTest {
        val entries = Entries()
        val repo = JournalRepository(entries)
        assertTrue(repo.applyCleanup(7, entries.rows.value.single().transcript, TRANSCRIPT_READY, "Cleaned"))
        entries.updateTranscript(7, "Newer", TRANSCRIPT_READY)
        assertFalse(repo.undoCleanup(7))
        assertEquals("Newer", entries.rows.value.single().transcript)
    }

    @Test fun `task review writes nothing and skips write nothing`() = runTest {
        val entries = Entries(); val tasks = Tasks(); val alarms = Alarms()
        val vm = vm(entries, tasks, alarms = alarms)
        runCurrent()
        vm.workOnNote(entries.rows.value.single().toDomain(), NoteOperation.TASKS)
        runCurrent()
        assertEquals(2, vm.state.value.review!!.tasks.size)
        assertTrue(tasks.rows.value.isEmpty())
        assertTrue(alarms.scheduled.isEmpty())
        vm.skipNoteTask(0)
        assertEquals(listOf("Pay rent"), vm.state.value.review!!.tasks.map { it.title })
        assertTrue(tasks.rows.value.isEmpty())
    }

    @Test fun `changed note cannot add tasks from a stale preview`() = runTest {
        val entries = Entries(); val tasks = Tasks(); val alarms = Alarms()
        val vm = vm(entries, tasks, alarms = alarms)
        runCurrent()
        vm.workOnNote(entries.rows.value.single().toDomain(), NoteOperation.TASKS)
        runCurrent()
        entries.updateTranscript(7, "I already called Sam.", TRANSCRIPT_READY)
        vm.addNoteTask(0, true)
        runCurrent()
        assertTrue(tasks.rows.value.isEmpty())
        assertTrue(alarms.scheduled.isEmpty())
        assertEquals(R.string.note_add_failed, vm.state.value.review!!.errorRes)
    }

    @Test fun `plain task discards detected time and never creates an alarm`() = runTest {
        val entries = Entries(); val tasks = Tasks(); val alarms = Alarms()
        val vm = vm(entries, tasks, alarms = alarms)
        runCurrent()
        vm.workOnNote(entries.rows.value.single().toDomain(), NoteOperation.TASKS)
        runCurrent()
        vm.addNoteTask(0, false)
        runCurrent()
        assertEquals("Call Sam", tasks.rows.value.single().title)
        assertEquals(7L, tasks.rows.value.single().sourceEntryId)
        assertNull(tasks.rows.value.single().remindAt)
        assertTrue(alarms.scheduled.isEmpty())
    }

    @Test fun `reminder requires explicit acceptance and schedules the stored task`() = runTest {
        val entries = Entries(); val tasks = Tasks(); val alarms = Alarms()
        val vm = vm(entries, tasks, alarms = alarms)
        runCurrent()
        vm.workOnNote(entries.rows.value.single().toDomain(), NoteOperation.TASKS)
        runCurrent()
        vm.addNoteTask(0, true)
        runCurrent()
        assertNotNull(tasks.rows.value.single().remindAt)
        assertEquals(listOf(tasks.rows.value.single().id), alarms.scheduled)
    }

    @Test fun `reanalysis filters tasks already saved from the same note`() = runTest {
        val entries = Entries(); val tasks = Tasks(); val vm = vm(entries, tasks)
        tasks.rows.value = listOf(TaskEntity(id = 3, title = "Call Sam!", createdAt = 0, sourceEntryId = 7))
        runCurrent()
        vm.workOnNote(entries.rows.value.single().toDomain(), NoteOperation.TASKS)
        runCurrent()
        assertEquals(listOf("Pay rent"), vm.state.value.review!!.tasks.map { it.title })
    }

    @Test fun `closing an in flight review cancels it and cannot reopen it`() = runTest {
        val entries = Entries(); val source = Source().also { it.wait = true }; val vm = vm(entries, source = source)
        runCurrent()
        vm.workOnNote(entries.rows.value.single().toDomain(), NoteOperation.TASKS)
        runCurrent()
        assertTrue(vm.state.value.review!!.busy)
        vm.dismissReview()
        source.pending.complete(Unit)
        runCurrent()
        assertNull(vm.state.value.review)
    }

    @Test fun `alarm failure leaves the stored task removed from suggestions so retry cannot duplicate it`() = runTest {
        val entries = Entries(); val tasks = Tasks(); val alarms = Alarms().also { it.fail = true }
        val vm = vm(entries, tasks, alarms = alarms)
        runCurrent()
        vm.workOnNote(entries.rows.value.single().toDomain(), NoteOperation.TASKS)
        runCurrent()
        vm.addNoteTask(0, true)
        runCurrent()
        assertEquals(1, tasks.rows.value.size)
        assertEquals(listOf("Pay rent"), vm.state.value.review!!.tasks.map { it.title })
        assertEquals(R.string.note_task_saved_check_time, vm.state.value.review!!.errorRes)
        assertFalse(vm.state.value.review!!.busy)
    }
}
