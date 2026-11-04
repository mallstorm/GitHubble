package app.githubble.mobile.network

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Google account OAuth used by the isolated Subscription Proxy contour.
 *
 * The flow mirrors CLIProxyAPI's Antigravity / Cloud Code account flow:
 * Google OAuth -> user profile -> Cloud Code project discovery/onboarding ->
 * account-scoped model catalog. It never reads Gemini API keys.
 */
class SubscriptionProxyGeminiClient {
    data class CatalogResult(val models: List<String>, val apiBaseUrl: String)

    data class LoginSession(
        val authUrl: String,
        internal val state: String,
        internal val server: ServerSocket,
        internal val expiresAtMs: Long
    )

    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .dns(NetworkDnsPolicy.dns())
        .addInterceptor(NetworkDnsPolicy.feedbackInterceptor())
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun beginLogin(): LoginSession {
        val state = randomHex(16)
        val server = ServerSocket(CALLBACK_PORT, 4, InetAddress.getByName("127.0.0.1")).apply {
            soTimeout = 1000
        }
        val authUrl = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("accounts.google.com")
            .addPathSegments("o/oauth2/v2/auth")
            .addQueryParameter("access_type", "offline")
            .addQueryParameter("client_id", CLIENT_ID)
            .addQueryParameter("prompt", "consent")
            .addQueryParameter("include_granted_scopes", "true")
            .addQueryParameter("redirect_uri", REDIRECT_URI)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("scope", SCOPES.joinToString(" "))
            .addQueryParameter("state", state)
            .build()
            .toString()
        return LoginSession(
            authUrl = authUrl,
            state = state,
            server = server,
            expiresAtMs = System.currentTimeMillis() + LOGIN_TTL_MS
        )
    }

    suspend fun awaitCredentials(
        session: LoginSession
    ): SubscriptionProxyStore.GeminiCredentials = withContext(Dispatchers.IO) {
        try {
            while (currentCoroutineContext().isActive &&
                System.currentTimeMillis() < session.expiresAtMs
            ) {
                val socket = try {
                    session.server.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                }
                socket.use {
                    val callback = readCallback(it)
                    if (callback == null) {
                        writeHtml(it, 404, "Страница не найдена")
                        return@use
                    }
                    val error = callback.getQueryParameter("error")
                    if (!error.isNullOrBlank()) {
                        writeHtml(it, 200, "Google OAuth отклонён. Можно закрыть вкладку.")
                        throw ApiFailure("Google OAuth: $error")
                    }
                    val code = callback.getQueryParameter("code").orEmpty()
                    val returnedState = callback.getQueryParameter("state").orEmpty()
                    if (code.isBlank() || returnedState != session.state) {
                        writeHtml(it, 200, "Некорректный Google OAuth ответ.")
                        throw ApiFailure("Google OAuth вернул неверный state или пустой code")
                    }
                    val token = exchangeCode(code)
                    val email = fetchEmail(token.accessToken)
                    val projectId = discoverProject(token.accessToken)
                    val credentials = SubscriptionProxyStore.GeminiCredentials(
                        accessToken = token.accessToken,
                        refreshToken = token.refreshToken,
                        tokenType = token.tokenType,
                        expiresAtMs = System.currentTimeMillis() + token.expiresIn * 1000L,
                        email = email,
                        projectId = projectId
                    )
                    writeHtml(it, 200, "GitHubble подключён к Google Gemini")
                    return@withContext credentials
                }
            }
            throw ApiFailure("Истекло время ожидания Google OAuth")
        } finally {
            runCatching { session.server.close() }
        }
    }

    fun cancel(session: LoginSession?) {
        runCatching { session?.server?.close() }
    }

    suspend fun refresh(
        credentials: SubscriptionProxyStore.GeminiCredentials
    ): SubscriptionProxyStore.GeminiCredentials = withContext(Dispatchers.IO) {
        require(credentials.refreshToken.isNotBlank()) { "Google refresh token отсутствует" }
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("client_secret", CLIENT_SECRET)
            .add("refresh_token", credentials.refreshToken)
            .add("grant_type", "refresh_token")
            .build()
        val json = executeJson(
            Request.Builder()
                .url(TOKEN_ENDPOINT)
                .header("Accept", "application/json")
                .post(body)
                .build(),
            "Google token refresh"
        )
        val access = json.optString("access_token")
        if (access.isBlank()) throw ApiFailure("Google не вернул access token")
        credentials.copy(
            accessToken = access,
            tokenType = json.optString("token_type", credentials.tokenType.ifBlank { "Bearer" }),
            expiresAtMs = System.currentTimeMillis() +
                json.optLong("expires_in", 3600L).coerceAtLeast(60L) * 1000L
        )
    }

