package com.chronicle.app.ui.vm

import com.chronicle.app.data.ChroniclePrefs
import com.chronicle.app.data.MindBackend
import com.chronicle.app.data.PrefsStore
import com.chronicle.app.data.SttEngine
import com.chronicle.app.data.ThemeMode
import com.chronicle.app.media.LiveTranscriber
import com.chronicle.app.mind.DeterministicMind
import com.chronicle.app.mind.MindEngine
import com.chronicle.app.mind.MindExecutionResult
import com.chronicle.app.mind.MindResult
import com.chronicle.app.mind.MindSource
import com.chronicle.app.mind.ModelInstallManager
import com.chronicle.app.mind.ModelStore
import com.chronicle.app.reminders.ReminderGateway
import java.time.LocalDateTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * FE-11 — the nudge toggle and the time pick used to write enabled/minutes
 * in separate edits, so a quick toggle+pick could persist a mismatched pair
 * (and the VM armed the alarm off a stale `state.value` read). The repo now
 * takes both values into ONE edit; these tests pin the single-write seam and
 * the alarm armed from the caller's values.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsNudgeTest {

    @get:Rule val mainRule = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private class FakePrefsStore(initial: ChroniclePrefs = ChroniclePrefs()) : PrefsStore {
        override val prefs = MutableStateFlow(initial)
        /** Every recorded write — "nudge:<enabled>:<minutes>" lands as ONE call. */
        val writes = mutableListOf<String>()

        override suspend fun setThemeMode(mode: ThemeMode) {}
        override suspend fun setDailyNudge(enabled: Boolean, minutes: Int) {
            writes += "nudge:$enabled:$minutes"
            prefs.value = prefs.value.copy(
                dailyNudgeEnabled = enabled,
                dailyNudgeMinutes = minutes,
            )
        }
        override suspend fun setMindBackend(backend: MindBackend) {}
        override suspend fun setOpenRouter(key: String, model: String) {}
        override suspend fun setCustomEndpoint(baseUrl: String, model: String, key: String) {}
        override suspend fun setSttEngine(engine: SttEngine) {}
        override suspend fun setModelUrls(parakeetUrl: String, llmUrl: String) {}
    }

    private class FakeReminders : ReminderGateway {
        val nudgeSchedules = mutableListOf<Int>()
        var nudgeCancels = 0
        override fun scheduleTask(taskId: Long, title: String, atMillis: Long) {}
        override fun cancelTask(taskId: Long) {}
        override fun notifyTask(taskId: Long, title: String) {}
        override fun cancelNotification(taskId: Long) {}
        override fun scheduleDailyNudge(minutesAfterMidnight: Int) {
            nudgeSchedules += minutesAfterMidnight
        }
        override fun cancelDailyNudge() {
            nudgeCancels++
        }
        override fun notificationsEnabled() = true
    }

    private class FakeMind : MindSource {
        override fun resolve(prefs: ChroniclePrefs): MindEngine = DeterministicMind
        override suspend fun extract(text: String, now: LocalDateTime) =
            MindExecutionResult(MindResult(emptyList()), "fake", "fake", null)
    }

    private class FakeLiveTranscriber : LiveTranscriber {
        override val available = true
        override val partial = MutableStateFlow("")
        override val committed = MutableStateFlow<List<String>>(emptyList())
        override val transcript get() = ""
        override fun start() {}
        override suspend fun stop() {}
        override fun destroyNow() {}
    }

    private fun vm(
        prefs: FakePrefsStore,
        reminders: FakeReminders,
        scope: kotlinx.coroutines.CoroutineScope,
    ) = SettingsViewModel(
        prefsRepository = prefs,
        modelInstallManager = ModelInstallManager(
            store = ModelStore(tmp.newFolder("models")),
            scope = scope,
        ),
        mindRepository = FakeMind(),
        transcriber = FakeLiveTranscriber(),
        reminders = reminders,
    )

    @Test
    fun `enabling the nudge is one write carrying both fields`() = runTest {
        val prefs = FakePrefsStore()
        val reminders = FakeReminders()
        val v = vm(prefs, reminders, backgroundScope)
        runCurrent()

        v.setDailyNudge(enabled = true, minutes = 7 * 60)
        runCurrent()

        // A single atomic write — no separate enabled/minutes commits.
        assertEquals(listOf("nudge:true:420"), prefs.writes)
        assertTrue(prefs.prefs.value.dailyNudgeEnabled)
        assertEquals(420, prefs.prefs.value.dailyNudgeMinutes)
        // …and the alarm was armed from those values, not a stale state read.
        assertEquals(listOf(420), reminders.nudgeSchedules)
        assertEquals(0, reminders.nudgeCancels)
    }

    @Test
    fun `disabling commits both fields and cancels the alarm`() = runTest {
        val prefs = FakePrefsStore(
            ChroniclePrefs(dailyNudgeEnabled = true, dailyNudgeMinutes = 480),
        )
        val reminders = FakeReminders()
        val v = vm(prefs, reminders, backgroundScope)
        runCurrent()

        v.setDailyNudge(enabled = false, minutes = 480)
        runCurrent()

        assertEquals(listOf("nudge:false:480"), prefs.writes)
        assertEquals(1, reminders.nudgeCancels)
        assertTrue(reminders.nudgeSchedules.isEmpty())
    }

    @Test
    fun `a time pick re-arms at the new time in the same write`() = runTest {
        val prefs = FakePrefsStore(
            ChroniclePrefs(dailyNudgeEnabled = true, dailyNudgeMinutes = 1_200),
        )
        val reminders = FakeReminders()
        val v = vm(prefs, reminders, backgroundScope)
        runCurrent()

        // The UI passes the pair — enabled stays true, minutes move.
        v.setDailyNudge(enabled = true, minutes = 540)
        runCurrent()

        assertEquals(listOf("nudge:true:540"), prefs.writes)
        assertEquals(listOf(540), reminders.nudgeSchedules)
    }
}
