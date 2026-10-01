package com.chronicle.app.mind

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File
import java.time.LocalDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device mind: a MediaPipe-compatible .task bundle (e.g. Llama 3.2 3B)
 * running through LlmInference. The model never leaves the phone.
 */
class LocalLlmMind(
    private val context: Context,
    private val modelPath: String,
) : MindEngine {

    override val label = "On-device · ${File(modelPath).nameWithoutExtension}"

    @Volatile
    private var llm: LlmInference? = null

    private fun engine(): LlmInference = llm ?: synchronized(this) {
        llm ?: LlmInference.createFromOptions(
            context,
            LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(1024)
                .build(),
        ).also { llm = it }
    }

    override suspend fun extract(text: String, now: LocalDateTime): MindResult =
        withContext(Dispatchers.Default) {
            val prompt = "$MIND_PROMPT\n\nCurrent time: $now\nCapture:\n$text"
            val reply = try {
                engine().generateResponse(prompt)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // Recover from a stale/crashed engine once before giving up.
                runCatching { llm?.close() }
                llm = null
                engine().generateResponse(prompt)
            }
            MindResultParser.parse(reply, now)
                ?: error("Unparseable reply: ${reply.take(200)}")
        }

    fun close() {
        runCatching { llm?.close() }
        llm = null
    }
}
