package com.chronicle.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "chronicle_prefs")

enum class ThemeMode { SYSTEM, PAPER, INK }

/** How captured words are turned into tasks and reflections. */
enum class MindBackend(val title: String) {
    /** Deterministic parser — nothing leaves the phone, nothing to download. */
    QUIET_PARSER("Quiet parser"),
    /** A MediaPipe .task bundle (e.g. Llama 3.2 3B) running on-device. */
    ON_DEVICE("On-device model"),
    /** OpenRouter — incl. free-tier models — with the user's own API key. */
    OPENROUTER("OpenRouter"),
    /** Any OpenAI-compatible endpoint (OpenAI, Groq, Together, Gemini-compat). */
    CUSTOM("Custom endpoint"),
}

/** How speech is turned into text. */
enum class SttEngine(val title: String) {
    SYSTEM("System (on-device)"),
    PARAKEET("Parakeet TDT 0.6B"),
}

data class ChroniclePrefs(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dailyNudgeEnabled: Boolean = false,
    /** Minutes after midnight for the daily writing nudge. */
    val dailyNudgeMinutes: Int = 20 * 60,
    val mindBackend: MindBackend = MindBackend.QUIET_PARSER,
    val openRouterKey: String = "",
    val openRouterModel: String = "meta-llama/llama-3.2-3b-instruct:free",
    val customBaseUrl: String = "",
    val customModel: String = "",
    val customKey: String = "",
    val sttEngine: SttEngine = SttEngine.SYSTEM,
    /** Override download URLs — HF models are license-gated, so users paste their own. */
    val parakeetUrl: String = DEFAULT_PARAKEET_URL,
    val llmUrl: String = DEFAULT_LLM_URL,
) {
    companion object {
        const val DEFAULT_PARAKEET_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
                "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2"
        const val DEFAULT_LLM_URL =
            "https://huggingface.co/litert-community/Llama-3.2-3B-Instruct/resolve/main/" +
                "Llama-3.2-3B-Instruct_multi-prefill-seq_q8_ekv1280.task"
    }
}

/** Read-side of preferences — the surface ViewModels need to observe settings. */
interface PrefsSource {
    val prefs: Flow<ChroniclePrefs>
}

class PrefsRepository(private val context: Context) : PrefsSource {

    override val prefs: Flow<ChroniclePrefs> = context.dataStore.data.map { p ->
        ChroniclePrefs(
            themeMode = p[KEY_THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            dailyNudgeEnabled = p[KEY_NUDGE_ENABLED] ?: false,
            dailyNudgeMinutes = p[KEY_NUDGE_MINUTES] ?: (20 * 60),
            mindBackend = p[KEY_MIND]?.let { runCatching { MindBackend.valueOf(it) }.getOrNull() }
                ?: MindBackend.QUIET_PARSER,
            openRouterKey = p[KEY_OR_KEY] ?: "",
            openRouterModel = p[KEY_OR_MODEL]
                ?: "meta-llama/llama-3.2-3b-instruct:free",
            customBaseUrl = p[KEY_CUSTOM_URL] ?: "",
            customModel = p[KEY_CUSTOM_MODEL] ?: "",
            customKey = p[KEY_CUSTOM_KEY] ?: "",
            sttEngine = p[KEY_STT]?.let { runCatching { SttEngine.valueOf(it) }.getOrNull() }
                ?: SttEngine.SYSTEM,
            parakeetUrl = p[KEY_PARAKEET_URL] ?: ChroniclePrefs.DEFAULT_PARAKEET_URL,
            llmUrl = p[KEY_LLM_URL] ?: ChroniclePrefs.DEFAULT_LLM_URL,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[KEY_THEME] = mode.name }
    }

    suspend fun setDailyNudge(enabled: Boolean) {
        context.dataStore.edit { it[KEY_NUDGE_ENABLED] = enabled }
    }

    suspend fun setDailyNudgeMinutes(minutes: Int) {
        context.dataStore.edit { it[KEY_NUDGE_MINUTES] = minutes }
    }

    suspend fun setMindBackend(backend: MindBackend) {
        context.dataStore.edit { it[KEY_MIND] = backend.name }
    }

    suspend fun setOpenRouter(key: String, model: String) {
        context.dataStore.edit {
            it[KEY_OR_KEY] = key
            it[KEY_OR_MODEL] = model
        }
    }

    suspend fun setCustomEndpoint(baseUrl: String, model: String, key: String) {
        context.dataStore.edit {
            it[KEY_CUSTOM_URL] = baseUrl
            it[KEY_CUSTOM_MODEL] = model
            it[KEY_CUSTOM_KEY] = key
        }
    }

    suspend fun setSttEngine(engine: SttEngine) {
        context.dataStore.edit { it[KEY_STT] = engine.name }
    }

    suspend fun setModelUrls(parakeetUrl: String, llmUrl: String) {
        context.dataStore.edit {
            it[KEY_PARAKEET_URL] = parakeetUrl
            it[KEY_LLM_URL] = llmUrl
        }
    }

    private companion object {
        val KEY_THEME = stringPreferencesKey("theme_mode")
        val KEY_NUDGE_ENABLED = booleanPreferencesKey("daily_nudge_enabled")
        val KEY_NUDGE_MINUTES = intPreferencesKey("daily_nudge_minutes")
        val KEY_MIND = stringPreferencesKey("mind_backend")
        val KEY_OR_KEY = stringPreferencesKey("openrouter_key")
        val KEY_OR_MODEL = stringPreferencesKey("openrouter_model")
        val KEY_CUSTOM_URL = stringPreferencesKey("custom_base_url")
        val KEY_CUSTOM_MODEL = stringPreferencesKey("custom_model")
        val KEY_CUSTOM_KEY = stringPreferencesKey("custom_key")
        val KEY_STT = stringPreferencesKey("stt_engine")
        val KEY_PARAKEET_URL = stringPreferencesKey("parakeet_url")
        val KEY_LLM_URL = stringPreferencesKey("llm_url")
    }
}
