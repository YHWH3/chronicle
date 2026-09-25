package com.chronicle.app.mind

import android.content.Context
import com.chronicle.app.data.ChroniclePrefs
import com.chronicle.app.data.MindBackend
import com.chronicle.app.data.PrefsRepository
import java.time.LocalDateTime
import kotlinx.coroutines.flow.first

/**
 * Resolves the configured [MindEngine] from preferences and runs extraction
 * with a hard fallback to [DeterministicMind] — a failed network or model
 * must never lose the user's words.
 */
class MindRepository(
    private val context: Context,
    private val prefsRepository: PrefsRepository,
    private val modelStore: ModelStore,
) {

    /** The engine the current prefs resolve to. */
    suspend fun engine(): MindEngine {
        val prefs = prefsRepository.prefs.first()
        return resolve(prefs)
    }

    fun resolve(prefs: ChroniclePrefs): MindEngine = when (prefs.mindBackend) {
        MindBackend.QUIET_PARSER -> DeterministicMind
        MindBackend.ON_DEVICE -> modelStore.localLlmFile()
            ?.let { LocalLlmMind(context, it.absolutePath) }
            ?: DeterministicMind
        MindBackend.OPENROUTER ->
            if (prefs.openRouterKey.isBlank()) DeterministicMind
            else ChatMind.openRouter(prefs.openRouterKey, prefs.openRouterModel)
        MindBackend.CUSTOM ->
            if (prefs.customBaseUrl.isBlank() || prefs.customModel.isBlank()) DeterministicMind
            else ChatMind(
                label = "Custom · ${prefs.customModel}",
                baseUrl = prefs.customBaseUrl,
                apiKey = prefs.customKey,
                model = prefs.customModel,
            )
    }

    /**
     * Runs the configured engine; on any failure falls back to the
     * deterministic parser and reports which engine actually answered.
     */
    suspend fun extract(text: String, now: LocalDateTime): Pair<MindResult, MindEngine> {
        val engine = engine()
        val result = runCatching { engine.extract(text, now) }
            .getOrElse { DeterministicMind.extract(text, now) }
        return result to engine
    }
}
