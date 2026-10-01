package com.chronicle.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** BE-18 — the sweep removes only stale, unreferenced, own-pattern files. */
class OrphanSweepTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun stale(file: File, now: Long, ageMs: Long = OrphanSweep.DEFAULT_MIN_AGE_MS + 1_000) {
        file.setLastModified(now - ageMs)
    }

    @Test
    fun `unreferenced stale takes are deleted`() {
        val now = System.currentTimeMillis()
        val dir = tmp.newFolder("recordings")
        val orphan = File(dir, "note_1.m4a").apply { writeText("x") }
        val wavOrphan = File(dir, "note_2.wav").apply { writeText("x") }
        stale(orphan, now)
        stale(wavOrphan, now)

        val removed = OrphanSweep.sweepRecordings(dir, emptySet(), now)

        assertEquals(2, removed)
        assertFalse(orphan.exists())
        assertFalse(wavOrphan.exists())
    }

    @Test
    fun `referenced takes survive even when stale`() {
        val now = System.currentTimeMillis()
        val dir = tmp.newFolder("recordings")
        val kept = File(dir, "note_3.m4a").apply { writeText("x") }
        stale(kept, now)

        val removed = OrphanSweep.sweepRecordings(dir, setOf(kept.absolutePath), now)

        assertEquals(0, removed)
        assertTrue(kept.exists())
    }

    @Test
    fun `fresh orphans and foreign files are left alone`() {
        val now = System.currentTimeMillis()
        val dir = tmp.newFolder("recordings")
        // Unreferenced but young — could be a take still being captured.
        val fresh = File(dir, "note_4.m4a").apply { writeText("x") }
        stale(fresh, now, ageMs = 60_000)
        // Old but not our name pattern — never the sweep's business.
        val foreign = File(dir, "other_5.m4a").apply { writeText("x") }
        stale(foreign, now)
        // Right name, wrong extension — e.g. a future sidecar format.
        val oddExt = File(dir, "note_6.txt").apply { writeText("x") }
        stale(oddExt, now)

        val removed = OrphanSweep.sweepRecordings(dir, emptySet(), now)

        assertEquals(0, removed)
        assertTrue(fresh.exists())
        assertTrue(foreign.exists())
        assertTrue(oddExt.exists())
    }

    @Test
    fun `stale part temps are deleted but fresh downloads survive`() {
        val now = System.currentTimeMillis()
        val dir = tmp.newFolder("models")
        val pack = File(dir, "parakeet_stt").apply { mkdirs() }
        val stalePart = File(pack, "download.part").apply { writeText("x") }
        stale(stalePart, now)
        // A young .part is an in-flight download — exempt.
        val activePart = File(pack, "other.part").apply { writeText("x") }
        // Non-temp files are never touched.
        val model = File(pack, "encoder.int8.onnx").apply { writeText("x") }
        stale(model, now)

        val removed = OrphanSweep.sweepModelTemps(dir, now)

        assertEquals(1, removed)
        assertFalse(stalePart.exists())
        assertTrue(activePart.exists())
        assertTrue(model.exists())
    }
}
