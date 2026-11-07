package app.githubble.mobile.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/**
 * Hosted NVIDIA NIM client used only by the isolated Subscription Proxy contour.
 *
 * NVIDIA's hosted NIM endpoint is OpenAI-compatible, so chat/streaming/tool calls continue
 * through ApiGateway. This client owns only live catalog discovery and key validation.
 */
class NvidiaNimClient(
    private val baseUrl: String = API_BASE,
    private val client: OkHttpClient = defaultClient(),
    private val inferenceProbeTimeoutMs: Long = DEFAULT_PROBE_TIMEOUT_MS
) {
    data class InferenceProbe(
        val available: Boolean,
        val message: String,
        val statusCode: Int? = null,
        val verifiedAtMs: Long = System.currentTimeMillis()
    )

    suspend fun modelCatalog(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val key = apiKey.trim()
        require(key.isNotBlank()) { "Введите NVIDIA API key" }

        val base = baseUrl.trim().trimEnd('/')
        val url = "$base/models".toHttpUrlOrNull()
            ?: throw ApiFailure("Некорректный NVIDIA NIM endpoint")
        NetworkEndpointPolicy.requireApiUrl(url, allowLocalHttp = true)

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = when (response.code) {
                    401 -> "NVIDIA NIM: API key отклонён (HTTP 401)"
                    403 -> "NVIDIA NIM: доступ к endpoint запрещён для этого ключа (HTTP 403)"
                    429 -> "NVIDIA NIM: достигнут временный лимит запросов (HTTP 429)"
                    else -> "NVIDIA NIM /models: HTTP ${response.code}"
                }
                throw ApiFailure(message, response.code)
            }

            val json = runCatching { JSONObject(raw) }.getOrElse {
                throw ApiFailure("NVIDIA NIM /models вернул некорректный JSON")
            }
            val data = json.optJSONArray("data") ?: json.optJSONArray("models") ?: JSONArray()
            val models = (0 until data.length()).mapNotNull { i ->
                when (val item = data.opt(i)) {
                    is String -> item.trim().takeIf { it.isNotBlank() }
                    is JSONObject -> item.optString("id")
                        .ifBlank { item.optString("name") }
                        .trim()
                        .takeIf { it.isNotBlank() }
                    else -> null
                }
            }.distinct()

            if (models.isEmpty()) {
                throw ApiFailure("NVIDIA NIM не вернул доступных моделей")
            }

            models.sortedWith(
                compareBy<String> { if (it == DEEPSEEK_V41_FLASH) 0 else 1 }
                    .thenBy { it.lowercase() }
            )
        }
    }

    suspend fun probeInference(
        apiKey: String,
        model: String = DEEPSEEK_V41_FLASH
    ): InferenceProbe = withContext(Dispatchers.IO) {
        val key = apiKey.trim()
        require(key.isNotBlank()) { "Введите NVIDIA API key" }
        val url = (baseUrl.trim().trimEnd('/') + "/chat/completions").toHttpUrlOrNull()
            ?: throw ApiFailure("Некорректный NVIDIA NIM inference endpoint")
        NetworkEndpointPolicy.requireApiUrl(url, allowLocalHttp = true)

        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply with exactly: OK")))
            .put("max_tokens", 16)
            .put("temperature", 1.0)
            .put("top_p", 0.95)
            .put("stream", false)

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val call = client.newCall(request)
        call.timeout().timeout(inferenceProbeTimeoutMs, TimeUnit.MILLISECONDS)
        try {
            call.execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = runCatching {
                        val json = JSONObject(raw)
                        json.optString("detail").ifBlank {
                            json.optString("message").ifBlank { json.optString("title") }
                        }
                    }.getOrDefault("").take(240)
                    val message = when (response.code) {
                        401 -> "NVIDIA NIM inference: API key отклонён (HTTP 401)"
                        403 -> "NVIDIA NIM inference запрещён для этой NVIDIA Cloud Account/Organization (HTTP 403)"
                        404 -> PUBLIC_ENDPOINTS_HINT + if (detail.isBlank()) "" else " · $detail"
                        429 -> "NVIDIA NIM inference доступен, но достигнут временный лимит запросов (HTTP 429)"
                        else -> "NVIDIA NIM inference: HTTP ${response.code}" +
                            if (detail.isBlank()) "" else " · $detail"
                    }
                    return@withContext InferenceProbe(false, message, response.code)
                }
                val json = runCatching { JSONObject(raw) }.getOrNull()
                val hasChoice = json?.optJSONArray("choices")?.length()?.let { it > 0 } == true
                InferenceProbe(
                    available = hasChoice,
                    message = if (hasChoice) "NVIDIA NIM inference endpoint отвечает"
                    else "NVIDIA NIM вернул HTTP 200, но без completion choice"
                )
            }
        } catch (_: InterruptedIOException) {
            InferenceProbe(false, PUBLIC_ENDPOINTS_HINT)
        }
    }

    companion object {
        const val API_BASE = "https://integrate.api.nvidia.com/v1"
        const val DEEPSEEK_V41_FLASH = "deepseek-ai/deepseek-v4.1-flash"
        const val DEFAULT_PROBE_TIMEOUT_MS = 15_000L
        const val RUNTIME_TIMEOUT_SECONDS = 45L
        const val PUBLIC_ENDPOINTS_HINT =
            "Каталог NVIDIA доступен, но inference endpoint не отвечает. Для Personal NVIDIA Cloud Account " +
                "это совпадает с отсутствием entitlement «Public API Endpoints». Проверьте Organization/permissions " +
                "в NVIDIA Build или обратитесь в NVIDIA Support."

        private val KNOWN_FREE_CHAT_ENDPOINTS = setOf(
            "deepseek-ai/deepseek-v4.1-flash",
            "z-ai/glm-5.3",
            "z-ai/glm-5.3-flash",
            "moonshotai/kimi-k3",
            "nvidia/nemotron-3.5-lightning-30b-a3b",
            "meta/muse-glimmer-30b",
            "poolside/laguna-xs-2.1",
            "google/diffusiongemma-26b-a4b-it",
            "nvidia/nemotron-3-ultra-550b-a55b",
            "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning",
            "nvidia/ising-calibration-1.5-31b",
            "google/gemma-4-31b-it",
            "nvidia/nemotron-3-super-120b-a12b"
        )

        fun isKnownFreeEndpoint(model: String): Boolean =
            model.trim().lowercase() in KNOWN_FREE_CHAT_ENDPOINTS

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .dns(NetworkDnsPolicy.dns()).proxySelector(InAppVlessRouter.proxySelector())
            .addInterceptor(NetworkDnsPolicy.feedbackInterceptor())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}