    suspend fun modelCatalog(
        credentials: SubscriptionProxyStore.GeminiCredentials
    ): List<String> = modelCatalogWithEndpoint(credentials).models

    suspend fun modelCatalogWithEndpoint(
        credentials: SubscriptionProxyStore.GeminiCredentials
    ): CatalogResult = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("project", credentials.projectId)
            .toString()
            .toRequestBody(jsonType)
        val bases = endpointCandidates(credentials.apiBaseUrl)
        val musicModels = SubscriptionProxyGeminiSupport.VERTEX_MUSIC_MODELS
        var lastError: Throwable? = null
        for (base in bases) {
            val result = runCatching {
                executeJson(
                    Request.Builder()
                        .url("$base/v1internal:fetchAvailableModels")
                        .apply { cloudCodeHeaders(this, credentials.accessToken) }
                        .post(body)
                        .build(),
                    "Google Cloud Code models"
                )
            }.onFailure { lastError = it }.getOrNull() ?: continue
            val objectModels = result.optJSONObject("models") ?: continue
            val merged = serverModelIds(objectModels)
            if (merged.isNotEmpty()) return@withContext CatalogResult(merged, base)
        }
        throw (lastError ?: ApiFailure("Google Cloud Code не вернул доступные модели"))
    }

    companion object {
        internal fun serverModelIds(models:JSONObject):List<String> = models.keys().asSequence().map{it.trim()}.filter{it.isNotBlank()&&it.length<=160}.distinct().sorted().toList()

        const val CLIENT_ID =
            "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com"
        const val CLIENT_SECRET = "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"
        const val CALLBACK_PORT = 51121
        const val REDIRECT_URI = "http://localhost:51121/oauth-callback"
        const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        const val USER_INFO_ENDPOINT = "https://www.googleapis.com/oauth2/v2/userinfo?alt=json"
        const val API_ENDPOINT = "https://cloudcode-pa.googleapis.com"
        const val DAILY_API = "https://daily-cloudcode-pa.googleapis.com"
        const val VERTEX_AI_ENDPOINT = "https://aiplatform.googleapis.com"
        const val USER_AGENT = "antigravity/hub/2.9.1 darwin/arm64"
        const val LOGIN_TTL_MS = 5 * 60 * 1000L

        val SCOPES = listOf(
            "https://www.googleapis.com/auth/cloud-platform",
            "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/userinfo.profile",
            "https://www.googleapis.com/auth/cclog",
            "https://www.googleapis.com/auth/experimentsandconfigs"
        )

        val DEFAULT_MODELS = listOf(
            "gemini-3.6-flash-high",
            "gemini-3.1-pro-high",
            "gemini-3.1-pro-low",
            "gemini-3.1-pro-preview",
            "gemini-3-flash-preview",
            "gemini-2.5-pro",
            "gemini-2.5-flash"
        )

        private val INTERNAL_MODELS = setOf(
            "chat_20706",
            "chat_23310",
            "tab_flash_lite_preview",
            "tab_jump_flash_lite_preview",
            "gemini-2.5-flash-thinking"
        )

        internal fun endpointCandidates(saved: String): List<String> {
            val preferred = saved.takeIf { it in setOf(DAILY_API, API_ENDPOINT) }
            return listOfNotNull(preferred, DAILY_API, API_ENDPOINT).distinct()
        }

        fun cloudCodeHeaders(builder: Request.Builder, token: String): Request.Builder = builder
            .header("Authorization", "Bearer $token")
            .header("Accept", "*/*")
            .header("Content-Type", "application/json")
            .header("User-Agent", USER_AGENT)

        private fun randomHex(bytes: Int): String {
            val data = ByteArray(bytes)
            SecureRandom().nextBytes(data)
            return buildString(bytes * 2) {
                data.forEach { append("%02x".format(it.toInt() and 0xff)) }
            }
        }
    }

    private data class TokenResponse(
        val accessToken: String,
        val refreshToken: String,
        val tokenType: String,
        val expiresIn: Long
    )

    private fun exchangeCode(code: String): TokenResponse {
        val body = FormBody.Builder()
            .add("code", code)
            .add("client_id", CLIENT_ID)
            .add("client_secret", CLIENT_SECRET)
            .add("redirect_uri", REDIRECT_URI)
            .add("grant_type", "authorization_code")
            .build()
        val json = executeJson(
            Request.Builder().url(TOKEN_ENDPOINT)
                .header("Accept", "application/json")
                .post(body)
                .build(),
            "Google OAuth token exchange"
        )
        val access = json.optString("access_token")
        if (access.isBlank()) throw ApiFailure("Google OAuth не вернул access token")
        return TokenResponse(
            accessToken = access,
            refreshToken = json.optString("refresh_token"),
            tokenType = json.optString("token_type", "Bearer"),
            expiresIn = json.optLong("expires_in", 3600L).coerceAtLeast(60L)
        )
    }

    private fun fetchEmail(accessToken: String): String {
        val json = executeJson(
            Request.Builder()
                .url(USER_INFO_ENDPOINT)
                .header("Authorization", "Bearer $accessToken")
                .header("User-Agent", USER_AGENT)
                .get()
                .build(),
            "Google user info"
        )
        return json.optString("email")
    }

    private suspend fun discoverProject(accessToken: String): String {
        val loadBody = JSONObject()
            .put("metadata", JSONObject().put("ideType", "ANTIGRAVITY"))
            .toString()
            .toRequestBody(jsonType)
        val load = executeJson(
            Request.Builder()
                .url("$API_ENDPOINT/v1internal:loadCodeAssist")
                .apply { cloudCodeHeaders(this, accessToken) }
                .post(loadBody)
                .build(),
            "Google Cloud Code project"
        )
        projectId(load)?.takeIf { it.isNotBlank() }?.let { return it }

        var tier = "free-tier"
        val tiers = load.optJSONArray("allowedTiers")
        if (tiers != null) {
            for (i in 0 until tiers.length()) {
                val item = tiers.optJSONObject(i) ?: continue
                if (item.optBoolean("isDefault")) {
                    tier = item.optString("id").ifBlank { tier }
                    break
                }
            }
        }
        load.optJSONObject("currentTier")?.optString("id")
            ?.takeIf { it.isNotBlank() }
            ?.let { tier = it }

        val metadata = JSONObject()
            .put("ide_type", "ANTIGRAVITY")
            .put("ide_version", "2.9.1")
            .put("ide_name", "antigravity")
        val onboardBody = JSONObject()
            .put("tier_id", tier)
            .put("metadata", metadata)
            .toString()
            .toRequestBody(jsonType)

        repeat(5) { attempt ->
            val result = runCatching {
                executeJson(
                    Request.Builder()
                        .url("$DAILY_API/v1internal:onboardUser")
                        .apply { cloudCodeHeaders(this, accessToken) }
                        .header("X-Goog-Api-Client", "gl-node/22.21.1")
                        .post(onboardBody)
                        .build(),
                    "Google Cloud Code onboarding"
                )
            }.getOrElse {
                if (attempt == 4) throw it
                delay(2000)
                return@repeat
            }
            if (result.optBoolean("done")) {
                val response = result.optJSONObject("response") ?: result
                val project = projectId(response)
                if (project.isNotBlank()) return project
            }
            if (attempt < 4) delay(2000)
        }
        throw ApiFailure("Legacy Cloud Code не вернул project ID. Персональный Gemini Code Assist больше не обслуживается; используйте официальный Antigravity SDK/CLI или прямое Gemini API-подключение.")
    }

    private fun projectId(json: JSONObject): String {
        for (key in listOf("cloudaicompanionProject", "projectId", "project")) {
            when (val raw = json.opt(key)) {
                is String -> raw.trim().takeIf { it.isNotBlank() }?.let { return it }
                is JSONObject -> raw.optString("id").trim().takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return ""
    }

    private fun executeJson(request: Request, label: String): JSONObject {
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    val json = JSONObject(raw)
                    val error = json.opt("error")
                    when (error) {
                        is JSONObject -> error.optString("message")
                        is String -> error
                        else -> json.optString("message")
                    }
                }.getOrDefault("").ifBlank { raw.take(260) }
                throw ApiFailure("$label: ${detail.ifBlank { "HTTP ${response.code}" }}", response.code)
            }
            return runCatching { JSONObject(raw) }.getOrElse {
                throw ApiFailure("$label: некорректный JSON")
            }
        }
    }

    private fun readCallback(socket: java.net.Socket): Uri? {
        socket.soTimeout = 3000
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2 || parts[0] != "GET") return null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        return Uri.parse("http://localhost${parts[1]}").takeIf { it.path == "/oauth-callback" }
    }

    private fun writeHtml(socket: java.net.Socket, status: Int, message: String) {
    val success = status == 200 && message.contains("подключ", ignoreCase = true)
    val safeMessage = htmlEscape(message)
    val headline = if (success) "GitHubble подключён" else "Не удалось завершить вход"
    val detail = if (success) {
        "Google Gemini готов к работе через Subscription Proxy."
    } else {
        safeMessage
    }
    val statusMark = if (success) "✓" else "!"
    val statusClass = if (success) "status success" else "status error"
    val hint = if (success) {
        "Можно закрыть эту вкладку и вернуться в GitHubble."
    } else {
        "Вернитесь в GitHubble и повторите подключение."
    }
    val body = """<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#f7f7fb">
<title>$headline</title>
<style>
*{box-sizing:border-box}html,body{margin:0;min-height:100%;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Arial,sans-serif;color:#202124}body{min-height:100vh;display:grid;place-items:center;padding:24px;background:radial-gradient(circle at 15% 10%,rgba(66,133,244,.14),transparent 32%),radial-gradient(circle at 85% 18%,rgba(168,85,247,.13),transparent 34%),radial-gradient(circle at 50% 100%,rgba(52,168,83,.10),transparent 35%),#f7f7fb}.card{width:min(100%,480px);padding:30px 28px 26px;border:1px solid rgba(60,64,67,.09);border-radius:28px;background:rgba(255,255,255,.92);box-shadow:0 18px 60px rgba(32,33,36,.12);backdrop-filter:blur(16px)}.brand{display:flex;align-items:center;gap:13px;margin-bottom:28px}.spark{width:50px;height:50px;display:grid;place-items:center;border-radius:16px;background:linear-gradient(145deg,rgba(66,133,244,.12),rgba(168,85,247,.12))}.brandText strong{display:block;font-size:18px;letter-spacing:-.2px}.brandText span{display:block;margin-top:2px;font-size:13px;color:#747775}.status{width:58px;height:58px;display:grid;place-items:center;margin-bottom:20px;border-radius:50%;font-size:28px;font-weight:700}.status.success{color:#137333;background:#e6f4ea}.status.error{color:#b3261e;background:#fce8e6}h1{margin:0;font-size:30px;line-height:1.12;letter-spacing:-.7px}p{margin:12px 0 0;font-size:16px;line-height:1.55;color:#5f6368}.hint{margin-top:24px;padding:14px 16px;border-radius:16px;background:#f5f7fa;color:#5f6368;font-size:14px;line-height:1.45}.footer{display:flex;align-items:center;gap:8px;margin-top:22px;color:#9aa0a6;font-size:12px}.dot{width:6px;height:6px;border-radius:50%;background:#34a853}@media(max-width:520px){body{place-items:start center;padding:20px 16px}.card{margin-top:7vh;padding:26px 22px 22px;border-radius:24px}h1{font-size:27px}}
</style>
</head>
<body>
<main class="card">
  <div class="brand">
    <div class="spark" aria-hidden="true">
      <svg width="32" height="32" viewBox="0 0 32 32" fill="none" xmlns="http://www.w3.org/2000/svg">
        <defs><linearGradient id="g" x1="4" y1="28" x2="28" y2="4" gradientUnits="userSpaceOnUse"><stop stop-color="#34A853"/><stop offset=".35" stop-color="#4285F4"/><stop offset=".68" stop-color="#9B72CB"/><stop offset="1" stop-color="#D96570"/></linearGradient></defs>
        <path d="M16 2.8c1.1 7.2 6 12.1 13.2 13.2C22 17.1 17.1 22 16 29.2 14.9 22 10 17.1 2.8 16 10 14.9 14.9 10 16 2.8Z" fill="url(#g)"/>
      </svg>
    </div>
    <div class="brandText"><strong>GitHubble</strong><span>Google Gemini</span></div>
  </div>
  <div class="$statusClass">$statusMark</div>
  <h1>$headline</h1>
  <p>$detail</p>
  <div class="hint">$hint</div>
  <div class="footer"><span class="dot"></span><span>Subscription Proxy · защищённый OAuth-вход</span></div>
</main>
</body>
</html>"""
    val bytes = body.toByteArray(Charsets.UTF_8)
    val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
    writer.write("HTTP/1.1 $status ${if (status == 200) "OK" else "Not Found"}\r\n")
    writer.write("Content-Type: text/html; charset=utf-8\r\n")
    writer.write("Content-Length: ${bytes.size}\r\n")
    writer.write("Cache-Control: no-store\r\n")
    writer.write("Connection: close\r\n\r\n")
    writer.write(body)
    writer.flush()
}

private fun htmlEscape(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")
}


