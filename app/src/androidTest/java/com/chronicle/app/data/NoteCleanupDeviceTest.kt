package com.chronicle.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chronicle.app.data.db.ChronicleDatabase
import com.chronicle.app.data.db.EntryEntity
import com.chronicle.app.data.db.TRANSCRIPT_READY
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteCleanupDeviceTest {
    @Test fun cleanupUndoSurvivesRepositoryRecreationAndProtectsNewerText() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ChronicleDatabase::class.java).build()
        val store = NoteDetailsStore(context)
        val id = 900_001L
        val original = "  My exact original words .\n\nNext paragraph.  "
        try {
            store.clearCleanup(id)
            db.entryDao().insert(EntryEntity(id = id, createdAt = 0, day = "2026-10-04", kind = "text",
                transcript = original, transcriptStatus = TRANSCRIPT_READY))
            val first = JournalRepository(db.entryDao(), store)
            assertTrue(first.applyCleanup(id, original, TRANSCRIPT_READY, "My cleaned words.\n\nNext paragraph."))
            val reopened = JournalRepository(db.entryDao(), NoteDetailsStore(context))
            assertEquals(original, reopened.cleanupRevisions.first()[id]?.original)
            assertTrue(reopened.undoCleanup(id))
            assertEquals(original, db.entryDao().byId(id)?.transcript)
            assertFalse(reopened.undoCleanup(id))

            assertTrue(reopened.applyCleanup(id, original, TRANSCRIPT_READY, "Cleaned again."))
            db.entryDao().updateTranscript(id, "Newer transcription", TRANSCRIPT_READY)
            assertFalse(reopened.undoCleanup(id))
            assertEquals("Newer transcription", db.entryDao().byId(id)?.transcript)
            assertFalse(reopened.applyCleanup(id, original, TRANSCRIPT_READY, "Stale model reply"))
        } finally {
            store.clearCleanup(id)
            db.close()
        }
    }
}
