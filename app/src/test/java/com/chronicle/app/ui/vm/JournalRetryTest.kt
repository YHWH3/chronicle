package com.chronicle.app.ui.vm

import com.chronicle.app.core.UiMessage
import com.chronicle.app.core.UiMessageBus
import com.chronicle.app.data.JournalRepository
import com.chronicle.app.data.db.EntryDao
import com.chronicle.app.data.db.EntryEntity
import com.chronicle.app.data.db.TRANSCRIPT_FAILED
import com.chronicle.app.data.db.TRANSCRIPT_PENDING
import com.chronicle.app.data.db.TRANSCRIPT_READY
import com.chronicle.app.domain.TranscriptStatus
import com.chronicle.app.media.EntryPlayer
import com.chronicle.app.media.WavTranscriber
import java.io.File
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Journal "Transcribe again" — re-runs the file transcriber on a FAILED
 * take and folds the result back into the row (FE-01).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JournalRetryTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private val events = mutableListOf<String>()

    private inner class FakeDao : EntryDao {
        val rows = MutableStateFlow<List<EntryEntity>>(emptyList())
        override fun observeAll(): Flow<List<EntryEntity>> = rows
        override fun observeDay(day: String): Flow<List<EntryEntity>> = rows
        override suspend fun allOnce() = rows.value
        override suspend fun byId(id: Long) = rows.value.firstOrNull { it.id == id }
        override suspend fun insert(entry: EntryEntity) = entry.id
        override suspend fun updateTranscript(id: Long, transcript: String, status: String) {
            events += "update:$status"
            rows.value = rows.value.map {
                if (it.id == id) it.copy(transcript = transcript, transcriptStatus = status) else it
            }
        }
        override suspend fun updateTranscriptStatus(id: Long, status: String) {
            events += "status:$status"
            rows.value = rows.value.map {
                if (it.id == id) it.copy(transcriptStatus = status) else it
            }
        }
        override suspend fun delete(entry: EntryEntity) {}
        override suspend fun deleteById(id: Long) {}
    }

    private inner class FakeWav : WavTranscriber {
        override var available = true
        var onTranscribe: suspend (File) -> String = { "hello again" }
        override suspend fun transcribe(wavFile: File): String = onTranscribe(wavFile)
    }

    private fun failedEntry(audio: File) = EntryEntity(
        id = 7,
        createdAt = 1_000,
        day = "2026-01-10",
        kind = "voice",
        transcript = "",
        audioPath = audio.absolutePath,
        durationMs = 900,
        transcriptStatus = TRANSCRIPT_FAILED,
    )

    @Test
    fun `retry success writes transcript and ready status`() = runTest {
        val dao = FakeDao()
        val audio = File.createTempFile("take", ".wav")
        dao.rows.value = listOf(failedEntry(audio))
        val wav = FakeWav()
        val bus = UiMessageBus()
        val messages = mutableListOf<UiMessage>()
        backgroundScope.launch { bus.messages.collect { messages += it } }

        val vm = JournalViewModel(JournalRepository(dao), EntryPlayer(backgroundScope), wav, bus)
        runCurrent()
        val entry = vm.state.value.days[0].entries[0]
        assertEquals(TranscriptStatus.FAILED, entry.transcriptStatus)
        assertTrue(vm.state.value.canRetranscribe)

        vm.retryTranscription(entry)
        runCurrent()
        advanceTimeBy(100)
        runCurrent()

        val row = dao.rows.value[0]
        assertEquals("hello again", row.transcript)
        assertEquals(TRANSCRIPT_READY, row.transcriptStatus)
        // pending -> ready transitions recorded
        assertTrue("status:$TRANSCRIPT_PENDING" in events)
    }

    @Test
    fun `retry failure keeps audio and surfaces the message`() = runTest {
        val dao = FakeDao()
        val audio = File.createTempFile("take", ".wav")
        dao.rows.value = listOf(failedEntry(audio))
        val wav = FakeWav()
        wav.onTranscribe = { throw IOException("decode blew up") }
        val bus = UiMessageBus()
        val messages = mutableListOf<UiMessage>()
        backgroundScope.launch { bus.messages.collect { messages += it } }

        val vm = JournalViewModel(JournalRepository(dao), EntryPlayer(backgroundScope), wav, bus)
        runCurrent()
        vm.retryTranscription(vm.state.value.days[0].entries[0])
        runCurrent()

        val row = dao.rows.value[0]
        assertEquals(TRANSCRIPT_FAILED, row.transcriptStatus)
        assertEquals(audio.absolutePath, row.audioPath)
        assertTrue(messages.any { it is UiMessage.TranscriptionFailed })
    }

    @Test
    fun `retry is a no-op when the transcriber is unavailable`() = runTest {
        val dao = FakeDao()
        val audio = File.createTempFile("take", ".wav")
        dao.rows.value = listOf(failedEntry(audio))
        val wav = FakeWav()
        wav.available = false
        var called = false
        wav.onTranscribe = { called = true; "" }
        val bus = UiMessageBus()

        val vm = JournalViewModel(JournalRepository(dao), EntryPlayer(backgroundScope), wav, bus)
        runCurrent()
        vm.retryTranscription(vm.state.value.days[0].entries[0])
        runCurrent()

        assertTrue(!called)
        assertEquals(TRANSCRIPT_FAILED, dao.rows.value[0].transcriptStatus)
    }
}
