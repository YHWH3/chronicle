package com.chronicle.app.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chronicle.app.data.MindBackend
import com.chronicle.app.data.PrefsRepository
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
    val mindBackend: MindBackend = MindBackend.QUIET_PARSER,
    val openRouterKey: String = "",
    val openRouterModel: String = "",
    val customBaseUrl: String = "",
    val customModel: String = "",
    val customKey: String = "",
    val sttEngine: SttEngine = SttEngine.SYSTEM,
    val parakeetUrl: String = "",
    val llmUrl: String = "",
    val models: Map<ModelKind, ModelState> = emptyMap(),
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
    private val prefsRepository: PrefsRepository,
    private val modelInstallManager: ModelInstallManager,
    private val mindRepository: MindSource,
    private val transcriber: LiveTranscriber,
    private val reminders: ReminderGateway,
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

    fun setDailyNudge(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepository.setDailyNudge(enabled)
            val minutes = state.value.dailyNudgeMinutes
            if (enabled) reminders.scheduleDailyNudge(minutes)
            else reminders.cancelDailyNudge()
        }
    }

    fun setDailyNudgeMinutes(minutes: Int) {
        viewModelScope.launch {
            prefsRepository.setDailyNudgeMinutes(minutes)
            if (state.value.dailyNudgeEnabled) {
                reminders.scheduleDailyNudge(minutes)
            }
        }
    }

    fun setMindBackend(backend: MindBackend) {
        viewModelScope.launch { prefsRepository.setMindBackend(backend) }
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
