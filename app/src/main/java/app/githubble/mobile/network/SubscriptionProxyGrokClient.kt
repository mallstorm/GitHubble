package app.githubble.mobile.network

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * Isolated xAI/Grok OAuth device flow for Subscription Proxy.
 *
 * Interoperability values and request shape follow the public Grok CLI flow used by
 * CLIProxyAPI. Credentials are persisted only by SubscriptionProxyStore.
 */
class SubscriptionProxyGrokClient {
    data class ImagineResult(val bytes: ByteArray, val mime: String, val model: String)
    data class VideoResult(
        val bytes: ByteArray,
        val mime: String,
        val model: String,
        val durationSeconds: Int,
        val requestId: String
    )

    data class DeviceLogin(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String,
        val expiresAtMs: Long,
        val pollIntervalSeconds: Long,
        val tokenEndpoint: String
    )

    private val pollPenaltySeconds = ConcurrentHashMap<String, Long>()

    private val client = OkHttpClient.Builder()
        .dns(NetworkDnsPolicy.dns())
        .addInterceptor(NetworkDnsPolicy.feedbackInterceptor())
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun beginDeviceLogin(): DeviceLogin = withContext(Dispatchers.IO) {
        val discovery = requestJson(
            Request.Builder().url(DISCOVERY_URL).header("Accept", "application/json").get().build(),
            "xAI OAuth discovery"
        )
        val deviceEndpoint = validateXaiEndpoint(discovery.optString("device_authorization_endpoint"))
        val tokenEndpoint = validateXaiEndpoint(discovery.optString("token_endpoint"))
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("scope", SCOPE)
            .build()
        val response = requestJson(
            Request.Builder().url(deviceEndpoint)
                .header("Accept", "application/json")
                .post(body).build(),
            "xAI device login"
        )
        val deviceCode = response.optString("device_code")
        val userCode = response.optString("user_code")
        val verificationUri = response.optString("verification_uri")
        val complete = response.optString("verification_uri_complete")
        require(deviceCode.isNotBlank() && userCode.isNotBlank()) {
            "xAI не вернул device code"
        }
        require(verificationUri.isNotBlank() || complete.isNotBlank()) {
            "xAI не вернул страницу подтверждения"
        }
        val expiresIn = response.optLong("expires_in", 900L).coerceIn(60L, 3600L)
        val interval = response.optLong("interval", 5L).coerceIn(3L, 30L)
        DeviceLogin(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = verificationUri,
            verificationUriComplete = complete.ifBlank { verificationUri },
            expiresAtMs = System.currentTimeMillis() + expiresIn * 1000L,
            pollIntervalSeconds = interval,
            tokenEndpoint = tokenEndpoint
        )
    }

