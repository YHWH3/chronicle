package com.chronicle.app.ui.vm

import com.chronicle.app.data.JournalRepository
import com.chronicle.app.data.db.EntryDao
import com.chronicle.app.data.db.EntryEntity
import com.chronicle.app.data.db.TRANSCRIPT_READY
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * Story reader identity — chapters resolve by their week-start Monday, a key
 * that survives the renumbering a mid-week delete causes (FE-14).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StoryViewModelTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private class FakeDao : EntryDao {
        val rows = MutableStateFlow<List<EntryEntity>>(emptyList())
        override fun observeAll(): Flow<List<EntryEntity>> = rows
        override fun observeDay(day: String): Flow<List<EntryEntity>> = rows
        override suspend fun allOnce() = rows.value
        override suspend fun byId(id: Long) = rows.value.firstOrNull { it.id == id }
        override suspend fun insert(entry: EntryEntity) = entry.id
        override suspend fun insertPreservingId(entry: EntryEntity) = entry.id
        override suspend fun updateTranscript(id: Long, transcript: String, status: String) {}
        override suspend fun updateTranscriptStatus(id: Long, status: String) {}
        override suspend fun delete(entry: EntryEntity) {}
        override suspend fun deleteById(id: Long) {}
    }

    private fun entry(id: Long, day: String, createdAt: Long) = EntryEntity(
        id = id,
        createdAt = createdAt,
        day = day,
        kind = "text",
        transcript = "kept entry $id",
        transcriptStatus = TRANSCRIPT_READY,
    )

    private fun vm(dao: FakeDao, scheduler: kotlinx.coroutines.test.TestCoroutineScheduler) =
        StoryViewModel(JournalRepository(dao), StandardTestDispatcher(scheduler))

    @Test
    fun `chapters resolve by week-start Monday`() = runTest {
        val dao = FakeDao()
        dao.rows.value = listOf(
            entry(1, "2026-01-07", 1_000L), // Wed → week of Mon 2026-01-05
            entry(2, "2026-01-09", 2_000L), // Fri → same week
            entry(3, "2026-01-12", 3_000L), // Mon → the next week
        )
        val vm = vm(dao, testScheduler)
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(
            listOf(LocalDate.parse("2026-01-05"), LocalDate.parse("2026-01-12")),
            state.chapterWeeks,
        )
        assertEquals("Chapter I", state.chapterAt(LocalDate.parse("2026-01-05"))?.title)
        assertEquals("Chapter II", state.chapterAt(LocalDate.parse("2026-01-12"))?.title)
        assertEquals(2, state.chapterAt(LocalDate.parse("2026-01-05"))?.entryCount)
        assertNull(state.chapterAt(LocalDate.parse("2026-01-19")))
    }

    @Test
    fun `a deleted week stops resolving and the survivor keeps its key`() = runTest {
        val dao = FakeDao()
        dao.rows.value = listOf(
            entry(1, "2026-01-07", 1_000L),
            entry(2, "2026-01-12", 2_000L),
        )
        val vm = vm(dao, testScheduler)
        advanceUntilIdle()
        val firstWeek = LocalDate.parse("2026-01-05")
        assertNotNull(vm.state.value.chapterAt(firstWeek))

        dao.rows.value = listOf(entry(2, "2026-01-12", 2_000L))
        advanceUntilIdle()

        // The reader holding chapter/2026-01-05 would pop back; the survivor
        // renumbered II→I yet still answers to its own week key.
        assertNull(vm.state.value.chapterAt(firstWeek))
        assertEquals(listOf(LocalDate.parse("2026-01-12")), vm.state.value.chapterWeeks)
        assertEquals("Chapter I", vm.state.value.chapterAt(LocalDate.parse("2026-01-12"))?.title)
    }

    @Test
    fun `empty journal resolves nothing`() = runTest {
        val vm = vm(FakeDao(), testScheduler)
        advanceUntilIdle()
        assertEquals(emptyList<LocalDate>(), vm.state.value.chapterWeeks)
        assertNull(vm.state.value.chapterAt(LocalDate.parse("2026-01-05")))
    }
}
