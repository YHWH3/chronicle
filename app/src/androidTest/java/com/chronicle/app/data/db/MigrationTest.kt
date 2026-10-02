package com.chronicle.app.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v1 → v2 migration: every pre-existing row must survive, new columns get
 * sane defaults, and the four lookup indexes exist.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbName = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ChronicleDatabase::class.java,
    )

    @Test
    fun migrate1To2PreservesRows() {
        // A v1 fixture — the column set Room exported as schemas/1.json.
        helper.createDatabase(dbName, 1).apply {
            execSQL(
                "INSERT INTO entries (createdAt, day, kind, transcript, audioPath, durationMs) " +
                    "VALUES (1000, '2026-01-10', 'text', 'a quiet morning', NULL, 0)",
            )
            execSQL(
                "INSERT INTO entries (createdAt, day, kind, transcript, audioPath, durationMs) " +
                    "VALUES (2000, '2026-01-10', 'voice', '', '/rec/n.m4a', 4000)",
            )
            execSQL(
                "INSERT INTO tasks (title, createdAt, remindAt, done, doneAt, sourceEntryId) " +
                    "VALUES ('call Sam', 1000, 999999, 0, NULL, 1)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 2, true, ChronicleDatabase.MIGRATION_1_2)

        db.query("SELECT transcript, transcriptStatus, zoneId FROM entries WHERE id = 1")
            .use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("a quiet morning", c.getString(0))
                assertEquals("ready", c.getString(1))
                assertTrue(c.isNull(2))
            }
        // A legacy voice row with an empty transcript was never visibly
        // failing — it migrates as an audio note, not a failure.
        db.query("SELECT transcriptStatus FROM entries WHERE id = 2")
            .use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("not_requested", c.getString(0))
            }
        db.query("SELECT title, sourceEntryId, notifiedAt FROM tasks WHERE id = 1")
            .use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("call Sam", c.getString(0))
                assertEquals(1L, c.getLong(1))
                assertTrue(c.isNull(2))
            }
        db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'index_%'")
            .use { c ->
                val names = mutableListOf<String>()
                while (c.moveToNext()) names += c.getString(0)
                assertTrue("index_entries_day" in names)
                assertTrue("index_entries_createdAt" in names)
                assertTrue("index_tasks_remindAt" in names)
                assertTrue("index_tasks_done" in names)
            }
    }
}
