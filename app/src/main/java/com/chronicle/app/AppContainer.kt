package com.chronicle.app

import android.content.Context
import androidx.room.Room
import com.chronicle.app.core.UiMessageBus
import com.chronicle.app.data.JournalRepository
import com.chronicle.app.data.PrefsRepository
import com.chronicle.app.data.SecretStore
import com.chronicle.app.data.TaskRepository
import com.chronicle.app.data.db.ChronicleDatabase
import com.chronicle.app.media.EntryPlayer
import com.chronicle.app.media.ParakeetTranscriber
import com.chronicle.app.media.SpeechTranscriber
import com.chronicle.app.media.VoiceRecorder
import com.chronicle.app.media.WavRecorder
import com.chronicle.app.mind.LlmEngineHolder
import com.chronicle.app.mind.LocalLlmMind
import com.chronicle.app.mind.MindRepository
import com.chronicle.app.mind.ModelInstallManager
import com.chronicle.app.mind.ModelKind
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
    ).addMigrations(ChronicleDatabase.MIGRATION_1_2).build()

    val journalRepository = JournalRepository(database.entryDao(), com.chronicle.app.data.NoteDetailsStore(appContext))
    val taskRepository = TaskRepository(database.taskDao())

    /**
     * Keystore-backed sealer for API keys at rest. Null when the keystore is
     * unavailable — PrefsRepository keeps plaintext working then (BE-24).
     */
    private val secretStore = SecretStore.create()
    val prefsRepository = PrefsRepository(appContext, secretStore)

    /** The single channel one-shot user-facing messages travel on. */
    val uiMessages = UiMessageBus()
    val reminderGateway: ReminderGateway = AndroidReminderGateway(appContext)

    val voiceRecorder = VoiceRecorder(appContext, appScope)
    val wavRecorder = WavRecorder(appContext, appScope)
    val transcriber = SpeechTranscriber(appContext)
    val player = EntryPlayer(appScope, uiMessages, context = appContext)

    /**
     * Model downloads own their own scope — a transfer must keep running
     * (and stay resumable) after the user navigates away from Settings.
     */
    private val installScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Pure on-disk model state — shared by the installer and the engines. */
    private val modelDisk = ModelStore(appContext)

    /** The model download/install owner — also answers "is the pack on disk". */
    val modelStore = ModelInstallManager(modelDisk, installScope)
    val modelDownloads = com.chronicle.app.mind.ModelDownloads(appContext)
    val hfAuth = com.chronicle.app.mind.HfAuthRepository(prefsRepository)
    val parakeetTranscriber = ParakeetTranscriber(appContext, modelDisk)

    /**
     * The single on-device LLM instance — cached by model path so captures
     * never pay a reload; released on backend change or model delete.
     */
    val llmEngineHolder = LlmEngineHolder { path -> LocalLlmMind(appContext, path) }
    val mindRepository = MindRepository(prefsRepository, modelDisk, llmEngineHolder)

    val smartSetup = com.chronicle.app.mind.SmartSetupController(
        appContext, prefsRepository, journalRepository, modelStore, modelDownloads, mindRepository, appScope,
    )

    init {
        // A pack coming or going invalidates whatever engine held its files.
        modelStore.addModelChangeListener { kind, installed ->
            when (kind) {
                com.chronicle.app.mind.SmartModels.PARAKEET_PACK -> {
                    parakeetTranscriber.invalidateAvailabilityCache()
                    if (!installed) parakeetTranscriber.release()
                }
                else -> llmEngineHolder.releaseIfDirectory(modelStore.dir(kind).absolutePath)
            }
        }
    }
}
