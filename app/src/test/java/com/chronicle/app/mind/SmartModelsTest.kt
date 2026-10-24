package com.chronicle.app.mind

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime

class SmartModelsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `catalog has fixed anonymous default and separate gated packs`() {
        assertEquals(3, SmartModels.all.size)
        val qwen = SmartModels.find(SmartModels.DEFAULT_ID)!!
        assertFalse(qwen.gated)
        assertEquals(1_598_556_720L, qwen.bytes)
        assertEquals(Runtime.MEDIAPIPE, qwen.runtime)
        assertEquals(3, SmartModels.all.map { it.packId }.toSet().size)
        SmartModels.all.forEach { model ->
            assertTrue(model.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(model.url.startsWith("https://huggingface.co/"))
        }
        assertTrue(SmartModels.all.drop(1).all { it.gated })
    }

    @Test fun `RAM gate includes boundary and excludes lower memory`() {
        SmartModels.all.forEach { model ->
            val required = model.minRamGb * 1_000_000_000L
            assertTrue(model.supportedBy(required))
            assertFalse(model.supportedBy(required - 1))
            assertFalse(model.supportedBy(0))
        }
    }

    @Test fun `packs are independent and Parakeet keeps its original path`() {
        val store = ModelStore(tmp.newFolder())
        assertEquals("parakeet_stt", store.dirName(SmartModels.PARAKEET_PACK))
        for (model in SmartModels.all) {
            val dir = store.dir(model.packId)
            dir.mkdirs()
            File(dir, model.file).writeText(model.id)
            store.writeMarker(dir)
        }
        SmartModels.all.forEach { model ->
            assertEquals(model.id, store.localLlmFile(model.packId)!!.readText())
        }
        assertNull(store.localLlmFile(SmartModels.CUSTOM_PACK))
    }

    @Test fun `unknown and traversal pack IDs cannot reach the disk`() {
        val store = ModelStore(tmp.newFolder())
        listOf("../outside", "llm/../../outside", "llm/unknown", "/absolute").forEach { id ->
            assertTrue(runCatching { store.dir(id) }.isFailure)
        }
    }

    @Test fun `legacy custom file and resumable transfer migrate without touching Parakeet`() {
        val store = ModelStore(tmp.newFolder())
        val legacy = File(store.root, "local_llm")
        legacy.mkdirs()
        File(legacy, "old.task").writeText("model")
        store.writeMarker(legacy)
        File(store.root, "local_llm.part").writeText("partial")
        val parakeet = store.dir(ModelKind.PARAKEET_STT)
        parakeet.mkdirs()
        File(parakeet, "keep").writeText("untouched")
        store.migrateCustomPack()
        assertEquals("model", store.localLlmFile()!!.readText())
        assertEquals("partial", File(store.root, "llm/custom.part").readText())
        assertEquals("untouched", File(parakeet, "keep").readText())
        store.migrateCustomPack()
        assertEquals("model", store.localLlmFile()!!.readText())
    }

    @Test fun `prompt formats retain clock contract and capture`() {
        val now = LocalDateTime.of(2026, 10, 4, 16, 43)
        val plain = PromptFormat.PLAIN.render("call mum", now)
        assertTrue(plain.contains(MIND_PROMPT))
        assertTrue(plain.contains(now.toString()))
        val chat = PromptFormat.CHAT_ML.render("call mum", now)
        assertTrue(chat.startsWith("<|im_start|>system\n"))
        assertTrue(chat.contains("<|im_start|>user\n"))
        assertTrue(chat.endsWith("<|im_start|>assistant\n"))
    }
}
