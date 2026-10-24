package com.chronicle.app.mind

import com.chronicle.app.BuildConfig
import com.chronicle.app.data.PrefsRepository
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class HfResponse(val code: Int, val errorCode: String? = null, val body: String = "")
internal fun interface HfHttp {
    suspend fun request(method: String, url: String, bearer: String, form: Map<String, String>): HfResponse
}
internal class HfNetwork : HfHttp {
    override suspend fun request(method: String, url: String, bearer: String, form: Map<String, String>): HfResponse =
        withContext(Dispatchers.IO) {
            val target = URL(url)
            require(target.protocol == "https" && target.host == "huggingface.co")
            val conn = target.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 20_000
                conn.readTimeout = 20_000
                if (bearer.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $bearer")
                if (form.isNotEmpty()) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    conn.outputStream.use { it.write(form.entries.joinToString("&") { (k, v) ->
                        encode(k) + "=" + encode(v)
                    }.toByteArray()) }
                }
                val code = conn.responseCode
                HfResponse(code, conn.getHeaderField("x-error-code"),
                    if (method == "HEAD") "" else
                        runCatching { conn.inputStream.bufferedReader().use { it.readText() } }.getOrDefault(""))
            } finally { conn.disconnect() }
        }
}
internal fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

enum class HfAccess { GRANTED, WAITING_FOR_LICENCE, TOKEN_REJECTED, FAILED }
/** Redirects are intentionally disabled here: a 302 from HF proves repo access. */
internal fun classifyHfAccess(response: HfResponse): HfAccess = when {
    response.code == 200 || response.code == 302 -> HfAccess.GRANTED
    response.code in listOf(401, 403) && response.errorCode == "GatedRepo" -> HfAccess.WAITING_FOR_LICENCE
    response.code == 401 -> HfAccess.TOKEN_REJECTED
    else -> HfAccess.FAILED
}
internal class HfAccessWatcher(private val http: HfHttp) {
    suspend fun check(model: SmartModel, token: String) =
        classifyHfAccess(http.request("HEAD", model.url, token, emptyMap()))
}

class HfAuthRepository internal constructor(
    private val prefs: PrefsRepository,
    private val http: HfHttp = HfNetwork(),
) {
    // Serialize challenge consumption, refresh and sign-out. A late refresh
    // must not restore a session the user has just cleared.
    private val session = Mutex()
    private val completedOAuth = MutableStateFlow(0L)
    val oauthCompletions = completedOAuth.asStateFlow()

    suspend fun authorizationUrl(): String = session.withLock {
        require(BuildConfig.HF_OAUTH_CLIENT_ID.isNotBlank())
        val random = SecureRandom()
        fun nonce(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        val state = nonce()
        val verifier = nonce()
        prefs.saveHfChallenge(state, verifier)
        val challenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        "https://huggingface.co/oauth/authorize?" + mapOf(
            "client_id" to BuildConfig.HF_OAUTH_CLIENT_ID,
            "response_type" to "code", "redirect_uri" to REDIRECT,
            "scope" to "openid profile gated-repos", "state" to state,
            "code_challenge" to challenge, "code_challenge_method" to "S256",
        ).entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) }
    }

    suspend fun finishOAuth(code: String, state: String): Boolean = session.withLock {
        val stored = prefs.prefs.first()
        if (code.isBlank() || state.isBlank() || stored.hfOauthState.isBlank() || stored.hfPkceVerifier.isBlank() ||
            !MessageDigest.isEqual(state.toByteArray(), stored.hfOauthState.toByteArray())) return@withLock false
        // Consume state before exchange; a duplicate callback cannot replay the code.
        prefs.saveHfChallenge("", "")
        val success = exchange(mapOf("grant_type" to "authorization_code", "code" to code,
            "code_verifier" to stored.hfPkceVerifier, "redirect_uri" to REDIRECT,
            "client_id" to BuildConfig.HF_OAUTH_CLIENT_ID))
        if (success) completedOAuth.update { it + 1 }
        success
    }

    suspend fun validateToken(token: String): Boolean = session.withLock {
        val clean = token.trim()
        if (clean.isBlank()) return@withLock false
        val response = http.request("GET", "https://huggingface.co/api/whoami-v2", clean, emptyMap())
        if (response.code != 200) return@withLock false
        prefs.saveHfTokens(clean, "", 0)
        true
    }

    suspend fun accessToken(): String = session.withLock {
        val stored = prefs.prefs.first()
        if (stored.hfExpiresAt > 0 && stored.hfExpiresAt < System.currentTimeMillis() + 30_000) {
            if (stored.hfRefreshToken.isBlank() || !exchange(mapOf(
                "grant_type" to "refresh_token", "refresh_token" to stored.hfRefreshToken,
                "client_id" to BuildConfig.HF_OAUTH_CLIENT_ID,
            ))) return@withLock ""
            return@withLock prefs.prefs.first().hfAccessToken
        }
        stored.hfAccessToken
    }

    suspend fun checkAccess(model: SmartModel): HfAccess {
        val token = accessToken()
        if (token.isBlank()) return HfAccess.TOKEN_REJECTED
        return HfAccessWatcher(http).check(model, token)
    }
    suspend fun signOut() = session.withLock { prefs.clearHfSession() }

    private suspend fun exchange(form: Map<String, String>): Boolean {
        val reply = http.request("POST", "https://huggingface.co/oauth/token", "", form)
        if (reply.code != 200) return false
        val obj = JSONObject(reply.body)
        val access = obj.optString("access_token")
        if (access.isBlank()) return false
        val old = prefs.prefs.first()
        prefs.saveHfTokens(access, obj.optString("refresh_token").ifBlank { old.hfRefreshToken },
            System.currentTimeMillis() + obj.optLong("expires_in", 3600) * 1000)
        return true
    }

    companion object { const val REDIRECT = "chronicle://oauth/huggingface" }
}
