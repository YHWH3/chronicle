package com.chronicle.app.mind

import com.chronicle.app.domain.TaskExtractor
import java.time.LocalDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ParagraphNotesTest {
    private val now = LocalDateTime.of(2026, 10, 4, 16, 43)

    @Test fun `newline list is not collapsed into one giant title`() {
        val tasks = TaskExtractor.extract("I need to buy milk\nneed to call Sam\nremind me to pay rent tomorrow at 5pm", now)
        assertEquals(listOf("Buy milk", "Call Sam", "Pay rent"), tasks.map { it.title })
        assertNull(tasks[0].remindAt)
        assertNull(tasks[1].remindAt)
        assertEquals(now.toLocalDate().plusDays(1).atTime(17, 0), tasks[2].remindAt)
    }

    @Test fun `windows cover the complete note without dropping the tail`() {
        val text = (1..90).joinToString("\n") { "Sentence $it describes the meeting and the next action we intend to take." }
        val windows = NoteText.windows(text)
        assertTrue(windows.size > 1)
        assertTrue(windows.all { it.length <= NoteText.WINDOW_CHARS })
        assertEquals(text.replace(Regex("\\s+"), " "), windows.joinToString(" ").replace(Regex("\\s+"), " "))
        assertTrue(windows.last().contains("Sentence 90"))
    }

    @Test fun `oversize input is rejected explicitly rather than silently truncated`() {
        try { NoteText.windows("word ".repeat(10_000)); fail("expected a limit") }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("Split")) }
    }

    @Test fun `every section reaches the model and late tasks survive`() = runTest {
        val seen = mutableListOf<String>()
        val engine = object : MindEngine {
            override val label = "test model"
            override suspend fun extract(text: String, now: LocalDateTime): MindResult {
                seen += text
                return MindResult(if (text.contains("invoice")) listOf(MindTask("Send the invoice", null)) else emptyList())
            }
        }
        val text = "The day was quiet. ".repeat(120) + "I need to send the invoice."
        val result = MindRepository.extractNoteWithFallback(engine, text, now)
        assertTrue(seen.size > 1)
        assertTrue(seen.all { it.length <= NoteText.WINDOW_CHARS })
        assertEquals(listOf("Send the invoice"), result.result.tasks.map { it.title })
    }

    @Test fun `incomplete valid model reply does not discard clear paragraph tasks`() = runTest {
        val engine = object : MindEngine {
            override val label = "test model"
            override suspend fun extract(text: String, now: LocalDateTime) = MindResult(emptyList())
        }
        val result = MindRepository.extractNoteWithFallback(engine,
            "The meeting went well. I need to send the notes. I should call the dentist. Remind me to pay rent tomorrow at 5pm.", now)
        assertEquals(setOf("Send the notes", "Call the dentist", "Pay rent"), result.result.tasks.map { it.title }.toSet())
        assertTrue(result.result.tasks.single { it.title == "Call the dentist" }.suggested)
        assertTrue(result.actualLabel.contains("Basic"))
    }

    @Test fun `failed section retains its rules while successful sections retain model output`() = runTest {
        val engine = object : MindEngine {
            override val label = "test model"
            override suspend fun extract(text: String, now: LocalDateTime): MindResult {
                if (text.contains("rent")) error("bad reply")
                return MindResult(listOf(MindTask("Call the dentist", null)))
            }
        }
        val text = "I need to call the dentist. " + "Background detail. ".repeat(50) + "Remind me to pay rent tomorrow."
        val result = MindRepository.extractNoteWithFallback(engine, text, now)
        assertEquals(setOf("Call the dentist", "Pay rent"), result.result.tasks.map { it.title }.toSet())
        assertTrue(result.actualLabel.contains("Basic"))
        assertNotNull(result.fallbackReason)
    }

    @Test fun `Basic formats but does not rewrite or remove words`() = runTest {
        val result = MindRepository.cleanupWithFallback(DeterministicMind, "  I   might call Sam .\n\n\n I paid  25 yesterday.  ", now)
        assertEquals("I might call Sam.\n\nI paid 25 yesterday.", result.text)
        assertEquals(DeterministicMind.label, result.actualLabel)
    }

    @Test fun `a time beyond a model window is recovered from the complete source clause`() = runTest {
        val text = "Remind me to call Sam about " + "the project details ".repeat(80) + "tomorrow at 5pm."
        val result = MindRepository.extractNoteWithFallback(DeterministicMind, text, now)
        assertEquals(1, result.result.tasks.size)
        assertEquals(now.toLocalDate().plusDays(1).atTime(17, 0), result.result.tasks.single().remindAt)
    }

    @Test fun `cleanup never returns only the successful half of a note`() = runTest {
        val engine = object : MindEngine {
            override val label = "test model"
            override suspend fun extract(text: String, now: LocalDateTime) = MindResult(emptyList())
            override suspend fun cleanup(text: String, now: LocalDateTime): String {
                if (text.contains("last")) error("truncated JSON")
                return "A rewritten first section."
            }
        }
        val text = "All the original facts. ".repeat(50) + "The last section must survive."
        val result = MindRepository.cleanupWithFallback(engine, text, now)
        assertEquals(NoteText.format(text), result.text)
        assertNotNull(result.fallbackReason)
    }

    @Test fun `cleanup timeout falls back and cancellation propagates`() = runTest {
        val engine = object : MindEngine {
            override val label = "test model"
            override suspend fun extract(text: String, now: LocalDateTime) = MindResult(emptyList())
            override suspend fun cleanup(text: String, now: LocalDateTime): String { delay(11_000); return text }
        }
        val result = MindRepository.cleanupWithFallback(engine, "My original note.", now)
        assertEquals("My original note.", result.text)
        assertEquals("Note cleanup took too long", result.fallbackReason)
        val cancelled = object : MindEngine {
            override val label = "test model"
            override suspend fun extract(text: String, now: LocalDateTime) = MindResult(emptyList())
            override suspend fun cleanup(text: String, now: LocalDateTime): String = throw CancellationException("closed")
        }
        try { MindRepository.cleanupWithFallback(cancelled, "note", now); fail("expected cancellation") }
        catch (expected: CancellationException) { assertEquals("closed", expected.message) }
    }

    @Test fun `cleanup parser rejects truncated blank and wrong contract replies`() {
        assertEquals("A complete note.\nNext paragraph.", NoteCleanupParser.parse("""{"text":"A complete note.\nNext paragraph."}"""))
        for (raw in listOf("{\"text\":\"unfinished", "{\"text\":\"\"}", "{\"tasks\":[]}", "{\"text\":3}")) {
            assertTrue(runCatching { NoteCleanupParser.parse(raw) }.isFailure)
        }
    }
}
