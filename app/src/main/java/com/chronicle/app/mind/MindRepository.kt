package com.chronicle.app.mind

import com.chronicle.app.core.OperationResult
import com.chronicle.app.core.safeSuspend
import com.chronicle.app.core.sanitizeReason
import com.chronicle.app.data.ChroniclePrefs
import com.chronicle.app.data.MindBackend
import com.chronicle.app.data.PrefsRepository
import java.time.LocalDateTime
import kotlinx.coroutines.flow.first

/** What ViewModels need from the mind layer — resolution plus execution. */
interface MindSource {
    /** The engine the given prefs resolve to. */
    fun resolve(prefs: ChroniclePrefs): MindEngine

    /**
     * Runs the configured engine; on failure reports the fallback honestly in
     * the returned [MindExecutionResult]. CancellationException propagates.
     */
    suspend fun extract(text: String, now: LocalDateTime): MindExecutionResult
}

/**
 * Resolves the configured [MindEngine] from preferences and runs extraction
 * with a hard fallback to [DeterministicMind] — a failed network or model
 * must never lose the user's words.
 */
class MindRepository(
    private val prefsRepository: PrefsRepository,
    private val modelStore: ModelStore,
    private val llmEngineHolder: LlmEngineHolder<LocalLlmMind>,
) : MindSource {

    /** The engine the current prefs resolve to. */
    suspend fun engine(): MindEngine {
        val prefs = prefsRepository.prefs.first()
        return resolve(prefs)
    }

    override fun resolve(prefs: ChroniclePrefs): MindEngine {
        val engine = when (prefs.mindBackend) {
            MindBackend.QUIET_PARSER -> DeterministicMind
            // The app-scoped holder loads the model once and reuses it until
            // the file or backend changes — a ~2 GB engine is never rebuilt
            // per capture.
            MindBackend.ON_DEVICE -> modelStore.localLlmFile()
                ?.let { llmEngineHolder.acquire(it.absolutePath) }
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
        // Any non-local outcome means the backend moved or the model file went
        // away — drop the cached engine instead of letting it pin ~2 GB.
        if (engine !is LocalLlmMind) llmEngineHolder.release()
        return engine
    }

    /**
     * Runs the configured engine; on any failure falls back to the
     * deterministic parser and reports which engine actually answered.
     */
    override suspend fun extract(text: String, now: LocalDateTime): MindExecutionResult =
        extractWithFallback(engine(), text, now)

    companion object {
        /**
         * The fallback policy, separated from engine resolution so it can be
         * exercised in unit tests with a fake engine.
         *
         * Cancellation always propagates. When the configured engine fails,
         * [DeterministicMind] answers instead and the returned
         * [MindExecutionResult] records both labels plus a sanitized reason.
         * If the quiet parser itself throws, that failure propagates so the
         * caller can surface an extraction failure.
         */
        internal suspend fun extractWithFallback(
            engine: MindEngine,
            text: String,
            now: LocalDateTime,
        ): MindExecutionResult {
            val configured = engine.label
            return when (val first = safeSuspend { engine.extract(text, now) }) {
                is OperationResult.Ok -> MindExecutionResult(
                    result = first.value,
                    configuredLabel = configured,
                    actualLabel = configured,
                    fallbackReason = null,
                )
                is OperationResult.Failed -> MindExecutionResult(
                    result = DeterministicMind.extract(text, now),
                    configuredLabel = configured,
                    actualLabel = DeterministicMind.label,
                    fallbackReason = sanitizeReason(first.error),
                )
            }
        }
    }
}
