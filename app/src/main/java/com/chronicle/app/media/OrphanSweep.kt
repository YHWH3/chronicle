package com.chronicle.app.media

import android.content.Context
import com.chronicle.app.data.JournalRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * BE-18 — app-start sweep for files the journal no longer references.
 *
 * Two families are cleaned:
 *  - `filesDir/recordings/note_*.{m4a,wav}` takes whose path no entry row
 *    references (deleted-entry leftovers, crashed-pipeline orphans),
 *  - `.part` download temps under `filesDir/models`, abandoned by a killed
 *    install (ModelStore deletes them on cancel/failure, but a process death
 *    mid-download leaves one behind).
 *
 * Everything must age past [DEFAULT_MIN_AGE_MS] first: a 24h bound protects
 * a take still being captured (the foreground service permits long takes)
 * and exempts in-flight downloads — an active `.part` file is always fresh.
 * If the reference set can't be loaded the sweep refuses to run: deleting
 * blind could wipe every take.
 */
object OrphanSweep {

    const val DEFAULT_MIN_AGE_MS: Long = 24L * 60 * 60 * 1000

    /**
     * Deletes unreferenced `note_*.m4a/.wav` files older than [minAgeMs].
     * Only our own take-name pattern is eligible, and a file is referenced
     * if EITHER its absolute or canonical path appears in [referencedPaths].
     * Returns the number of files removed.
     */
    fun sweepRecordings(
        recordingsDir: File,
        referencedPaths: Set<String>,
        now: Long = System.currentTimeMillis(),
        minAgeMs: Long = DEFAULT_MIN_AGE_MS,
    ): Int {
        var removed = 0
        recordingsDir.listFiles()?.forEach { f ->
            if (!f.isFile || !f.name.startsWith("note_")) return@forEach
            if (f.extension != "m4a" && f.extension != "wav") return@forEach
            if (f.absolutePath in referencedPaths) return@forEach
            if (runCatching { f.canonicalPath }.getOrNull() in referencedPaths) return@forEach
            if (now - f.lastModified() <= minAgeMs) return@forEach
            if (f.delete()) removed++
        }
        return removed
    }

    /**
     * Deletes `*.part` temps older than [minAgeMs] anywhere under the models
     * root. Returns the number of files removed.
     */
    fun sweepModelTemps(
        modelsDir: File,
        now: Long = System.currentTimeMillis(),
        minAgeMs: Long = DEFAULT_MIN_AGE_MS,
    ): Int {
        var removed = 0
        modelsDir.walkTopDown().forEach { f ->
            if (!f.isFile || f.extension != "part") return@forEach
            if (now - f.lastModified() <= minAgeMs) return@forEach
            if (f.delete()) removed++
        }
        return removed
    }

    /**
     * App-start entry point. Reads the referenced-audio set once, then sweeps
     * on a background dispatcher. A failed reference read aborts the whole
     * sweep — without it every take looks orphaned.
     */
    suspend fun run(
        context: Context,
        journalRepository: JournalRepository,
        now: Long = System.currentTimeMillis(),
    ) {
        val referenced = try {
            journalRepository.allOnce().mapNotNull { it.audioPath }.toSet()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return
        }
        withContext(Dispatchers.IO) {
            runCatching {
                sweepRecordings(File(context.filesDir, "recordings"), referenced, now)
            }
            runCatching {
                sweepModelTemps(File(context.filesDir, "models"), now)
            }
        }
    }
}
