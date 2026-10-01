package com.chronicle.app.mind

import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * An OpenAI-compatible chat-completions backend. Works for OpenRouter
 * (which hosts free-tier models), OpenAI itself, Groq, Together, and any
 * service exposing POST {base}/chat/completions.
 */
class ChatMind(
    override val label: String,
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val referer: String? = null,
) : MindEngine {

    override suspend fun extract(text: String, now: LocalDateTime): MindResult =
        withContext(Dispatchers.IO) {
            val url = URL("${baseUrl.trimEnd('/')}/chat/completions")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 60_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                referer?.let {
                    setRequestProperty("HTTP-Referer", it)
                    setRequestProperty("X-Title", "Chronicle")
                }
            }
            try {
                val body = MindResultParser.requestBody(model, text, now)
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText().orEmpty()
                if (code !in 200..299) {
                    error("HTTP $code: ${raw.take(300)}")
                }
                val reply = JSONObject(raw)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    .orEmpty()
                MindResultParser.parse(reply, now)
                    ?: error("Unparseable reply: ${reply.take(200)}")
            } finally {
                conn.disconnect()
            }
        }

    companion object {
        fun openRouter(apiKey: String, model: String) = ChatMind(
            label = "OpenRouter · $model",
            baseUrl = "https://openrouter.ai/api/v1",
            apiKey = apiKey,
            model = model,
            referer = "https://chronicle.app",
        )
    }
}

/** Free-tier models offered on the OpenRouter picker — all end in ":free". */
val OPENROUTER_FREE_MODELS = listOf(
    "meta-llama/llama-3.2-3b-instruct:free",
    "meta-llama/llama-3.3-70b-instruct:free",
    "google/gemma-3-27b-it:free",
    "google/gemini-2.0-flash-exp:free",
    "deepseek/deepseek-r1:free",
    "qwen/qwen3-4b:free",
    "mistralai/mistral-small-3.1-24b-instruct:free",
)
