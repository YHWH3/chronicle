package com.chronicle.app.mind

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File
import java.time.LocalDateTime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device mind: a MediaPipe-compatible bundle (Qwen .task by default).
 * running through LlmInference. The model never leaves the phone.
 */
class LocalLlmMind(
    private val context: Context,
    private val modelPath: String,
) : MindEngine, ReleasableEngine {

    private val model = SmartModels.all.firstOrNull { it.file == File(modelPath).name }
    override val label = model?.displayName ?: "Custom model"
    private val generation = Mutex()

    @Volatile
    private var llm: LlmInference? = null
    private var closed = false
    private var users = 0

    // All callers hold generation and a lease. close() defers native teardown
    // until an in-flight load/generation has unwound, including cancellation.
    private fun engine(): LlmInference = llm ?: LlmInference.createFromOptions(
            context,
            LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                // Includes input AND output. The extraction examples plus a
                // paragraph left too little output space in the old 1024 cap.
                .setMaxTokens(2048)
                .build(),
        ).also { llm = it }
    private suspend fun <T> withNative(block: suspend () -> T): T = generation.withLock {
        synchronized(this) {
            check(!closed) { "The selected model changed" }
            users++
        }
        try { block() } finally {
            synchronized(this) {
                users--
                if (closed && users == 0) closeNative()
            }
        }
    }

    override suspend fun prepare() {
        withContext(Dispatchers.Default) { withNative { engine() } }
    }

    /** The spike sees the raw reply; parsing never hides a runtime/JSON failure. */
    suspend fun diagnose(
        text: String,
        now: LocalDateTime,
        format: PromptFormat = model?.promptFormat ?: PromptFormat.PLAIN,
        systemPrompt: String = MIND_PROMPT,
    ): SmartReply = withContext(Dispatchers.Default) {
        withNative {
            val start = System.nanoTime()
            val native = engine()
            val loaded = System.nanoTime()
            val reply = LlmInferenceSession.createFromOptions(
                native,
                LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(1).setTemperature(0f).setRandomSeed(0).build(),
            ).use { session ->
                session.addQueryChunk(format.render(text, now, systemPrompt))
                kotlinx.coroutines.suspendCancellableCoroutine<String> { continuation ->
                    val future = session.generateResponseAsync()
                    future.addListener({
                        try {
                            if (continuation.isActive) continuation.resumeWith(Result.success(future.get()))
                        } catch (t: Throwable) {
                            if (continuation.isActive) continuation.resumeWith(Result.failure(t.cause ?: t))
                        }
                    }, java.util.concurrent.Executor { it.run() })
                    continuation.invokeOnCancellation { session.cancelGenerateResponseAsync() }
                }
            }
            SmartReply(
                loadMs = (loaded - start) / 1_000_000,
                totalMs = (System.nanoTime() - start) / 1_000_000,
                raw = reply,
                parsed = MindResultParser.parse(reply, now),
                validJson = runCatching {
                    val obj = org.json.JSONObject(reply.trim())
                    obj.getJSONArray("tasks")
                    !reply.trim().startsWith("```") && reply.trim().endsWith("}")
                }.getOrDefault(false),
            )
        }
    }

    override suspend fun extract(text: String, now: LocalDateTime): MindResult =
        diagnose(text, now).parsed ?: error("The model reply could not be read")

    override suspend fun cleanup(text: String, now: LocalDateTime): String =
        NoteCleanupParser.parse(diagnose(text, now, systemPrompt = NOTE_CLEANUP_PROMPT).raw)

    /** Idempotent — safe from the holder's release path and after a crash. */
    @Synchronized
    override fun close() {
        closed = true
        if (users == 0) closeNative()
    }
    private fun closeNative() {
        runCatching { llm?.close() }
        llm = null
    }
}

data class SmartReply(
    val loadMs: Long,
    val totalMs: Long,
    val raw: String,
    val parsed: MindResult?,
    val validJson: Boolean,
)
