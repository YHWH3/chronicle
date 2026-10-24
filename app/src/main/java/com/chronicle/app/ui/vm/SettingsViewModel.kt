package com.chronicle.app.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chronicle.app.data.MindBackend
import com.chronicle.app.data.PrefsStore
import com.chronicle.app.data.SttEngine
import com.chronicle.app.data.ThemeMode
import com.chronicle.app.media.LiveTranscriber
import com.chronicle.app.mind.ChatMind
import com.chronicle.app.mind.DeterministicMind
import com.chronicle.app.mind.MindSource
import com.chronicle.app.mind.ModelInstallManager
import com.chronicle.app.mind.ModelKind
import com.chronicle.app.mind.ModelState
import com.chronicle.app.reminders.ReminderGateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// ————— Settings —————

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dailyNudgeEnabled: Boolean = false,
    val dailyNudgeMinutes: Int = 20 * 60,
    val allowMobileData: Boolean = false,
    val hfSignedIn: Boolean = false,
    val selectedSmartModelId: String = com.chronicle.app.mind.SmartModels.DEFAULT_ID,
    val mindBackend: MindBackend = MindBackend.AUTO,
    val openRouterKey: String = "",
    val openRouterModel: String = "",
    val customBaseUrl: String = "",
    val customModel: String = "",
    val customKey: String = "",
    val sttEngine: SttEngine = SttEngine.SYSTEM,
    val parakeetUrl: String = "",
    val llmUrl: String = "",
    val models: Map<String, ModelState> = emptyMap(),
    // Derived display state — mirrors MindRepository.resolve() exactly.
    val effectiveMindLabel: String = "",
    /** True when the resolved engine is a network backend (entry text leaves). */
    val mindLeavesPhone: Boolean = false,
    /** True when a non-quiet selection fell back to the Quiet parser. */
    val mindFellBack: Boolean = false,
    /** False when Android reports no on-device speech recognition. */
    val systemSttAvailable: Boolean = true,
)

class SettingsViewModel(
    private val prefsRepository: PrefsStore,
    private val modelInstallManager: ModelInstallManager,
    private val mindRepository: MindSource,
    private val transcriber: LiveTranscriber,
    private val reminders: ReminderGateway,
    val smartSetup: com.chronicle.app.mind.SmartSetupController? = null,
    val hfAuth: com.chronicle.app.mind.HfAuthRepository? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(
        SettingsUiState(systemSttAvailable = transcriber.available),
    )
    val state: StateFlow<SettingsUiState> = _state

    init {
        viewModelScope.launch {
            prefsRepository.prefs.collect { p ->
                val engine = mindRepository.resolve(p)
                _state.value = _state.value.copy(
                    themeMode = p.themeMode,
                    dailyNudgeEnabled = p.dailyNudgeEnabled,
                    dailyNudgeMinutes = p.dailyNudgeMinutes,
                    mindBackend = p.mindBackend,
                    selectedSmartModelId = p.selectedSmartModelId,
                    allowMobileData = p.allowMobileData,
                    hfSignedIn = p.hfAccessToken.isNotBlank(),
                    openRouterKey = p.openRouterKey,
                    openRouterModel = p.openRouterModel,
                    customBaseUrl = p.customBaseUrl,
                    customModel = p.customModel,
                    customKey = p.customKey,
                    sttEngine = p.sttEngine,
                    parakeetUrl = p.parakeetUrl,
                    llmUrl = p.llmUrl,
                    effectiveMindLabel = engine.label,
                    mindLeavesPhone = engine is ChatMind,
                    mindFellBack = engine === DeterministicMind &&
                        p.mindBackend != MindBackend.QUIET_PARSER,
                )
            }
        }
        viewModelScope.launch {
            modelInstallManager.states.collect { m ->
                _state.value = _state.value.copy(models = m)
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { prefsRepository.setThemeMode(mode) }
    }

    /**
     * enabled + minutes arrive together and commit in ONE prefs write — the
     * alarm is then armed/cancelled from the same pair, never from a stale
     * `state.value` read (FE-11). The caller passes the current values.
     */
    fun setDailyNudge(enabled: Boolean, minutes: Int) {
        viewModelScope.launch {
            prefsRepository.setDailyNudge(enabled, minutes)
            if (enabled) reminders.scheduleDailyNudge(minutes)
            else reminders.cancelDailyNudge()
        }
    }

    fun setMindBackend(backend: MindBackend) {
        viewModelScope.launch {
            if (backend == MindBackend.ON_DEVICE) prefsRepository.setSelectedSmartModel("custom")
            prefsRepository.setMindBackend(backend)
        }
    }

    fun selectSmartModel(id: String) {
        viewModelScope.launch {
            prefsRepository.setSelectedSmartModel(id)
            prefsRepository.setMindBackend(MindBackend.ON_DEVICE)
        }
    }

    fun installSmartModel(id: String) {
        val model = com.chronicle.app.mind.SmartModels.find(id) ?: return
        smartSetup?.download()
    }
    fun pauseSmartModel(id: String) {
        smartSetup?.pause()
    }
    fun deleteSmartModel(id: String) {
        com.chronicle.app.mind.SmartModels.find(id)?.let { model ->
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { smartSetup?.delete(model.id) }
        }
    }

    fun signOutHf() {
        viewModelScope.launch { hfAuth?.signOut() }
    }

    fun setSttEngine(engine: SttEngine) {
        viewModelScope.launch { prefsRepository.setSttEngine(engine) }
    }

    fun setOpenRouter(key: String, model: String) {
        viewModelScope.launch { prefsRepository.setOpenRouter(key, model) }
    }

    fun setCustomEndpoint(baseUrl: String, model: String, key: String) {
        viewModelScope.launch { prefsRepository.setCustomEndpoint(baseUrl, model, key) }
    }

    fun setModelUrls(parakeetUrl: String, llmUrl: String) {
        viewModelScope.launch { prefsRepository.setModelUrls(parakeetUrl, llmUrl) }
    }

    /**
     * Delegates to the app-scoped [ModelInstallManager] — the download is
     * owned by the app, not this ViewModel, so it survives leaving Settings.
     */
    fun installModel(kind: ModelKind) {
        val url = when (kind) {
            ModelKind.PARAKEET_STT -> _state.value.parakeetUrl
            ModelKind.LOCAL_LLM -> _state.value.llmUrl
        }
        modelInstallManager.install(kind, url)
    }

    fun cancelModelDownload(kind: ModelKind) = modelInstallManager.cancel(kind)

    /** Deleting also releases any loaded engine via the manager's listeners. */
    fun deleteModel(kind: ModelKind) {
        viewModelScope.launch { modelInstallManager.delete(kind) }
    }
}
