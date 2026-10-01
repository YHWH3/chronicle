package com.chronicle.app

import android.content.Context
import androidx.room.Room
import com.chronicle.app.core.UiMessageBus
import com.chronicle.app.data.JournalRepository
import com.chronicle.app.data.PrefsRepository
import com.chronicle.app.data.TaskRepository
import com.chronicle.app.data.db.ChronicleDatabase
import com.chronicle.app.media.EntryPlayer
import com.chronicle.app.media.ParakeetTranscriber
import com.chronicle.app.media.SpeechTranscriber
import com.chronicle.app.media.VoiceRecorder
import com.chronicle.app.media.WavRecorder
import com.chronicle.app.mind.MindRepository
import com.chronicle.app.mind.ModelStore
import com.chronicle.app.reminders.AndroidReminderGateway
import com.chronicle.app.reminders.ReminderGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun appContextRef(): Context = appContext

    private val database: ChronicleDatabase = Room.databaseBuilder(
        appContext,
        ChronicleDatabase::class.java,
        "chronicle.db",
    ).build()

    val journalRepository = JournalRepository(database.entryDao())
    val taskRepository = TaskRepository(database.taskDao())
    val prefsRepository = PrefsRepository(appContext)

    /** The single channel one-shot user-facing messages travel on. */
    val uiMessages = UiMessageBus()
    val reminderGateway: ReminderGateway = AndroidReminderGateway(appContext)

    val voiceRecorder = VoiceRecorder(appContext, appScope)
    val wavRecorder = WavRecorder(appContext, appScope)
    val transcriber = SpeechTranscriber(appContext)
    val player = EntryPlayer(appScope, uiMessages)

    val modelStore = ModelStore(appContext)
    val mindRepository = MindRepository(appContext, prefsRepository, modelStore)
    val parakeetTranscriber = ParakeetTranscriber(appContext, modelStore)
}
