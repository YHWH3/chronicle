package com.chronicle.app.mind

import com.chronicle.app.core.OperationResult
import com.chronicle.app.core.safeSuspend
import com.chronicle.app.core.sanitizeReason
import com.chronicle.app.data.ChroniclePrefs
import com.chronicle.app.data.MindBackend
import com.chronicle.app.data.PrefsRepository
import java.time.LocalDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import com.chronicle.app.domain.TaskExtractor
import com.chronicle.app.domain.ExtractedTask

/** What ViewModels need from the mind layer — resolution plus execution. */
interface MindSource {
    /** The engine the given prefs resolve to. */
    fun resolve(prefs: ChroniclePrefs): MindEngine

    /**
     * Runs the configured engine; on failure reports the fallback honestly in
     * the returned [MindExecutionResult]. CancellationException propagates.
     */
    suspend fun extract(text: String, now: LocalDateTime): MindExecutionResult

    suspend fun cleanup(text: String, now: LocalDateTime): NoteCleanupResult =
        NoteCleanupResult(NoteText.format(text), DeterministicMind.label)
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
            MindBackend.AUTO, MindBackend.ON_DEVICE -> modelStore.localLlmFile(
                if (prefs.selectedSmartModelId == "custom") SmartModels.CUSTOM_PACK
                else SmartModels.find(prefs.selectedSmartModelId)?.packId ?: SmartModels.all.first().packId,
            )
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
        extractNoteWithFallback(engine(), text, now)

    override suspend fun cleanup(text: String, now: LocalDateTime): NoteCleanupResult =
        cleanupWithFallback(engine(), text, now)

    companion object {
        internal suspend fun extractNoteWithFallback(
            engine: MindEngine, text: String, now: LocalDateTime,
        ): MindExecutionResult {
            val windows = NoteText.windows(TaskExtractor.cleanText(text))
            val paragraph = windows.size > 1 || Regex("""[.!?;]\s+|\n""").containsMatchIn(TaskExtractor.cleanText(text))
            if (!paragraph) return extractWithFallback(engine, text, now)
            val replies = windows.map { extractWithFallback(engine, it, now) }
            val tasks = replies.flatMap { it.result.tasks }.toMutableList()
            val baseline = TaskExtractor.extract(text, now)
            // Across a long note, retain clear rules intents even when a valid
            // but incomplete model reply omits them. Single-capture merge stays unchanged.
            var rulesAdded = false
            for (task in baseline) {
                if (tasks.none { taskKey(it.title) == taskKey(task.title) }) {
                    tasks += MindTask(task.title, task.remindAt, task.suggested)
                    rulesAdded = true
                }
            }
            val deduplicated = linkedMapOf<String, MindTask>()
            for (task in tasks) {
                val key = taskKey(task.title)
                val candidate = task.copy(remindAt = task.remindAt ?: baseline.firstOrNull {
                    taskKey(it.title) == key
                }?.remindAt)
                val previous = deduplicated[key]
                if (previous == null || previous.remindAt == null && candidate.remindAt != null) {
                    deduplicated[key] = candidate
                }
            }
            val failed = replies.count { it.fallbackReason != null }
            return MindExecutionResult(
                MindResult(deduplicated.values.toList()), engine.label,
                when (failed) {
                    0 -> if (rulesAdded && engine !== DeterministicMind) "${engine.label} + ${DeterministicMind.label}" else engine.label
                    replies.size -> DeterministicMind.label
                    else -> "${engine.label} + ${DeterministicMind.label}"
                },
                if (failed > 0) "$failed sections used Basic after the model could not finish" else null,
            )
        }

        internal suspend fun cleanupWithFallback(
            engine: MindEngine, text: String, now: LocalDateTime,
        ): NoteCleanupResult {
            val windows = NoteText.windows(text)
            if (engine === DeterministicMind) return NoteCleanupResult(NoteText.format(text), engine.label)
            // All-or-nothing: one failed section gives a formatting-only preview
            // of the entire original, never a half-rewritten or truncated note.
            val outcome = try {
                safeSuspend {
                    engine.prepare()
                    windows.map { section ->
                        val cleaned = if (engine is ChatMind) engine.cleanup(section, now)
                        else withTimeout(10_000) { engine.cleanup(section, now) }
                        require(cleaned.isNotBlank()) { "The model returned an empty note" }
                        cleaned
                    }.joinToString("\n\n")
                }
            } catch (_: TimeoutCancellationException) {
                OperationResult.Failed(IllegalStateException("Note cleanup took too long"))
            }
            return when (outcome) {
                is OperationResult.Ok -> NoteCleanupResult(outcome.value, engine.label)
                is OperationResult.Failed -> NoteCleanupResult(NoteText.format(text), DeterministicMind.label,
                    sanitizeReason(outcome.error))
            }
        }

        internal fun taskKey(title: String) = title.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

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
            val clean = TaskExtractor.cleanText(text)
            return coroutineScope {
                val rules = async { TaskExtractor.extract(clean, now) }
                val first = if (engine === DeterministicMind) {
                    OperationResult.Ok(engine.extract(clean, now))
                } else {
                    try {
                        safeSuspend {
                            engine.prepare()
                            if (engine is ChatMind) engine.extract(clean, now)
                            else withTimeout(10_000) { engine.extract(clean, now) }
                        }
                    } catch (_: TimeoutCancellationException) {
                        OperationResult.Failed(IllegalStateException("Smart to-dos took too long"))
                    }
                }
                val baseline = rules.await()
                when (first) {
                    is OperationResult.Ok -> MindExecutionResult(
                        result = if (engine === DeterministicMind) first.value
                            else mergeTasks(first.value, baseline, now),
                        configuredLabel = configured, actualLabel = configured, fallbackReason = null,
                    )
                    is OperationResult.Failed -> MindExecutionResult(
                        result = MindResult(baseline.map { MindTask(it.title, it.remindAt, it.suggested) }),
                        configuredLabel = configured, actualLabel = DeterministicMind.label,
                        fallbackReason = sanitizeReason(first.error),
                    )
                }
            }
        }

        internal fun mergeTasks(model: MindResult, rules: List<ExtractedTask>, now: LocalDateTime): MindResult {
            fun key(title: String) = title.lowercase().replace(Regex("\\s+"), " ").trim()
            val merged = model.tasks.map { task ->
                val valid = task.remindAt?.takeIf { it > now && it < now.plusDays(365) }
                task.copy(remindAt = valid ?: rules.firstOrNull { key(it.title) == key(task.title) }?.remindAt
                    ?: TaskExtractor.resolveTimeText(task.title, now))
            }.toMutableList()
            for (task in rules.filter { it.explicitReminder }) {
                if (merged.none { key(it.title) == key(task.title) }) {
                    merged += MindTask(task.title, task.remindAt, task.suggested)
                }
            }
            return model.copy(tasks = merged.distinctBy { key(it.title) })
        }
    }
}
