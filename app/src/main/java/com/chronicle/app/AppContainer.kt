package com.chronicle.app

import android.content.Context
import androidx.room.Room
import com.chronicle.app.data.JournalRepository
import com.chronicle.app.data.PrefsRepository
import com.chronicle.app.data.TaskRepository
import com.chronicle.app.data.db.ChronicleDatabase
import com.chronicle.app.media.EntryPlayer
import com.chronicle.app.media.SpeechTranscriber
import com.chronicle.app.media.VoiceRecorder
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

    val voiceRecorder = VoiceRecorder(appContext, appScope)
    val transcriber = SpeechTranscriber(appContext)
    val player = EntryPlayer(appScope)
}
