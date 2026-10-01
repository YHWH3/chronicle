package com.chronicle.app.mind

import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * An OpenAI-compatible chat-completions backend. Works for OpenRouter
 * (which hosts free-tier models), OpenAI itself, Groq, Together, and any
 * service exposing POST {base}/chat/completions.
 *
 * Timing budget: the caller sees a [USER_FACING_TIMEOUT_MS] cap (20 s).
 * The round-trip runs in a child coroutine so the cap fires even while a
 * socket read is parked; on timeout [BlockingCall.cancel] disconnects the
 * connection, which unblocks the reader and reclaims the IO thread.
 * Worst case is therefore ~20 s plus the few ms for disconnect() to bite —
 * not the old connect(15s)+read(60s) ≈ 75 s. If a network stack ever
 * ignores disconnect(), the socket timeouts remain the true outer bound.
 */
class ChatMind internal constructor(
    override val label: String,
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val referer: String? = null,
    // Test seams — production callers use the defaults.
    internal val timeoutMs: Long = USER_FACING_TIMEOUT_MS,
    internal val newCall: (RequestSpec) -> BlockingCall = { HttpCall(it) },
) : MindEngine {

    /** A fully-built request — URL, headers, JSON body. Pure data so tests can assert shape. */
    internal data class RequestSpec(
        val url: String,
        val headers: Map<String, String>,
        val body: String,
    )

    /** What came back over the wire. */
    internal data class CloudReply(val code: Int, val body: String)

    /**
     * One blocking round-trip. [cancel] must make [run] return promptly —
     * for HttpURLConnection that is disconnect(), which unblocks a stuck read.
     */
    internal interface BlockingCall {
        fun run(): CloudReply
        fun cancel()
    }

    /** The production [BlockingCall]: a plain HttpURLConnection POST. */
    internal class HttpCall(private val spec: RequestSpec) : BlockingCall {
        private var conn: HttpURLConnection? = null

        override fun run(): CloudReply {
            val c = (URL(spec.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                spec.headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            synchronized(this) { conn = c }
            try {
                c.outputStream.use { it.write(spec.body.toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                val raw = (if (code in 200..299) c.inputStream else c.errorStream)
                    ?.bufferedReader()?.readText().orEmpty()
                return CloudReply(code, raw)
            } finally {
                c.disconnect()
            }
        }

        override fun cancel() {
            synchronized(this) { conn }?.disconnect()
        }
    }

    override suspend fun extract(text: String, now: LocalDateTime): MindResult =
        withContext(Dispatchers.IO) {
            val call = newCall(requestSpec(text, now))
            val reply = coroutineScope {
                val flight = async { call.run() }
                try {
                    withTimeout(timeoutMs) { flight.await() }
                } catch (t: TimeoutCancellationException) {
                    call.cancel()
                    // If the caller itself was cancelled, this timeout must not
                    // mask it — ensureActive() rethrows CancellationException.
                    currentCoroutineContext().ensureActive()
                    error("$label timed out after ${timeoutMs / 1000}s")
                } catch (ce: CancellationException) {
                    // Caller cancelled — still disconnect so the orphaned
                    // socket read doesn't hold an IO thread until the 60s
                    // socket timeout.
                    call.cancel()
                    throw ce
                }
            }
            if (reply.code !in 200..299) {
                error("HTTP ${reply.code}: ${errorSnippet(reply.body)}")
            }
            val content = JSONObject(reply.body)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                .orEmpty()
            MindResultParser.parse(content, now)
                ?: error("Unparseable reply: ${errorSnippet(content)}")
        }

    /**
     * Builds the request without touching the network. The transcript is
     * capped at [MAX_TRANSCRIPT_CHARS] so a long entry can't produce a
     * multi-megabyte payload, and the Authorization header is only sent
     * when an API key is actually configured — never "Bearer ".
     */
    internal fun requestSpec(text: String, now: LocalDateTime): RequestSpec {
        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = "application/json"
        if (apiKey.isNotBlank()) headers["Authorization"] = "Bearer $apiKey"
        if (referer != null) {
            headers["HTTP-Referer"] = referer
            headers["X-Title"] = "Chronicle"
        }
        return RequestSpec(
            url = "${baseUrl.trimEnd('/')}/chat/completions",
            headers = headers,
            body = MindResultParser
                .requestBody(model, text.take(MAX_TRANSCRIPT_CHARS), now)
                .toString(),
        )
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
        const val USER_FACING_TIMEOUT_MS = 20_000L
        const val MAX_TRANSCRIPT_CHARS = 12_000
        const val MAX_ERROR_BODY_CHARS = 200

        /**
         * One line, short — error bodies can be enormous or carry secrets,
         * so they are flattened and capped before they reach a message/log.
         */
        internal fun errorSnippet(
            raw: String,
            max: Int = MAX_ERROR_BODY_CHARS,
        ): String = raw.replace(Regex("[\\r\\n]+"), " ").trim().take(max)

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
