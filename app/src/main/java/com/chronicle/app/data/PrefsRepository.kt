package com.chronicle.app.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
        /**
         * No default LLM source: the usual .task bundles are licence-gated,
         * so shipping a dead URL would just fail. The field starts blank and
         * the user pastes their own model archive link.
         */
        const val DEFAULT_LLM_URL = ""
    }
}

/** Read-side of preferences — the surface ViewModels need to observe settings. */
interface PrefsSource {
    val prefs: Flow<ChroniclePrefs>
}

/**
 * Read + write surface of preferences. [PrefsRepository] is the production
 * implementation; ViewModels take this interface so unit tests can fake the
 * whole store without a Context/DataStore.
 */
interface PrefsStore : PrefsSource {
    suspend fun setThemeMode(mode: ThemeMode)

    /**
     * enabled + minutes commit in ONE edit — a toggle racing a time pick
     * can never persist a half-updated pair (FE-11).
     */
    suspend fun setDailyNudge(enabled: Boolean, minutes: Int)

    suspend fun setMindBackend(backend: MindBackend)
    suspend fun setOpenRouter(key: String, model: String)
    suspend fun setCustomEndpoint(baseUrl: String, model: String, key: String)
    suspend fun setSttEngine(engine: SttEngine)
    suspend fun setModelUrls(parakeetUrl: String, llmUrl: String)
}

/**
 * Preferences backed by DataStore. API keys are sealed with the
 * Android-Keystore AES/GCM [SecretStore] before they hit disk: stored
 * values read "enc:" + base64(iv‖cipher). Legacy plaintext values still
 * read correctly and are sealed on the fly (write-back happens off the
 * read path — an `edit` inside `data`'s transform would serialize behind
 * the read that produced it). When the keystore is unavailable
 * ([secretStore] == null) secrets stay plaintext — functionality over
 * secrecy, with the residual risk documented in the audit.
 */
class PrefsRepository(
    private val context: Context,
    private val secretStore: SecretStore? = null,
) : PrefsStore {

    /** Serializes the one-shot plaintext→sealed write-backs. */
    private val migrationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val prefs: Flow<ChroniclePrefs> = context.dataStore.data.map { p ->
        val pendingSeal = mutableListOf<Pair<Preferences.Key<String>, String>>()
        val openRouterKey = unseal(p[KEY_OR_KEY], KEY_OR_KEY, pendingSeal)
        val customKey = unseal(p[KEY_CUSTOM_KEY], KEY_CUSTOM_KEY, pendingSeal)
        queueSealWriteBack(pendingSeal)
        ChroniclePrefs(
            themeMode = p[KEY_THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            dailyNudgeEnabled = p[KEY_NUDGE_ENABLED] ?: false,
            dailyNudgeMinutes = p[KEY_NUDGE_MINUTES] ?: (20 * 60),
            mindBackend = p[KEY_MIND]?.let { runCatching { MindBackend.valueOf(it) }.getOrNull() }
                ?: MindBackend.QUIET_PARSER,
            openRouterKey = openRouterKey,
            openRouterModel = p[KEY_OR_MODEL]
                ?: "meta-llama/llama-3.2-3b-instruct:free",
            customBaseUrl = p[KEY_CUSTOM_URL] ?: "",
            customModel = p[KEY_CUSTOM_MODEL] ?: "",
            customKey = customKey,
            sttEngine = p[KEY_STT]?.let { runCatching { SttEngine.valueOf(it) }.getOrNull() }
                ?: SttEngine.SYSTEM,
            parakeetUrl = p[KEY_PARAKEET_URL] ?: ChroniclePrefs.DEFAULT_PARAKEET_URL,
            llmUrl = p[KEY_LLM_URL] ?: ChroniclePrefs.DEFAULT_LLM_URL,
        )
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[KEY_THEME] = mode.name }
    }

    override suspend fun setDailyNudge(enabled: Boolean, minutes: Int) {
        context.dataStore.edit {
            it[KEY_NUDGE_ENABLED] = enabled
            it[KEY_NUDGE_MINUTES] = minutes
        }
    }

    override suspend fun setMindBackend(backend: MindBackend) {
        context.dataStore.edit { it[KEY_MIND] = backend.name }
    }

    override suspend fun setOpenRouter(key: String, model: String) {
        context.dataStore.edit {
            it[KEY_OR_KEY] = seal(key)
            it[KEY_OR_MODEL] = model
        }
    }

    override suspend fun setCustomEndpoint(baseUrl: String, model: String, key: String) {
        context.dataStore.edit {
            it[KEY_CUSTOM_URL] = baseUrl
            it[KEY_CUSTOM_MODEL] = model
            it[KEY_CUSTOM_KEY] = seal(key)
        }
    }

    override suspend fun setSttEngine(engine: SttEngine) {
        context.dataStore.edit { it[KEY_STT] = engine.name }
    }

    override suspend fun setModelUrls(parakeetUrl: String, llmUrl: String) {
        context.dataStore.edit {
            it[KEY_PARAKEET_URL] = parakeetUrl
            it[KEY_LLM_URL] = llmUrl
        }
    }

    // ————— Secret sealing —————

    /**
     * Resolves a stored secret to cleartext:
     *  - "enc:" blob → decrypt (a failed decrypt clears the unrecoverable
     *    blob rather than retry-logging forever; an unavailable keystore
     *    drops the value — it can never be read back)
     *  - plaintext → returned as-is and queued for sealed write-back.
     */
    private fun unseal(
        raw: String?,
        key: Preferences.Key<String>,
        pendingSeal: MutableList<Pair<Preferences.Key<String>, String>>,
    ): String {
        if (raw.isNullOrEmpty()) return ""
        val store = secretStore
        if (raw.startsWith(ENC_PREFIX)) {
            if (store == null) {
                Log.w(TAG, "sealed secret but keystore unavailable — value dropped")
                return ""
            }
            return runCatching { store.decrypt(raw.removePrefix(ENC_PREFIX)) }
                .onFailure { Log.w(TAG, "secret decrypt failed — clearing blob", it) }
                .getOrElse {
                    pendingSeal.add(Pair(key, ""))
                    ""
                }
        }
        if (store != null) {
            val sealed = runCatching { ENC_PREFIX + store.encrypt(raw) }
                .onFailure { Log.w(TAG, "secret seal failed — staying plaintext", it) }
                .getOrNull()
            if (sealed != null) pendingSeal.add(Pair(key, sealed))
        }
        return raw
    }

    /** The write-side mirror of [unseal] — empty stays empty, never "enc:". */
    private fun seal(value: String): String {
        val store = secretStore
        return if (value.isEmpty() || store == null) value
        else runCatching { ENC_PREFIX + store.encrypt(value) }
            .onFailure { Log.w(TAG, "secret seal failed — staying plaintext", it) }
            .getOrDefault(value)
    }

    /** One edit per emission batch; failures only delay the migration. */
    private fun queueSealWriteBack(
        pendingSeal: List<Pair<Preferences.Key<String>, String>>,
    ) {
        if (pendingSeal.isEmpty()) return
        migrationScope.launch {
            runCatching {
                context.dataStore.edit { m ->
                    pendingSeal.forEach { (k, v) -> m[k] = v }
                }
            }.onFailure { Log.w(TAG, "secret migration write failed", it) }
        }
    }

    private companion object {
        const val TAG = "PrefsRepository"
        /** Prefix marking a value sealed by [SecretStore] — never user-typed. */
        const val ENC_PREFIX = "enc:"
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
