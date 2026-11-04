package app.githubble.mobile.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Per-credential GigaChat OAuth token cache. Authorization keys remain encrypted in DB;
 * access tokens are short-lived and held in RAM only. No redirects or relaxed TLS.
 */
internal class GigaChatAuth(
    private val oauthUrl: String = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth"
) {
    private data class Session(val token: String, val expiresAtMs: Long)
    private val sessions = mutableMapOf<String, Session>()
    private val mutex = Mutex()
    private val http = OkHttpClient.Builder().dns(NetworkDnsPolicy.dns()).proxySelector(InAppVlessRouter.proxySelector()).addInterceptor(NetworkDnsPolicy.feedbackInterceptor()).followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    private fun fingerprint(authorizationKey: String, scope: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest((scope + ":" + authorizationKey).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    suspend fun accessToken(authorizationKey: String, scope: String): String {
        require(scope in setOf("GIGACHAT_API_PERS", "GIGACHAT_API_B2B", "GIGACHAT_API_CORP")) {
            "Некорректная область доступа GigaChat"
        }
        require(authorizationKey.isNotBlank()) { "Введите ключ авторизации GigaChat" }
        val cacheKey = fingerprint(authorizationKey, scope)
        return mutex.withLock {
            val now = System.currentTimeMillis()
            sessions[cacheKey]?.takeIf { it.expiresAtMs - 90_000L > now }?.let { return@withLock it.token }
            withContext(Dispatchers.IO) {
                val basic = authorizationKey.trim().removePrefix("Basic ").removePrefix("basic ")
                val request = Request.Builder().url(oauthUrl).post(
                    FormBody.Builder().add("scope", scope).build()
                ).header("Accept", "application/json")
                    .header("RqUID", UUID.randomUUID().toString())
                    .header("Authorization", "Basic $basic").build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw ApiFailure("GigaChat: не удалось получить OAuth-токен (HTTP ${response.code}); проверьте ключ и область доступа")
                    }
                    val json = JSONObject(response.body?.string().orEmpty())
                    val token = json.optString("access_token").takeIf { it.isNotBlank() }
                        ?: throw ApiFailure("GigaChat: ответ авторизации не содержит access_token")
                    val rawExpiry = json.optLong("expires_at", 0L)
                    val expiry = when {
                        rawExpiry > 10_000_000_000L -> rawExpiry
                        rawExpiry > 0L -> rawExpiry * 1000L
                        else -> System.currentTimeMillis() + 29 * 60_000L
                    }
                    if (expiry <= System.currentTimeMillis() + 20_000L) {
                        throw ApiFailure("GigaChat: сервер вернул истёкший токен")
                    }
                    sessions[cacheKey] = Session(token, expiry)
                    token
                }
            }
        }
    }
}

