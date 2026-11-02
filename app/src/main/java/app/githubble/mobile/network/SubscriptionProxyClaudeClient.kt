package app.githubble.mobile.network

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class SubscriptionProxyClaudeClient {
    data class LoginSession(
        val authUrl: String,
        internal val verifier: String,
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
        val verifier = randomBase64Url(48).take(64)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        val state = randomHex(16)
        val server = ServerSocket(CALLBACK_PORT, 4, InetAddress.getByName("127.0.0.1")).apply {
            soTimeout = 1000
        }
        val url = AUTH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("code", "true")
            .addQueryParameter("client_id", CLIENT_ID)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", REDIRECT_URI)
            .addQueryParameter("scope", SCOPE)
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", state)
            .build()
            .toString()
        return LoginSession(url, verifier, state, server, System.currentTimeMillis() + LOGIN_TTL_MS)
    }

    suspend fun awaitCredentials(session: LoginSession): SubscriptionProxyStore.ClaudeCredentials =
        withContext(Dispatchers.IO) {
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
                            writeHtml(it, 200, "Вход Anthropic отклонён. Можно закрыть вкладку.")
                            throw ApiFailure("Anthropic OAuth: $error")
                        }
                        val code = callback.getQueryParameter("code").orEmpty()
                        val returnedState = callback.getQueryParameter("state").orEmpty()
                        if (code.isBlank() || returnedState != session.state) {
                            writeHtml(it, 200, "Некорректный OAuth-ответ Anthropic.")
                            throw ApiFailure("Anthropic OAuth вернул неверный state или пустой code")
                        }
                        val credentials = exchange(code, session.state, session.verifier)
                        writeHtml(it, 200, "Claude подключён к Subscription Proxy. Возвращайтесь в OmniChat.")
                        return@withContext credentials
                    }
                }
                throw ApiFailure("Истекло время ожидания входа Anthropic")
            } finally {
                runCatching { session.server.close() }
            }
        }

    fun cancel(session: LoginSession?) {
        runCatching { session?.server?.close() }
    }

    suspend fun refresh(
        credentials: SubscriptionProxyStore.ClaudeCredentials
    ): SubscriptionProxyStore.ClaudeCredentials = withContext(Dispatchers.IO) {
        require(credentials.refreshToken.isNotBlank()) { "Anthropic refresh token отсутствует" }
        val payload = JSONObject()
            .put("client_id", CLIENT_ID)
            .put("grant_type", "refresh_token")
            .put("refresh_token", credentials.refreshToken)
            .put("scope", SCOPE)
        val json = executeJson(
            Request.Builder()
                .url(TOKEN_URL)
                .apply(::oauthHeaders)
                .post(payload.toString().toRequestBody(jsonType))
                .build(),
            "Anthropic token refresh"
        )
        val refreshed = credentialsFrom(json)
        refreshed.copy(
            refreshToken = refreshed.refreshToken.ifBlank { credentials.refreshToken },
            email = refreshed.email.ifBlank { credentials.email },
            accountId = refreshed.accountId.ifBlank { credentials.accountId },
            organizationId = refreshed.organizationId.ifBlank { credentials.organizationId },
            organizationName = refreshed.organizationName.ifBlank { credentials.organizationName }
        )
    }

    suspend fun modelCatalog(
        credentials: SubscriptionProxyStore.ClaudeCredentials
    ): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$API_BASE/models?limit=100")
            .apply { apiHeaders(this, credentials.accessToken) }
            .get()
            .build()
        val json = executeJson(request, "Claude models")
        val data = json.optJSONArray("data") ?: JSONArray()
        val models = (0 until data.length()).mapNotNull { index ->
            val item = data.optJSONObject(index) ?: return@mapNotNull null
            item.optString("id").takeIf { it.startsWith("claude-", ignoreCase = true) }
        }.distinct()
        if (models.isEmpty()) throw ApiFailure("Claude не вернул доступные модели")
        models
    }

    private fun exchange(
        code: String,
        state: String,
        verifier: String
    ): SubscriptionProxyStore.ClaudeCredentials {
        val payload = JSONObject()
            .put("grant_type", "authorization_code")
            .put("code", code.substringBefore('#'))
            .put("redirect_uri", REDIRECT_URI)
            .put("client_id", CLIENT_ID)
            .put("code_verifier", verifier)
            .put("state", code.substringAfter('#', state))
        val json = executeJson(
            Request.Builder()
                .url(TOKEN_URL)
                .apply(::oauthHeaders)
                .post(payload.toString().toRequestBody(jsonType))
                .build(),
            "Anthropic OAuth token exchange"
        )
        val credentials = credentialsFrom(json)
        return runCatching {
            val profile = executeJson(
                Request.Builder()
                    .url(PROFILE_URL)
                    .apply(::oauthHeaders)
                    .header("Authorization", "Bearer ${credentials.accessToken}")
                    .header("Cache-Control", "no-cache")
                    .get()
                    .build(),
                "Anthropic profile"
            )
            credentials.copy(
                email = profile.optJSONObject("account")?.optString("email").orEmpty()
                    .ifBlank { credentials.email },
                accountId = profile.optJSONObject("account")?.optString("uuid").orEmpty()
                    .ifBlank { credentials.accountId },
                organizationId = profile.optJSONObject("organization")?.optString("uuid").orEmpty()
                    .ifBlank { credentials.organizationId },
                organizationName = profile.optJSONObject("organization")?.optString("name").orEmpty()
                    .ifBlank { credentials.organizationName }
            )
        }.getOrDefault(credentials)
    }

    private fun credentialsFrom(json: JSONObject): SubscriptionProxyStore.ClaudeCredentials {
        val access = json.optString("access_token")
        if (access.isBlank()) throw ApiFailure("Anthropic не вернул access token")
        val account = json.optJSONObject("account")
        val organization = json.optJSONObject("organization")
        return SubscriptionProxyStore.ClaudeCredentials(
            accessToken = access,
            refreshToken = json.optString("refresh_token"),
            expiresAtMs = System.currentTimeMillis() +
                json.optLong("expires_in", 3600L).coerceAtLeast(60L) * 1000L,
            email = account?.optString("email_address").orEmpty()
                .ifBlank { account?.optString("email").orEmpty() },
            accountId = account?.optString("uuid").orEmpty(),
            organizationId = organization?.optString("uuid").orEmpty(),
            organizationName = organization?.optString("name").orEmpty()
        )
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
        return Uri.parse("http://localhost${parts[1]}").takeIf { it.path == "/callback" }
    }

    private fun writeHtml(socket: java.net.Socket, status: Int, message: String) {
        val body = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head><body style="font-family:sans-serif;padding:32px"><h2>$message</h2></body></html>"""
        val bytes = body.toByteArray(Charsets.UTF_8)
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        writer.write("HTTP/1.1 $status ${if (status == 200) "OK" else "Not Found"}\r\n")
        writer.write("Content-Type: text/html; charset=utf-8\r\n")
        writer.write("Content-Length: ${bytes.size}\r\n")
        writer.write("Connection: close\r\n\r\n")
        writer.write(body)
        writer.flush()
    }

    companion object {
        const val AUTH_URL = "https://claude.ai/oauth/authorize"
        const val TOKEN_URL = "https://platform.claude.com/v1/oauth/token"
        const val PROFILE_URL = "https://api.anthropic.com/api/oauth/profile"
        const val API_BASE = "https://api.anthropic.com/v1"
        const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
        const val CALLBACK_PORT = 54545
        const val REDIRECT_URI = "http://localhost:54545/callback"
        const val SCOPE = "user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload"
        const val LOGIN_TTL_MS = 5 * 60 * 1000L

        val DEFAULT_MODELS = listOf(
            "claude-opus-5-5",
            "claude-sonnet-5-5",
            "claude-opus-5",
            "claude-sonnet-5",
            "claude-fable-5-1",
            "claude-fable-5",
            "claude-opus-4-8",
            "claude-opus-4-7",
            "claude-opus-4-6",
            "claude-sonnet-4-6",
            "claude-haiku-4-5-20251001"
        )

        fun apiHeaders(
            builder: Request.Builder,
            token: String,
            sessionId: String = ""
        ): Request.Builder {
            builder
                .header("Authorization", "Bearer $token")
                .header("Anthropic-Version", "2023-06-01")
                .header(
                    "Anthropic-Beta",
                    "claude-code-20250219,oauth-2025-04-20,interleaved-thinking-2025-05-14," +
                        "thinking-token-count-2026-05-13,context-management-2025-06-27," +
                        "prompt-caching-scope-2026-01-05,extended-cache-ttl-2025-04-11"
                )
                .header("Anthropic-Dangerous-Direct-Browser-Access", "true")
                .header("X-App", "cli")
                .header("X-Stainless-Retry-Count", "0")
                .header("X-Stainless-Runtime", "node")
                .header("X-Stainless-Lang", "js")
                .header("X-Stainless-Timeout", "600")
                .header("User-Agent", "claude-code/2.1.280")
                .header("Connection", "keep-alive")
                .header("x-client-request-id", java.util.UUID.randomUUID().toString())
            if (sessionId.isNotBlank()) {
                builder.header("X-Claude-Code-Session-Id", sessionId)
            }
            return builder
        }

        private fun oauthHeaders(builder: Request.Builder): Request.Builder = builder
            .header("Accept", "application/json, text/plain, */*")
            .header("Content-Type", "application/json")
            .header("User-Agent", "axios/1.15.2")

        private fun randomBase64Url(bytes: Int): String {
            val data = ByteArray(bytes)
            SecureRandom().nextBytes(data)
            return Base64.encodeToString(
                data,
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            )
        }

        private fun randomHex(bytes: Int): String {
            val data = ByteArray(bytes)
            SecureRandom().nextBytes(data)
            return buildString(bytes * 2) {
                data.forEach { append("%02x".format(it.toInt() and 0xff)) }
            }
        }
    }
}