    suspend fun pollDeviceOnce(login: DeviceLogin): SubscriptionProxyStore.GrokCredentials? =
        withContext(Dispatchers.IO) {
            pollPenaltySeconds[login.deviceCode]?.takeIf { it > 0L }?.let { delay(it * 1000L) }
            if (System.currentTimeMillis() >= login.expiresAtMs) {
                throw ApiFailure("Код xAI/Grok истёк. Начните вход заново.")
            }
            val body = FormBody.Builder()
                .add("grant_type", DEVICE_GRANT)
                .add("device_code", login.deviceCode)
                .add("client_id", CLIENT_ID)
                .build()
            val request = Request.Builder()
                .url(login.tokenEndpoint)
                .header("Accept", "application/json")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(raw) }.getOrElse {
                    throw ApiFailure("xAI вернул некорректный OAuth-ответ")
                }
                when (json.optString("error")) {
                    "authorization_pending" -> return@withContext null
                    "slow_down" -> {
                        pollPenaltySeconds[login.deviceCode] =
                            DeviceFlowPollBackoff.nextPenaltySeconds(
                                "slow_down",
                                pollPenaltySeconds[login.deviceCode] ?: 0L
                            )
                        return@withContext null
                    }
                    "expired_token" -> {
                        pollPenaltySeconds.remove(login.deviceCode)
                        throw ApiFailure("Код xAI/Grok истёк")
                    }
                    "access_denied" -> {
                        pollPenaltySeconds.remove(login.deviceCode)
                        throw ApiFailure("Вход xAI/Grok отклонён")
                    }
                    "" -> Unit
                    else -> throw ApiFailure(
                        json.optString("error_description").ifBlank {
                            "xAI OAuth: " + json.optString("error")
                        }
                    )
                }
                if (!response.isSuccessful) {
                    throw ApiFailure("xAI OAuth: HTTP ${response.code}", response.code)
                }
                pollPenaltySeconds.remove(login.deviceCode)
                return@withContext credentialsFrom(json, login.tokenEndpoint)
            }
        }

    suspend fun refresh(
        credentials: SubscriptionProxyStore.GrokCredentials
    ): SubscriptionProxyStore.GrokCredentials = withContext(Dispatchers.IO) {
        require(credentials.refreshToken.isNotBlank()) { "xAI refresh token отсутствует" }
        val endpoint = credentials.tokenEndpoint.ifBlank {
            val discovery = requestJson(
                Request.Builder().url(DISCOVERY_URL).header("Accept", "application/json").get().build(),
                "xAI OAuth discovery"
            )
            validateXaiEndpoint(discovery.optString("token_endpoint"))
        }
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", CLIENT_ID)
            .add("refresh_token", credentials.refreshToken)
            .build()
        val json = requestJson(
            Request.Builder().url(endpoint)
                .header("Accept", "application/json")
                .post(body).build(),
            "xAI token refresh"
        )
        val refreshed = credentialsFrom(json, endpoint)
        refreshed.copy(
            refreshToken = refreshed.refreshToken.ifBlank { credentials.refreshToken },
            email = refreshed.email.ifBlank { credentials.email },
            subject = refreshed.subject.ifBlank { credentials.subject }
        )
    }

    suspend fun modelCatalog(
        credentials: SubscriptionProxyStore.GrokCredentials
    ): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$GROK_BUILD_BASE/models")
            .apply { grokHeaders(this, credentials.accessToken) }
            .get()
            .build()
        val json = requestJson(request, "Grok models")
        val data = json.optJSONArray("data") ?: json.optJSONArray("models") ?: JSONArray()
        val models = (0 until data.length()).mapNotNull { index ->
            when (val item = data.opt(index)) {
                is String -> item.takeIf { it.isNotBlank() }
                is JSONObject -> item.optString("id")
                    .ifBlank { item.optString("name") }
                    .takeIf { it.isNotBlank() }
                else -> null
            }
        }.filter { it.startsWith("grok", ignoreCase = true) }.distinct().sorted()
        if (models.isEmpty()) throw ApiFailure("Grok не вернул доступные модели")
        models
    }

    suspend fun generateVideo(
        credentials: SubscriptionProxyStore.GrokCredentials,
        prompt: String,
        aspectRatio: String,
        durationSeconds: Int,
        resolution: String,
        sessionId: String = "",
        onProgress: (String) -> Unit = {}
    ): VideoResult = withContext(Dispatchers.IO) {
        require(prompt.isNotBlank()) { "Пустой запрос видео" }
        require(durationSeconds in setOf(6, 10)) { "Grok Imagine Video поддерживает 6 или 10 секунд" }
        require(resolution in setOf("480p", "720p")) { "Поддерживаются 480p и 720p" }
        val payload = JSONObject()
            .put("model", VIDEO_MODEL)
            .put("prompt", prompt.take(8_000))
            .put("duration", durationSeconds)
            .put("aspect_ratio", aspectRatio)
            .put("resolution", resolution)
        val startRequest = Request.Builder()
            .url("$GROK_BUILD_BASE/videos/generations")
            .apply { grokHeaders(this, credentials.accessToken, sessionId) }
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val start = try {
            requestJson(startRequest, "Grok Imagine Video")
        } catch (e: ApiFailure) {
            val msg = e.message.orEmpty()
            if (msg.contains("spending-limit", true) || msg.contains("tier", true) ||
                msg.contains("SuperGrok", true) || msg.contains("personal-team-blocked", true)) {
                throw ApiFailure("Grok Imagine Video недоступен для текущего тарифа. Требуется SuperGrok.")
            }
            throw e
        }
        val requestId = start.optString("request_id")
        if (requestId.isBlank()) throw ApiFailure("Grok Imagine Video не вернул request_id")
        onProgress("Grok Imagine Video · задача создана")
        val started = System.currentTimeMillis()
        var videoUrl = ""
        while (System.currentTimeMillis() - started < VIDEO_TIMEOUT_MS) {
            delay(VIDEO_POLL_INTERVAL_MS)
            val poll = requestJson(
                Request.Builder()
                    .url("$GROK_BUILD_BASE/videos/$requestId")
                    .apply { grokHeaders(this, credentials.accessToken, sessionId) }
                    .header("Accept", "application/json")
                    .get().build(),
                "Grok Imagine Video status"
            )
            val status = poll.optString("status").lowercase()
            val elapsed = ((System.currentTimeMillis() - started) / 1000L).coerceAtLeast(1L)
            when (status) {
                "done" -> {
                    videoUrl = poll.optJSONObject("video")?.optString("url").orEmpty()
                    if (videoUrl.isBlank()) throw ApiFailure("Grok Imagine Video завершил задачу без URL видео")
                    break
                }
                "failed" -> throw ApiFailure("Grok Imagine Video: генерация завершилась ошибкой")
                "expired" -> throw ApiFailure("Grok Imagine Video: задача истекла")
                else -> onProgress("Grok Imagine Video · генерация… ${elapsed} с")
            }
        }
        if (videoUrl.isBlank()) throw ApiFailure("Grok Imagine Video не завершил генерацию за 5 минут")
        val url = videoUrl.toHttpUrlOrNull() ?: throw ApiFailure("Grok Imagine Video вернул некорректный URL")
        if (url.scheme != "https" || !(url.host == "x.ai" || url.host.endsWith(".x.ai"))) {
            throw ApiFailure("Grok Imagine Video вернул URL вне x.ai")
        }
        onProgress("Grok Imagine Video · скачиваю MP4…")
        val downloadClient = client.newBuilder()
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(150, TimeUnit.SECONDS)
            .build()
        val bytes = downloadClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw ApiFailure("Не удалось скачать Grok-видео: HTTP ${response.code}")
            val contentLength = response.body?.contentLength() ?: -1L
            if (contentLength > MAX_VIDEO_BYTES) throw ApiFailure("Grok-видео превышает 150 МБ")
            response.body?.bytes() ?: throw ApiFailure("Grok Imagine Video вернул пустой MP4")
        }
        if (bytes.isEmpty()) throw ApiFailure("Grok Imagine Video вернул пустой MP4")
        if (bytes.size.toLong() > MAX_VIDEO_BYTES) throw ApiFailure("Grok-видео превышает 150 МБ")
        VideoResult(bytes, "video/mp4", VIDEO_MODEL, durationSeconds, requestId)
    }

    suspend fun subscriptionUsage(
        credentials: SubscriptionProxyStore.GrokCredentials
    ): GrokSubscriptionUsage = withContext(Dispatchers.IO) {
        val billingRequest = Request.Builder()
            .url("$GROK_BUILD_BASE/billing?format=credits")
            .apply { grokHeaders(this, credentials.accessToken) }
            .header("Accept", "application/json")
            .get().build()
        val billing = requestJson(billingRequest, "Grok usage")
        val settings = runCatching {
            requestJson(
                Request.Builder().url("$GROK_BUILD_BASE/settings")
                    .apply { grokHeaders(this, credentials.accessToken) }
                    .header("Accept", "application/json")
                    .get().build(),
                "Grok settings"
            )
        }.getOrNull()
        GrokSubscriptionSupport.parseUsage(billing, settings)
    }

    suspend fun generateImagine(
        credentials: SubscriptionProxyStore.GrokCredentials,
        prompt: String,
        aspectRatio: String,
        sessionId: String = ""
    ): ImagineResult = withContext(Dispatchers.IO) {
        require(prompt.isNotBlank()) { "Пустой запрос изображения" }
        val payload = JSONObject()
            .put("model", IMAGINE_MODEL)
            .put("prompt", prompt.take(8_000))
            .put("n", 1)
            .put("aspect_ratio", aspectRatio)
            .put("resolution", "1k")
            .put("response_format", "b64_json")
        val request = Request.Builder()
            .url("$GROK_BUILD_BASE/images/generations")
            .apply { grokHeaders(this, credentials.accessToken, sessionId) }
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val json = try {
            requestJson(request, "Grok Imagine")
        } catch (e: ApiFailure) {
            val msg = e.message.orEmpty()
            if (msg.contains("spending-limit", true) || msg.contains("tier", true) ||
                msg.contains("SuperGrok", true) || msg.contains("personal-team-blocked", true)) {
                throw ApiFailure("Grok Imagine недоступен для текущего тарифа. Генерация изображений через OAuth требует SuperGrok.")
            }
            throw e
        }
        val item = json.optJSONArray("data")?.optJSONObject(0)
            ?: throw ApiFailure("Grok Imagine не вернул изображение")
        val mime = item.optString("mime_type").ifBlank { "image/jpeg" }
        val encoded = item.optString("b64_json")
        val bytes = if (encoded.isNotBlank()) {
            Base64.decode(encoded, Base64.DEFAULT)
        } else {
            val rawUrl = item.optString("url")
            val url = rawUrl.toHttpUrlOrNull() ?: throw ApiFailure("Grok Imagine вернул некорректный URL")
            if (url.scheme != "https" || !(url.host == "x.ai" || url.host.endsWith(".x.ai"))) {
                throw ApiFailure("Grok Imagine вернул URL вне x.ai")
            }
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) throw ApiFailure("Не удалось скачать изображение Grok: HTTP ${response.code}")
                val data = response.body?.bytes() ?: throw ApiFailure("Grok Imagine вернул пустое изображение")
                if (data.size > 20_000_000) throw ApiFailure("Изображение Grok превышает 20 МБ")
                data
            }
        }
        if (bytes.isEmpty()) throw ApiFailure("Grok Imagine вернул пустое изображение")
        ImagineResult(bytes, mime, IMAGINE_MODEL)
    }

    suspend fun editImagine(
        credentials: SubscriptionProxyStore.GrokCredentials,
        prompt: String,
        sourceBytes: ByteArray,
        sourceMime: String,
        aspectRatio: String,
        sessionId: String = ""
    ): ImagineResult = withContext(Dispatchers.IO) {
        require(prompt.isNotBlank()) { "Пустой запрос редактирования изображения" }
        require(sourceBytes.isNotEmpty() && sourceBytes.size <= 20_000_000) {
            "Исходное изображение должно быть меньше 20 МБ"
        }
        require(sourceMime in setOf("image/jpeg", "image/png", "image/webp")) {
            "Grok Imagine Edit: неподдерживаемый формат $sourceMime"
        }
        val encoded = Base64.encodeToString(sourceBytes, Base64.NO_WRAP)
        val payload = JSONObject()
            .put("model", IMAGINE_MODEL)
            .put("prompt", prompt.take(8_000))
            .put("image", JSONObject()
                .put("url", "data:$sourceMime;base64,$encoded")
                .put("type", "image_url"))
            .put("n", 1)
            .put("aspect_ratio", aspectRatio)
            .put("resolution", "1k")
            .put("response_format", "b64_json")
        val request = Request.Builder()
            .url("$GROK_BUILD_BASE/images/edits")
            .apply { grokHeaders(this, credentials.accessToken, sessionId) }
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val json = requestJson(request, "Grok Imagine Edit")
        val item = json.optJSONArray("data")?.optJSONObject(0)
            ?: throw ApiFailure("Grok Imagine Edit не вернул изображение")
        val mime = item.optString("mime_type").ifBlank { "image/jpeg" }
        val b64 = item.optString("b64_json")
        val bytes = if (b64.isNotBlank()) {
            Base64.decode(b64, Base64.DEFAULT)
        } else {
            val rawUrl = item.optString("url")
            val url = rawUrl.toHttpUrlOrNull() ?: throw ApiFailure("Grok Imagine Edit вернул некорректный URL")
            if (url.scheme != "https" || !(url.host == "x.ai" || url.host.endsWith(".x.ai"))) {
                throw ApiFailure("Grok Imagine Edit вернул URL вне x.ai")
            }
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) throw ApiFailure("Не удалось скачать изображение Grok Edit: HTTP ${response.code}")
                val data = response.body?.bytes() ?: throw ApiFailure("Grok Imagine Edit вернул пустое изображение")
                if (data.size > 20_000_000) throw ApiFailure("Изображение Grok Edit превышает 20 МБ")
                data
            }
        }
        if (bytes.isEmpty()) throw ApiFailure("Grok Imagine Edit вернул пустое изображение")
        ImagineResult(bytes, mime, IMAGINE_MODEL)
    }
    companion object {
        const val DISCOVERY_URL = "https://auth.x.ai/.well-known/openid-configuration"
        const val CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
        const val SCOPE = "openid profile email offline_access grok-cli:access api:access"
        const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        const val GROK_BUILD_BASE = "https://cli-chat-proxy.grok.com/v1"
        const val CLIENT_VERSION = "1.0.45"
        const val IMAGINE_MODEL = "grok-imagine-image-2.0"
        const val VIDEO_MODEL = "grok-imagine-video-1.5"
        private const val VIDEO_POLL_INTERVAL_MS = 5_000L
        private const val VIDEO_TIMEOUT_MS = 300_000L
        private const val MAX_VIDEO_BYTES = 150_000_000L

        val DEFAULT_MODELS = listOf(
            "grok-4.7",
            "grok-4.7-build-fast",
            "grok-4.6",
            "grok-build-0.1",
            "grok-4.5",
            "grok-4.3",
            "grok-4.20-0309-reasoning",
            "grok-4.20-0309-non-reasoning",
            "grok-4.20-multi-agent-0309",
            "grok-3-mini",
            "grok-3-mini-fast",
            "grok-composer-2.5-fast"
        )

        fun grokHeaders(
            builder: Request.Builder,
            token: String,
            sessionId: String = ""
        ): Request.Builder {
            builder
                .header("Authorization", "Bearer $token")
                .header("X-XAI-Token-Auth", "xai-grok-cli")
                .header("x-grok-client-version", CLIENT_VERSION)
                .header("User-Agent", "xai-grok-workspace/$CLIENT_VERSION")
                .header("x-grok-client-identifier", "grok-shell")
                .header("x-authenticateresponse", "authenticate-response")
            if (sessionId.isNotBlank()) builder.header("x-grok-conv-id", sessionId)
            return builder
        }

        private fun validateXaiEndpoint(raw: String): String {
            val url = raw.toHttpUrlOrNull() ?: throw ApiFailure("Некорректный xAI OAuth endpoint")
            if (url.scheme != "https" || (url.host != "x.ai" && !url.host.endsWith(".x.ai"))) {
                throw ApiFailure("xAI OAuth endpoint находится вне x.ai")
            }
            return url.toString()
        }

        private fun credentialsFrom(
            json: JSONObject,
            tokenEndpoint: String
        ): SubscriptionProxyStore.GrokCredentials {
            val access = json.optString("access_token")
            if (access.isBlank()) throw ApiFailure("xAI не вернул access token")
            val expiresIn = json.optLong("expires_in", 3600L).coerceAtLeast(60L)
            val idToken = json.optString("id_token")
            val identity = jwtIdentity(idToken)
            return SubscriptionProxyStore.GrokCredentials(
                accessToken = access,
                refreshToken = json.optString("refresh_token"),
                idToken = idToken,
                tokenType = json.optString("token_type", "Bearer"),
                expiresAtMs = System.currentTimeMillis() + expiresIn * 1000L,
                email = identity.first,
                subject = identity.second,
                tokenEndpoint = tokenEndpoint
            )
        }

        private fun jwtIdentity(token: String): Pair<String, String> {
            val payload = token.split('.').getOrNull(1) ?: return "" to ""
            val decoded = runCatching {
                val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
                String(Base64.decode(padded, Base64.URL_SAFE), Charsets.UTF_8)
            }.getOrNull() ?: return "" to ""
            val json = runCatching { JSONObject(decoded) }.getOrNull() ?: return "" to ""
            return json.optString("email") to json.optString("sub")
        }
    }

    private fun requestJson(request: Request, label: String): JSONObject {
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(raw).optString("error_description") }
                    .getOrDefault("")
                    .ifBlank { raw.take(240) }
                throw ApiFailure("$label: ${detail.ifBlank { "HTTP ${response.code}" }}", response.code)
            }
            return runCatching { JSONObject(raw) }.getOrElse {
                throw ApiFailure("$label: некорректный JSON")
            }
        }
    }
}

