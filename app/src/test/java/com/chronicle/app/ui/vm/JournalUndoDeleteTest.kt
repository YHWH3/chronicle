package com.chronicle.app.ui.vm

import com.chronicle.app.core.UiMessageBus
import com.chronicle.app.data.JournalRepository
import com.chronicle.app.data.TaskRepository
import com.chronicle.app.data.db.EntryDao
import com.chronicle.app.data.db.EntryEntity
import com.chronicle.app.data.db.TaskDao
import com.chronicle.app.data.db.TaskEntity
import com.chronicle.app.data.db.TRANSCRIPT_READY
import com.chronicle.app.media.EntryPlayer
import com.chronicle.app.media.WavTranscriber
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * FE-04 — delete is soft: the row leaves at once, the entity is held for
 * undo, and the audio file survives until the undo window closes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JournalUndoDeleteTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private class FakeEntryDao : EntryDao {
        val rows = MutableStateFlow<List<EntryEntity>>(emptyList())
        override fun observeAll(): Flow<List<EntryEntity>> = rows
        override fun observeDay(day: String): Flow<List<EntryEntity>> = rows
        override suspend fun allOnce() = rows.value
        override suspend fun byId(id: Long) = rows.value.firstOrNull { it.id == id }
        override suspend fun insert(entry: EntryEntity): Long {
            rows.value = rows.value + entry.copy(id = entry.id)
            return entry.id
        }
        override suspend fun insertPreservingId(entry: EntryEntity): Long {
            rows.value = rows.value.filter { it.id != entry.id } + entry
            return entry.id
        }
        override suspend fun updateTranscript(id: Long, transcript: String, status: String) {}
        override suspend fun updateTranscriptStatus(id: Long, status: String) {}
        override suspend fun delete(entry: EntryEntity) {
            rows.value = rows.value.filter { it.id != entry.id }
        }
        override suspend fun deleteById(id: Long) {
            rows.value = rows.value.filter { it.id != id }
        }
    }

    private class FakeTaskDao : TaskDao {
        val rows = MutableStateFlow<List<TaskEntity>>(emptyList())
        override fun observeAll(): Flow<List<TaskEntity>> = rows
        override suspend fun byId(id: Long) = rows.value.firstOrNull { it.id == id }
        override suspend fun pendingReminders(after: Long) = rows.value.filter {
            !it.done && it.remindAt != null && it.remindAt > after
        }
        override suspend fun pendingRemindersDue(now: Long) = rows.value.filter {
            !it.done && it.remindAt != null && it.notifiedAt == null && it.remindAt <= now
        }
        override suspend fun markNotified(id: Long, now: Long): Int = 0
        override suspend fun clearNotified(id: Long) {}
        override suspend fun updateDetails(id: Long, title: String, remindAt: Long?) {}
        override suspend fun insert(task: TaskEntity) = task.id
        override suspend fun update(task: TaskEntity) {}
        override suspend fun delete(task: TaskEntity) {}
        override suspend fun deleteById(id: Long) {}
        override suspend fun clearSourceEntry(entryId: Long) {
            rows.value = rows.value.map {
                if (it.sourceEntryId == entryId) it.copy(sourceEntryId = null) else it
            }
        }
    }

    private class SilentWav : WavTranscriber {
        override var available = false
        override suspend fun transcribe(wavFile: File) = ""
    }

    private fun voiceEntry(id: Long, audio: File) = EntryEntity(
        id = id,
        createdAt = 1_000,
        day = "2026-01-10",
        kind = "voice",
        transcript = "a take",
        audioPath = audio.absolutePath,
        durationMs = 500,
        transcriptStatus = TRANSCRIPT_READY,
    )

    @Test
    fun `delete holds the entity and keeps the audio file for undo`() = runTest {
        val entryDao = FakeEntryDao()
        val taskDao = FakeTaskDao()
        val audio = File.createTempFile("take", ".wav")
        entryDao.rows.value = listOf(voiceEntry(7, audio))
        taskDao.rows.value = listOf(
            TaskEntity(id = 3, title = "x", createdAt = 0, sourceEntryId = 7),
        )
        val vm = JournalViewModel(
            JournalRepository(entryDao),
            EntryPlayer(backgroundScope),
            SilentWav(),
            UiMessageBus(),
            TaskRepository(taskDao),
            computeDispatcher = StandardTestDispatcher(testScheduler),
        )
        runCurrent()
        val entry = vm.state.value.days[0].entries[0]

        vm.delete(entry)
        runCurrent()

        // Row gone, undo armed, audio still on disk, tasks detached.
        assertTrue(entryDao.rows.value.isEmpty())
        assertEquals(7L, vm.state.value.pendingDelete?.id)
        assertTrue(audio.exists())
        assertNull(taskDao.rows.value[0].sourceEntryId)
    }

    @Test
    fun `undo restores the row with its original id`() = runTest {
        val entryDao = FakeEntryDao()
        val audio = File.createTempFile("take", ".wav")
        entryDao.rows.value = listOf(voiceEntry(7, audio))
        val vm = JournalViewModel(
            JournalRepository(entryDao),
            EntryPlayer(backgroundScope),
            SilentWav(),
            UiMessageBus(),
            computeDispatcher = StandardTestDispatcher(testScheduler),
        )
        runCurrent()
        val entry = vm.state.value.days[0].entries[0]
        vm.delete(entry)
        runCurrent()
        vm.undoDelete()
        runCurrent()

        val restored = entryDao.rows.value.single { it.id == 7L }
        assertEquals(audio.absolutePath, restored.audioPath)
        assertEquals("a take", restored.transcript)
        assertNull(vm.state.value.pendingDelete)
        assertTrue(audio.exists())
    }

    @Test
    fun `a second delete commits the first file`() = runTest {
        val entryDao = FakeEntryDao()
        val first = File.createTempFile("first", ".wav")
        val second = File.createTempFile("second", ".wav")
        entryDao.rows.value = listOf(voiceEntry(7, first), voiceEntry(8, second))
        val vm = JournalViewModel(
            JournalRepository(entryDao),
            EntryPlayer(backgroundScope),
            SilentWav(),
            UiMessageBus(),
            computeDispatcher = StandardTestDispatcher(testScheduler),
        )
        runCurrent()
        val entries = vm.state.value.days[0].entries

        vm.delete(entries.first { it.id == 7L })
        runCurrent()
        vm.delete(vm.state.value.days[0].entries.first { it.id == 8L })
        runCurrent()

        // The first undo window closed when the second delete landed.
        assertTrue(!first.exists())
        assertTrue(second.exists())
        assertEquals(8L, vm.state.value.pendingDelete?.id)
    }

    @Test
    fun `undo is a no-op without a pending delete`() = runTest {
        val entryDao = FakeEntryDao()
        entryDao.rows.value = listOf(voiceEntry(7, File.createTempFile("take", ".wav")))
        val vm = JournalViewModel(
            JournalRepository(entryDao),
            EntryPlayer(backgroundScope),
            SilentWav(),
            UiMessageBus(),
            computeDispatcher = StandardTestDispatcher(testScheduler),
        )
        runCurrent()
        vm.undoDelete()
        runCurrent()
        assertEquals(1, entryDao.rows.value.size)
        assertNull(vm.state.value.pendingDelete)
    }
}
