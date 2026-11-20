package app.githubble.mobile.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * GitHub Copilot sign-in: OAuth device flow (the same flow editors use) followed by an exchange of
 * the long-lived GitHub token for a short-lived Copilot API token. The long-lived token never leaves
 * github.com / api.github.com; only the short-lived token is sent to api.githubcopilot.com.
 */
class GitHubCopilotClient {
    data class DeviceLogin(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresAtMs: Long,
        val pollIntervalSeconds: Long
    )

    private val client get() = AiNetwork.client()
    private val penalty = ConcurrentHashMap<String, Long>()

    suspend fun beginLogin(): DeviceLogin = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().add("client_id", CLIENT_ID).add("scope", "read:user").build()
        val json = requestJson(
            Request.Builder().url(DEVICE_CODE_URL).header("Accept", "application/json")
                .header("User-Agent", USER_AGENT).post(body).build(),
            "GitHub Copilot device login"
        )
        val code = json.optString("device_code")
        val user = json.optString("user_code")
        require(code.isNotBlank() && user.isNotBlank()) { "GitHub не вернул код входа" }
        val uri = json.optString("verification_uri").ifBlank { "https://github.com/login/device" }
        require(uri.startsWith("https://github.com/")) { "GitHub вернул неизвестную страницу подтверждения" }
        DeviceLogin(
            deviceCode = code,
            userCode = user,
            verificationUri = uri,
            expiresAtMs = System.currentTimeMillis() + json.optLong("expires_in", 900L).coerceIn(60L, 1800L) * 1000L,
            pollIntervalSeconds = json.optLong("interval", 5L).coerceIn(3L, 30L)
        )
    }

    /** Returns the long-lived GitHub token, or null while the user has not confirmed yet. */
    suspend fun pollOnce(login: DeviceLogin): String? = withContext(Dispatchers.IO) {
        penalty[login.deviceCode]?.takeIf { it > 0L }?.let { kotlinx.coroutines.delay(it * 1000L) }
        if (System.currentTimeMillis() >= login.expiresAtMs) throw ApiFailure("Код GitHub Copilot истёк. Начните вход заново.")
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("device_code", login.deviceCode)
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .build()
        val raw = client.newCall(
            Request.Builder().url(TOKEN_URL).header("Accept", "application/json")
                .header("User-Agent", USER_AGENT).post(body).build()
        ).execute().use { it.body?.string().orEmpty() }
        val json = runCatching { JSONObject(raw) }.getOrElse { throw ApiFailure("GitHub вернул некорректный OAuth-ответ") }
        when (json.optString("error")) {
            "" -> Unit
            "authorization_pending" -> return@withContext null
            "slow_down" -> {
                penalty[login.deviceCode] = DeviceFlowPollBackoff.nextPenaltySeconds("slow_down", penalty[login.deviceCode] ?: 0L)
                return@withContext null
            }
            "expired_token" -> throw ApiFailure("Код GitHub Copilot истёк")
            "access_denied" -> throw ApiFailure("Вход GitHub Copilot отклонён")
            else -> throw ApiFailure("GitHub OAuth: " + json.optString("error_description").ifBlank { json.optString("error") })
        }
        json.optString("access_token").takeIf { it.isNotBlank() } ?: throw ApiFailure("GitHub не вернул токен")
    }

    /** Short-lived Copilot token + the API host chosen by GitHub for this account. */
    data class ApiToken(val token: String, val expiresAtMs: Long, val apiBase: String)

    suspend fun exchange(githubToken: String): ApiToken = withContext(Dispatchers.IO) {
        require(githubToken.isNotBlank()) { "Нет токена GitHub" }
        val request = Request.Builder().url(COPILOT_TOKEN_URL)
            .header("Authorization", "token $githubToken")
            .header("Accept", "application/json")
            .header("Editor-Version", EDITOR_VERSION)
            .header("Editor-Plugin-Version", PLUGIN_VERSION)
            .header("User-Agent", USER_AGENT)
            .get().build()
        val json = client.newCall(request).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            if (r.code == 401 || r.code == 403 || r.code == 404)
                throw ApiFailure("У этого аккаунта GitHub нет активной подписки Copilot (HTTP ${r.code})", r.code)
            if (!r.isSuccessful) throw ApiFailure("GitHub Copilot: HTTP ${r.code}", r.code)
            runCatching { JSONObject(raw) }.getOrElse { throw ApiFailure("GitHub Copilot вернул некорректный ответ") }
        }
        val token = json.optString("token")
        require(token.isNotBlank()) { "GitHub Copilot не выдал токен" }
        val api = json.optJSONObject("endpoints")?.optString("api").orEmpty()
        val base = api.takeIf { it.startsWith("https://") && (it.removePrefix("https://").substringBefore('/').let { h -> h == "githubcopilot.com" || h.endsWith(".githubcopilot.com") }) }
            ?: DEFAULT_API
        ApiToken(token, json.optLong("expires_at", 0L).let { if (it > 0) it * 1000L else System.currentTimeMillis() + 25 * 60_000L }, base.trimEnd('/'))
    }

    /** Chat-capable models of the subscription. */
    suspend fun models(token: ApiToken): List<String> = withContext(Dispatchers.IO) {
        val request = apiHeaders(Request.Builder().url(token.apiBase + "/models"), token.token).get().build()
        val json = client.newCall(request).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiFailure("Каталог Copilot: HTTP ${r.code}", r.code)
            JSONObject(raw)
        }
        val data = json.optJSONArray("data") ?: throw ApiFailure("Copilot не вернул каталог моделей")
        val ids = linkedSetOf<String>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isBlank() || id.length > 160) continue
            if (item.has("model_picker_enabled") && !item.optBoolean("model_picker_enabled", true)) continue
            val type = item.optJSONObject("capabilities")?.optString("type").orEmpty()
            if (type.isNotEmpty() && type != "chat") continue
            ids.add(id)
        }
        require(ids.isNotEmpty()) { "Каталог Copilot пуст: проверьте подписку и политику организации" }
        ids.toList()
    }

    private fun requestJson(request: Request, label: String): JSONObject =
        client.newCall(request).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiFailure("$label: HTTP ${r.code}", r.code)
            runCatching { JSONObject(raw) }.getOrElse { throw ApiFailure("$label: некорректный ответ") }
        }

    companion object {
        const val CLIENT_ID = "Iv1.b507a08c87ecfe98"
        const val DEVICE_CODE_URL = "https://github.com/login/device/code"
        const val TOKEN_URL = "https://github.com/login/oauth/access_token"
        const val COPILOT_TOKEN_URL = "https://api.github.com/copilot_internal/v2/token"
        const val DEFAULT_API = "https://api.githubcopilot.com"
        const val EDITOR_VERSION = "vscode/1.99.3"
        const val PLUGIN_VERSION = "copilot-chat/0.26.7"
        const val USER_AGENT = "GitHubCopilotChat/0.26.7"

        fun apiHeaders(builder: Request.Builder, token: String): Request.Builder = builder
            .header("Authorization", "Bearer $token")
            .header("Editor-Version", EDITOR_VERSION)
            .header("Editor-Plugin-Version", PLUGIN_VERSION)
            .header("Copilot-Integration-Id", "vscode-chat")
            .header("openai-intent", "conversation-panel")
            .header("X-Initiator", "user")
            .header("User-Agent", USER_AGENT)
    }
}
