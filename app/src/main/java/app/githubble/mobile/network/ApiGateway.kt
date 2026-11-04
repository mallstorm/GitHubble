package app.githubble.mobile.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import app.githubble.mobile.data.MessageEntity
import app.githubble.mobile.data.ProviderEntity
import app.githubble.mobile.network.LegacyAccountProviderIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.TimeZone


/** Null means API did not report an exact token count. */
data class UsageSnapshot(val inputTokens: Long?, val outputTokens: Long?)
data class CloudflareAccount(val id: String, val name: String)

/** HTTP transport and wire-protocol adapters. Never follows redirects carrying user API keys. */
class ApiGateway(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .dns(NetworkDnsPolicy.dns())
        .addInterceptor(NetworkDnsPolicy.feedbackInterceptor())
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .followRedirects(false).followSslRedirects(false).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val gigaAuth = GigaChatAuth()
    private val gigaUploadedFiles = ConcurrentHashMap<String, String>()

    companion object {
        const val CHAT = "openai-chat"
        const val RESPONSES = "openai-responses"
        const val ANTHROPIC = "anthropic"
        const val GEMINI = "gemini"
        const val OLLAMA = "ollama"
        const val CHATGPT_WEB = "chatgpt-web"
        val protocols = listOf(CHAT, RESPONSES, ANTHROPIC, GEMINI, OLLAMA, CHATGPT_WEB)

        /**
         * Chat Completions requires each function tool as
         * {"type":"function","function":{...}}. Responses API uses a flat descriptor.
         * Normalize defensively here so OpenAI-compatible gateways such as Kilo never
         * receive a flat tool object by mistake.
         */
        internal fun cloudflareModelsUrl(accountId: String): HttpUrl {
            val clean = accountId.trim()
            require(clean.matches(Regex("[A-Za-z0-9_-]{6,128}"))) { "Некорректный Cloudflare Account ID" }
            return "https://api.cloudflare.com/client/v4/accounts/$clean/ai/models/search"
                .toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("format", "openrouter")
                .build()
        }

        internal fun chatCompletionTools(tools: JSONArray): JSONArray = JSONArray().also { normalized ->
            for (i in 0 until tools.length()) {
                val tool = tools.optJSONObject(i) ?: throw ApiFailure("Некорректное описание инструмента")
                val wrapped = tool.optJSONObject("function")
                if (wrapped != null) {
                    normalized.put(tool)
                    continue
                }
                if (tool.optString("type") != "function" || tool.optString("name").isBlank()) {
                    throw ApiFailure("Некорректное описание function tool")
                }
                val function = JSONObject()
                    .put("name", tool.getString("name"))
                    .put("description", tool.optString("description"))
                    .put("parameters", tool.optJSONObject("parameters")
                        ?: JSONObject().put("type", "object").put("properties", JSONObject()))
                if (tool.has("strict")) function.put("strict", tool.optBoolean("strict"))
                normalized.put(JSONObject().put("type", "function").put("function", function))
            }
        }

        internal fun anthropicFunctionTools(tools: JSONArray): JSONArray = JSONArray().also { out ->
            for (i in 0 until tools.length()) {
                val tool = tools.optJSONObject(i) ?: continue
                val source = tool.optJSONObject("function") ?: tool
                val name = source.optString("name")
                if (name.isBlank()) continue
                out.put(
                    JSONObject()
                        .put("name", name)
                        .put("description", source.optString("description"))
                        .put(
                            "input_schema",
                            source.optJSONObject("parameters")
                                ?: JSONObject().put("type", "object").put("properties", JSONObject())
                        )
                )
            }
        }

        internal fun geminiFunctionTools(tools: JSONArray): JSONArray {
            val declarations = JSONArray()
            for (i in 0 until tools.length()) {
                val tool = tools.optJSONObject(i) ?: continue
                val source = tool.optJSONObject("function") ?: tool
                val name = source.optString("name")
                if (name.isBlank()) continue
                val declaration = JSONObject()
                    .put("name", name)
                    .put("description", source.optString("description"))
                    .put(
                        "parameters",
                        source.optJSONObject("parameters")
                            ?: JSONObject().put("type", "object").put("properties", JSONObject())
                    )
                declarations.put(declaration)
            }
            if (declarations.length() == 0) return JSONArray()
            return JSONArray().put(JSONObject().put("functionDeclarations", declarations))
        }

        /** Appending segments to a base ending in /v1 never creates a second /v1. */
        fun endpoint(base: String, protocol: String, vararg segments: String): HttpUrl {
            val url = base.trim().trimEnd('/').toHttpUrlOrNull() ?: throw ApiFailure("Некорректный адрес API")
            val allowLocalHttp = protocol == OLLAMA ||
                (protocol == CHAT && url.encodedPath.trimEnd('/').endsWith("/v1"))
            NetworkEndpointPolicy.requireApiUrl(url, allowLocalHttp = allowLocalHttp)
            val suffix = when (protocol) {
                CHAT -> "/chat/completions"
                RESPONSES -> "/responses"
                ANTHROPIC -> "/messages"
                OLLAMA -> "/api/chat"
                else -> ""
            }
            val builder = url.newBuilder()
            if (suffix.isNotEmpty() && url.encodedPath.endsWith(suffix)) {
                repeat(suffix.count { it == '/' }) { builder.removePathSegment(builder.build().pathSize - 1) }
            } else if (protocol == OLLAMA && url.encodedPath.endsWith("/api")) {
                builder.removePathSegment(builder.build().pathSize - 1)
            }
            segments.forEach { builder.addPathSegment(it) }
            return builder.build()
        }
    }

    private fun isOpenAiPlanResponses(providerType: String): Boolean =
        providerType == "openai-codex" ||
            providerType == LegacySubscriptionIds.PROVIDER_PREFIX + SubscriptionProxyStore.ACCOUNT_CODEX ||
            providerType == LegacySubscriptionIds.PROVIDER_PREFIX + SubscriptionProxyStore.ACCOUNT_CHATGPT

    private fun isOfficialChatGptPlan(providerType: String): Boolean =
        providerType == LegacySubscriptionIds.PROVIDER_PREFIX + SubscriptionProxyStore.ACCOUNT_CHATGPT

    private fun isExperimentalChatGpt(providerType: String): Boolean =
        providerType == LegacySubscriptionIds.PROVIDER_PREFIX + SubscriptionProxyStore.ACCOUNT_CHATGPT_JSON

    private suspend fun request(
        url: HttpUrl, key: String, protocol: String,
        providerType: String = "custom", authScope: String = "",
        sessionId: String = ""
    ): Request.Builder {
        val r = Request.Builder().url(url)
        when {
            isOfficialChatGptPlan(providerType) -> {
                if (url.host != "api.openai.com")
                    throw ApiFailure("ChatGPT plan usage разрешает только api.openai.com")
                require(key.isNotBlank()) { "Войдите через Continue with ChatGPT" }
                r.header("Authorization", "Bearer $key")
                    .header("User-Agent", "OmniChat-Android")
            }
            isExperimentalChatGpt(providerType) -> {
                require(key.isNotBlank()) { "Импортируйте ChatGPT Web JSON session" }
                if (url.host != "chatgpt.com") {
                    throw ApiFailure("ChatGPT Web Session разрешает только chatgpt.com")
                }
                r.header("Authorization", "Bearer $key")
                    .header("Origin", "https://chatgpt.com")
                    .header("Referer", "https://chatgpt.com/")
                    .header("User-Agent", "OmniChat-Android")
            }
            providerType == "openai-codex" ||
                providerType == LegacySubscriptionIds.PROVIDER_PREFIX + SubscriptionProxyStore.ACCOUNT_CODEX -> {
                if (url.host != "chatgpt.com")
                    throw ApiFailure("Для OpenAI ChatGPT Subscription разрешён только официальный Codex backend")
                require(key.isNotBlank()) { "Войдите в OpenAI ChatGPT Subscription" }
                r.header("Authorization", "Bearer $key")
                if (authScope.isNotBlank()) r.header("ChatGPT-Account-ID", authScope)
                r.header("originator", "omnichat")
                    .header("session_id", UUID.randomUUID().toString())
                    .header("User-Agent", "OmniChat-Android/${"legacy"}")
            }
            providerType == "gigachat" -> {
                if (url.host !in setOf("api.giga.chat", "gigachat.devices.sberbank.ru"))
                    throw ApiFailure("Для профиля GigaChat разрешены только официальные API-адреса")
                val token = gigaAuth.accessToken(key, authScope.ifBlank { "GIGACHAT_API_PERS" })
                r.header("Authorization", "Bearer $token")
            }
            providerType == LegacySubscriptionIds.PROVIDER_PREFIX + SubscriptionProxyStore.ACCOUNT_GROK -> {
                if (url.host != "cli-chat-proxy.grok.com")
                    throw ApiFailure("Subscription Proxy Grok разрешает только официальный Grok Build endpoint")
                require(key.isNotBlank()) { "Войдите в xAI/Grok в Subscription Proxy" }
                SubscriptionProxyGrokClient.grokHeaders(r, key, sessionId)
            }
            providerType == "yandex" -> {
                if (url.host != "ai.api.cloud.yandex.net")
                    throw ApiFailure("Для профиля Yandex AI Studio используйте официальный API-адрес")
                require(authScope.isNotBlank()) { "Укажите идентификатор каталога Yandex AI Studio" }
                require(key.isNotBlank()) { "Введите API-ключ Yandex AI Studio" }
                r.header("Authorization", "Api-Key $key")
                    .header("OpenAI-Project", authScope)
            }
            providerType == LegacySubscriptionIds.PROVIDER_PREFIX +
                SubscriptionProxyStore.ACCOUNT_CLAUDE -> {
                if (url.host != "api.anthropic.com")
                    throw ApiFailure("Subscription Proxy Claude разрешает только официальный Anthropic endpoint")
                require(key.isNotBlank()) { "Войдите в Anthropic Claude в Subscription Proxy" }
                SubscriptionProxyClaudeClient.apiHeaders(r, key, sessionId)
            }
            providerType in setOf(
                LegacyAccountProviderIds.OPENCODE_ZEN_ID,
                LegacyAccountProviderIds.OPENCODE_GO_ID
            ) && protocol == ANTHROPIC -> {
                require(key.isNotBlank()) { "Введите API-ключ провайдера" }
                r.header("Authorization", "Bearer $key")
                    .header("anthropic-version", "2023-06-01")
            }
            protocol == ANTHROPIC -> {
                r.header("x-api-key", key).header("anthropic-version", "2023-06-01")
            }
            protocol == GEMINI -> if (key.isNotBlank()) r.header("x-goog-api-key", key)
            else -> if (key.isNotBlank()) r.header("Authorization", "Bearer $key")
        }
        if (providerType == LegacyAccountProviderIds.REQUESTY_ID) {
            r.header("X-Title", "OmniChat")
                .header("User-Agent", "OmniChat-Android")
        }
        if (providerType in setOf(LegacyAccountProviderIds.POLZA_ID, LegacyAccountProviderIds.VSEGPT_ID)) {
            r.header("X-Title", "OmniChat")
                .header("User-Agent", "OmniChat-Android")
        }
        if (providerType in setOf(LegacyAccountProviderIds.OPENCODE_ZEN_ID, LegacyAccountProviderIds.OPENCODE_GO_ID)) {
            r.header("User-Agent", "OmniChat-Android/0.8")
                .header("X-Title", "OmniChat")
            if (providerType == LegacyAccountProviderIds.OPENCODE_GO_ID && sessionId.isNotBlank()) {
                r.header("x-opencode-session", sessionId)
            }
        }
        if (providerType == LegacyAccountProviderIds.ZOO_GATEWAY_ID) {
            r.header("User-Agent", "OmniChat-Android/0.8")
                .header("X-Title", "OmniChat")
        }
        if (providerType == LegacyAccountProviderIds.KIMI_CODE_ID) {
            // Kimi Code requires third-party clients to keep their real client identity.
            r.header("User-Agent", "OmniChat-Android")
                .header("X-Title", "OmniChat")
        }
        if (providerType.startsWith("cline")) {
            // Identify OmniChat as its own Cline API client. Account OAuth providers such
            // as pi-cline also send a client type; this keeps Cline routing/account
            // policies separate from anonymous generic OpenAI-compatible traffic.
            r.header("User-Agent", "OmniChat-Android")
                .header("X-CLIENT-TYPE", "omnichat")
                .header("X-Title", "OmniChat")
        }
        return r
    }

    private suspend fun execute(req: Request, action: suspend (Response) -> Unit) {
        val call = client.newCall(req)
        val nvidiaHosted = req.url.host == "integrate.api.nvidia.com"
        if (nvidiaHosted) {
            // Hosted NIM can currently leave unauthorized Personal org inference requests
            // open with zero bytes. Never let one provider stall the whole OmniChat session forever.
            call.timeout().timeout(NvidiaNimClient.RUNTIME_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        // A child on another dispatcher cancels blocking OkHttp reads immediately on coroutine cancellation.
        val canceller = CoroutineScope(currentCoroutineContext() + Dispatchers.Default).launch {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw statusError(response.code, response.body?.string().orEmpty())
                action(response)
            }
        } catch (error: InterruptedIOException) {
            currentCoroutineContext().ensureActive()
            if (nvidiaHosted) throw ApiFailure(NvidiaNimClient.PUBLIC_ENDPOINTS_HINT)
            throw error
        } finally { canceller.cancel() }
    }

    suspend fun cloudflareAccounts(apiToken: String): List<CloudflareAccount> = withContext(Dispatchers.IO) {
        require(apiToken.isNotBlank()) { "Введите Cloudflare API token" }
        val url = "https://api.cloudflare.com/client/v4/accounts"
            .toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("per_page", "50")
            .build()
        var accounts = emptyList<CloudflareAccount>()
        execute(request(url, apiToken, CHAT, LegacyAccountProviderIds.CLOUDFLARE_ID).get().build()) { response ->
            val json = JSONObject(response.body?.string() ?: "{}")
            val result = json.optJSONArray("result") ?: JSONArray()
            accounts = (0 until result.length()).mapNotNull { index ->
                val item = result.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id")
                if (id.isBlank()) null else CloudflareAccount(id, item.optString("name"))
            }
        }
        accounts
    }

    suspend fun models(
        base: String, apiKey: String, protocol: String, providerType: String = "custom",
        authScope: String = ""
    ): List<String> = withContext(Dispatchers.IO) {
        require(protocol in protocols) { "Неподдерживаемый протокол" }
        val url = when {
            providerType == LegacyAccountProviderIds.COHERE_ID ->
                "https://api.cohere.ai/v1/models".toHttpUrlOrNull()
                    ?: throw ApiFailure("Некорректный Cohere models URL")
            providerType == LegacyAccountProviderIds.CLOUDFLARE_ID ->
                cloudflareModelsUrl(authScope)
            providerType == LegacyAccountProviderIds.POLZA_ID ->
                endpoint(base, protocol, "models").newBuilder()
                    .addQueryParameter("type", "chat")
                    .addQueryParameter("include_providers", "true")
                    .build()
            protocol == OLLAMA -> endpoint(base, protocol, "api", "tags")
            else -> endpoint(base, protocol, "models")
        }
        var ids = emptyList<String>()
        execute(request(url, apiKey, protocol, providerType, authScope).get().build()) { response ->
            val json = JSONObject(response.body?.string() ?: "{}")
            if (providerType == LegacyAccountProviderIds.COHERE_ID) {
                val items = json.optJSONArray("models") ?: JSONArray()
                ids = (0 until items.length()).mapNotNull { i ->
                    val item = items.optJSONObject(i) ?: return@mapNotNull null
                    val endpoints = item.optJSONArray("endpoints")
                    val supportsChat = endpoints == null || (0 until endpoints.length())
                        .any { index -> endpoints.optString(index).equals("chat", ignoreCase = true) }
                    item.optString("name").takeIf { supportsChat && it.isNotBlank() }
                }.sorted()
            } else if (providerType == LegacyAccountProviderIds.CLOUDFLARE_ID) {
                // Workers AI model discovery is a management API, not /ai/v1/models.
                // Depending on the requested output format Cloudflare may expose the
                // model array directly in result, in result.data, or at top-level data.
                val result = json.opt("result")
                val items = when (result) {
                    is JSONArray -> result
                    is JSONObject -> result.optJSONArray("data") ?: JSONArray()
                    else -> json.optJSONArray("data") ?: JSONArray()
                }
                ids = (0 until items.length()).mapNotNull { i ->
                    val item = items.optJSONObject(i) ?: return@mapNotNull null
                    sequenceOf("id", "name", "model", "model_id")
                        .map { keyName -> item.optString(keyName) }
                        .firstOrNull { it.startsWith("@cf/") }
                }.distinct().sorted()
            } else if (providerType == LegacyAccountProviderIds.ZOO_GATEWAY_ID) {
                val items = json.optJSONArray("data") ?: JSONArray()
                ids = (0 until items.length()).mapNotNull { i ->
                    val item = items.optJSONObject(i) ?: return@mapNotNull null
                    val type = item.optString("type")
                    item.optString("id").takeIf {
                        it.isNotBlank() && (type.isBlank() || type.equals("language", ignoreCase = true))
                    }
                }.distinct().sorted()
            } else {
                val items = json.optJSONArray(if (protocol == OLLAMA || protocol == GEMINI) "models" else "data") ?: JSONArray()
                ids = (0 until items.length()).mapNotNull { i ->
                    val item = items.optJSONObject(i)
                    val key = if (protocol == OLLAMA) "name" else if (protocol == GEMINI) "name" else "id"
                    item?.optString(key)?.removePrefix(if (protocol == GEMINI) "models/" else "")?.takeIf { it.isNotBlank() }
                }.sorted()
            }
        }
        ids
    }

    suspend fun generateImage(provider: ProviderEntity, key: String, prompt: String,
                              size: String = "1024x1024"): ByteArray = withContext(Dispatchers.IO) {
        require(provider.providerType == "yandex") { "Генерация изображений доступна только для профиля Yandex AI Studio" }
        require(provider.authScope.isNotBlank()) { "Укажите ID каталога Yandex AI Studio" }
        val cleanPrompt = prompt.trim()
        require(cleanPrompt.isNotBlank()) { "Введите описание изображения" }
        require(cleanPrompt.length <= 500) { "Описание YandexART не должно превышать 500 символов" }
        require(size in setOf("1024x1024", "1536x1024", "1024x1536")) {
            "Неподдерживаемый размер изображения"
        }
        val modelUri = if (provider.model.startsWith("art://", ignoreCase = true)) provider.model
            else "art://${provider.authScope}/${provider.model}"
        require(modelUri.substringAfterLast('/') == "aliceai-image-art-3.0") {
            "Выберите модель Alice AI ART 3.0"
        }
        val body = JSONObject()
            .put("model", modelUri)
            .put("prompt", cleanPrompt)
            .put("size", size)
        var bytes = ByteArray(0)
        val url = endpoint(provider.baseUrl, CHAT, "images", "generations")
        execute(request(url, key, CHAT, provider.providerType, provider.authScope)
            .post(body.toString().toRequestBody(jsonType)).build()) { response ->
            val json = JSONObject(response.body?.string() ?: "{}")
            val encoded = json.optJSONArray("data")?.optJSONObject(0)?.optString("b64_json").orEmpty()
            if (encoded.isBlank()) throw ApiFailure("YandexART не вернул изображение")
            bytes = try { Base64.decode(encoded, Base64.DEFAULT) }
                catch (_: IllegalArgumentException) { throw ApiFailure("YandexART вернул повреждённое изображение") }
        }
        if (bytes.isEmpty() || bytes.size > 20_000_000) throw ApiFailure("Некорректный размер изображения YandexART")
        val png = bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        val jpeg = bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        if (!png && !jpeg) throw ApiFailure("YandexART вернул неподдерживаемый формат")
        bytes
    }

    data class GeminiInlineImage(val bytes: ByteArray, val mime: String)
    data class GeminiImageResult(
        val images: List<GeminiInlineImage>,
        val text: String,
        val model: String
    )

    data class GeminiMusicResult(
        val bytes: ByteArray,
        val mime: String,
        val text: String,
        val model: String
    )

    suspend fun generateSubscriptionProxyGeminiMusic(
        provider: ProviderEntity,
        key: String,
        prompt: String,
        referenceImages: List<LocalAttachment> = emptyList()
    ): GeminiMusicResult = withContext(Dispatchers.IO) {
        require(
            provider.providerType == LegacySubscriptionIds.PROVIDER_PREFIX +
                SubscriptionProxyStore.ACCOUNT_GEMINI
        ) { "Нужен Subscription Proxy Google Gemini" }
        val model = SubscriptionProxyGeminiSupport.canonicalMusicModel(provider.model)
        require(
            SubscriptionProxyGeminiSupport.VERTEX_MUSIC_MODELS.any {
                it.equals(model, ignoreCase = true)
            }
        ) { "Выбранная модель Lyria не поддерживается Gemini API" }
        require(key.isNotBlank()) { "Для Lyria нужен Gemini API key из Google AI Studio" }
        require(prompt.isNotBlank()) { "Опишите музыку или песню" }

        val input = JSONArray().put(
            JSONObject()
                .put("type", "text")
                .put("text", prompt.take(100_000))
        )
        val attachmentRoot = File(context.filesDir, "attachments").canonicalFile
        referenceImages
            .asSequence()
            .filter { it.mime.startsWith("image/", ignoreCase = true) }
            .take(10)
            .forEach { attachment ->
                val source = runCatching { File(attachment.path).canonicalFile }.getOrNull()
                    ?: return@forEach
                if (!source.isFile ||
                    !source.path.startsWith(attachmentRoot.path + File.separator) ||
                    source.length() <= 0L ||
                    source.length() > 20_000_000L
                ) return@forEach
                input.put(
                    JSONObject()
                        .put("type", "image")
                        .put("mime_type", attachment.mime)
                        .put("data", Base64.encodeToString(source.readBytes(), Base64.NO_WRAP))
                )
            }

        val body = JSONObject()
            .put("model", model)
            .put("input", if (input.length() == 1) prompt.take(100_000) else input)

        val url = "https://generativelanguage.googleapis.com/v1beta/interactions"
            .toHttpUrlOrNull()
            ?: throw ApiFailure("Некорректный Gemini Lyria endpoint")
        val req = Request.Builder()
            .url(url)
            .header("x-goog-api-key", key)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json; charset=utf-8")
            .header("User-Agent", "OmniChat-Android")
            .post(body.toString().toRequestBody(jsonType))
            .build()

        var raw = ""
        try {
            execute(req) { response -> raw = response.body?.string().orEmpty() }
        } catch (error: ApiFailure) {
            when (error.statusCode) {
                401, 403 -> throw ApiFailure(
                    "Gemini API не разрешил Lyria. Проверьте Gemini API key и платный billing проекта; " +
                        "для Lyria бесплатный API-тариф недоступен.",
                    error.statusCode
                )
                429 -> throw ApiFailure(
                    "Gemini Lyria: исчерпан лимит или квота проекта.",
                    error.statusCode
                )
                else -> throw error
            }
        }

        val json = runCatching { JSONObject(raw) }.getOrElse {
            throw ApiFailure("Gemini Lyria вернула некорректный JSON")
        }
        val interaction = json.optJSONObject("interaction") ?: json
        val textParts = mutableListOf<String>()
        var audioBytes: ByteArray? = null
        var audioMime = "audio/mpeg"

        interaction.optString("output_text")
            .trim()
            .takeIf(String::isNotBlank)
            ?.let(textParts::add)

        interaction.optJSONObject("output_audio")?.let { audio ->
            val data = audio.optString("data")
            val mime = audio.optString("mime_type")
                .ifBlank { audio.optString("mimeType") }
                .ifBlank { "audio/mpeg" }
            if (data.isNotBlank()) {
                val decoded = try {
                    Base64.decode(data, Base64.DEFAULT)
                } catch (_: IllegalArgumentException) {
                    throw ApiFailure("Gemini Lyria вернула повреждённое аудио")
                }
                if (decoded.isNotEmpty() && decoded.size <= 80_000_000) {
                    audioBytes = decoded
                    audioMime = mime
                }
            }
        }

        val steps = interaction.optJSONArray("steps") ?: JSONArray()
        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: continue
            if (step.optString("type") != "model_output") continue
            val content = step.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val block = content.optJSONObject(j) ?: continue
                when (block.optString("type").lowercase()) {
                    "text" -> block.optString("text").trim()
                        .takeIf(String::isNotBlank)
                        ?.let(textParts::add)
                    "audio" -> if (audioBytes == null) {
                        val data = block.optString("data")
                        val mime = block.optString("mime_type")
                            .ifBlank { block.optString("mimeType") }
                            .ifBlank { "audio/mpeg" }
                        if (data.isNotBlank()) {
                            val decoded = try {
                                Base64.decode(data, Base64.DEFAULT)
                            } catch (_: IllegalArgumentException) {
                                throw ApiFailure("Gemini Lyria вернула повреждённое аудио")
                            }
                            if (decoded.isNotEmpty() && decoded.size <= 80_000_000) {
                                audioBytes = decoded
                                audioMime = mime
                            }
                        }
                    }
                }
            }
        }

        val finalAudio = audioBytes ?: throw ApiFailure(
            "Gemini Lyria ответила без аудио. Проверьте доступ Lyria и billing проекта."
        )
        GeminiMusicResult(
            bytes = finalAudio,
            mime = audioMime,
            text = textParts.distinct().joinToString("\n\n"),
            model = model
        )
    }

    suspend fun analyzeSubscriptionProxyGeminiScene(
        provider: ProviderEntity,
        key: String,
        imageBytes: ByteArray,
        imageMime: String,
        sceneHint: String = ""
    ): String = withContext(Dispatchers.IO) {
        require(
            provider.providerType == LegacySubscriptionIds.PROVIDER_PREFIX +
                SubscriptionProxyStore.ACCOUNT_GEMINI
        ) { "Нужен Subscription Proxy Google Gemini" }
        require(key.isNotBlank()) { "Войдите в Google Gemini в Subscription Proxy" }
        require(provider.authScope.isNotBlank()) {
            "Subscription Proxy Gemini: отсутствует Google Cloud Code project ID"
        }
        require(imageBytes.isNotEmpty() && imageBytes.size <= 20_000_000) {
            "Изображение для анализа должно быть меньше 20 МБ"
        }
        require(imageMime.startsWith("image/")) { "Для анализа требуется изображение" }

        val prompt = buildString {
            append(
                "Analyze the supplied image as persistent scene state for later image editing. " +
                    "Return exactly one JSON object and no markdown/code fences. " +
                    "Schema: {\"subjects\":[{\"name\":\"short label\",\"description\":\"stable visible attributes and position\"}]," +
                    "\"background\":\"environment/background only\",\"style\":\"rendering/visual style\"," +
                    "\"composition\":\"framing, camera, relative subject positions\",\"lighting\":\"lighting/color temperature\"}. " +
                    "Describe only what is visibly present. Do not invent hidden attributes. " +
                    "Prefer concise Russian subject labels when the context is Russian. "
            )
            if (sceneHint.isNotBlank()) {
                append("Existing user context, only as a naming hint: ")
                append(sceneHint.take(1_500))
            }
        }

        val parts = JSONArray()
            .put(JSONObject().put("text", prompt))
            .put(
                JSONObject().put(
                    "inline_data",
                    JSONObject()
                        .put("mime_type", imageMime)
                        .put("data", Base64.encodeToString(imageBytes, Base64.NO_WRAP))
                )
            )
        val requestPayload = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", parts)
                )
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("TEXT"))
                    .put("temperature", 0.1)
            )
        val body = JSONObject()
            .put("model", provider.model)
            .put("userAgent", "antigravity")
            .put("requestType", "agent")
            .put("project", provider.authScope)
            .put("requestId", "scene-analysis-" + UUID.randomUUID())
            .put("request", requestPayload)

        val url = (provider.baseUrl.trimEnd('/') +
            "/v1internal:streamGenerateContent?alt=sse").toHttpUrlOrNull()
            ?: throw ApiFailure("Некорректный Google Cloud Code endpoint")
        val request = Request.Builder()
            .url(url)
            .apply { SubscriptionProxyGeminiClient.cloudCodeHeaders(this, key) }
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody(jsonType))
            .build()

        val output = StringBuilder()
        fun consume(payload: String) {
            if (payload.isBlank() || payload == "[DONE]") return
            val event = runCatching { JSONObject(payload) }.getOrNull() ?: return
            if (event.has("error") && !event.isNull("error")) {
                throw ApiFailure(streamErrorMessage(event))
            }
            val nested = event.optJSONObject("response") ?: event
            val candidates = nested.optJSONArray("candidates") ?: return
            for (i in 0 until candidates.length()) {
                val responseParts = candidates.optJSONObject(i)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts") ?: continue
                for (j in 0 until responseParts.length()) {
                    responseParts.optJSONObject(j)
                        ?.optString("text")
                        ?.takeIf(String::isNotBlank)
                        ?.let(output::append)
                }
            }
        }

        execute(request) { response ->
            val reader = response.body?.charStream()?.buffered()
                ?: throw ApiFailure("Gemini Scene Analyzer вернул пустой поток")
            val decoder = SseDecoder()
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = reader.readLine() ?: break
                decoder.feed(line)?.let(::consume)
            }
            decoder.flush()?.let(::consume)
        }
        output.toString().trim().takeIf(String::isNotBlank)
            ?: throw ApiFailure("Gemini Scene Analyzer не вернул описание сцены")
    }

    suspend fun generateSubscriptionProxyGeminiImage(
        provider: ProviderEntity,
        key: String,
        history: List<MessageEntity>,
        aspectRatio: String? = null
    ): GeminiImageResult = withContext(Dispatchers.IO) {
        require(
            provider.providerType == LegacySubscriptionIds.PROVIDER_PREFIX +
                SubscriptionProxyStore.ACCOUNT_GEMINI
        ) { "Нужен Subscription Proxy Google Gemini" }
        require(SubscriptionProxyGeminiSupport.isImageModel(provider.model)) {
            "Выбранная модель Gemini не поддерживает генерацию изображений"
        }
        require(key.isNotBlank()) { "Войдите в Google Gemini в Subscription Proxy" }
        require(provider.authScope.isNotBlank()) {
            "Subscription Proxy Gemini: отсутствует Google Cloud Code project ID"
        }

        val relevant = history
            .filter { it.role == "user" || (it.role == "assistant" && it.status == "complete") }
            .takeLast(8)
        val generationConfig = JSONObject().put(
            "responseModalities",
            JSONArray().put("TEXT").put("IMAGE")
        )
        aspectRatio?.takeIf { it.isNotBlank() }?.let { ratio ->
            generationConfig.put(
                "imageConfig",
                JSONObject().put("aspectRatio", ratio)
            )
        }
        val requestPayload = JSONObject()
            .put("contents", geminiHistory(relevant))
            .put("generationConfig", generationConfig)
        if (provider.systemPrompt.isNotBlank()) {
            requestPayload.put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", provider.systemPrompt))
                )
            )
        }
        val body = JSONObject()
            .put("model", provider.model)
            .put("userAgent", "antigravity")
            .put("requestType", "image_gen")
            .put("project", provider.authScope)
            .put("requestId", SubscriptionProxyGeminiSupport.imageRequestId())
            .put("request", requestPayload)

        val url = (provider.baseUrl.trimEnd('/') +
            "/v1internal:streamGenerateContent?alt=sse").toHttpUrlOrNull()
            ?: throw ApiFailure("Некорректный Google Cloud Code endpoint")
        val req = Request.Builder()
            .url(url)
            .apply { SubscriptionProxyGeminiClient.cloudCodeHeaders(this, key) }
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody(jsonType))
            .build()

        val encodedImages = mutableListOf<Pair<String, String>>()
        val outputText = StringBuilder()

        fun consumePayload(payload: String) {
            if (payload.isBlank() || payload == "[DONE]") return
            val event = runCatching { JSONObject(payload) }.getOrNull() ?: return
            if (event.has("error") && !event.isNull("error")) {
                throw ApiFailure(streamErrorMessage(event))
            }
            val nested = event.optJSONObject("response") ?: event
            val candidates = nested.optJSONArray("candidates") ?: return
            for (i in 0 until candidates.length()) {
                val parts = candidates.optJSONObject(i)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts") ?: continue
                for (j in 0 until parts.length()) {
                    val part = parts.optJSONObject(j) ?: continue
                    part.optString("text").takeIf { it.isNotBlank() }?.let(outputText::append)
                    val inline = part.optJSONObject("inlineData")
                        ?: part.optJSONObject("inline_data")
                        ?: continue
                    val data = inline.optString("data")
                    val mime = inline.optString("mimeType")
                        .ifBlank { inline.optString("mime_type") }
                        .ifBlank { "image/png" }
                    if (data.isNotBlank() && mime.startsWith("image/")) {
                        encodedImages += mime to data
                    }
                }
            }
        }

        try {
            execute(req) { response ->
                val reader = response.body?.charStream()?.buffered()
                    ?: throw ApiFailure("Google Gemini Image вернул пустой поток")
                val decoder = SseDecoder()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = reader.readLine() ?: break
                    decoder.feed(line)?.let(::consumePayload)
                }
                decoder.flush()?.let(::consumePayload)
            }
        } catch (error: ApiFailure) {
            if (error.message?.contains("HTTP 400") == true) {
                Log.w("OmniChat-GeminiImage", "HTTP 400 · " + imageHistoryDebugSummary(relevant))
            }
            throw error
        }

        val images = encodedImages.distinctBy { it.first + ":" + it.second.take(48) }.map { (mime, encoded) ->
            val bytes = try {
                Base64.decode(encoded, Base64.DEFAULT)
            } catch (_: IllegalArgumentException) {
                throw ApiFailure("Gemini Image вернул повреждённое изображение")
            }
            if (bytes.isEmpty() || bytes.size > 25_000_000)
                throw ApiFailure("Gemini Image вернул изображение некорректного размера")
            GeminiInlineImage(bytes, mime)
        }
        if (images.isEmpty()) throw ApiFailure(
            "Google OAuth ответил без изображения. Возможно, image-модель недоступна для этого аккаунта."
        )
        GeminiImageResult(images, outputText.toString(), provider.model)
    }

    /**
     * ChatGPT plan Responses transport is stream-only. Consume SSE and return the final
     * Response object so media/tool callers can share the same transport contract as chat.
     */
    private suspend fun executeOpenAiResponsesStreamFinal(
        provider: ProviderEntity,
        key: String,
        body: JSONObject
    ): JSONObject {
        body.put("stream", true)
        var completed: JSONObject? = null
        var latestResponse: JSONObject? = null

        fun consume(payload: String) {
            if (payload.isBlank() || payload == "[DONE]") return
            val event = runCatching { JSONObject(payload) }.getOrNull() ?: return
            val type = event.optString("type")
            event.optJSONObject("response")?.let { latestResponse = it }

            if (type == "error" || (event.has("error") && !event.isNull("error"))) {
                throw ApiFailure(streamErrorMessage(event))
            }
            if (type == "response.failed") {
                val failure = event.optJSONObject("response")?.optJSONObject("error")
                val detail = failure?.optString("message").orEmpty()
                throw ApiFailure(
                    if (detail.isBlank()) "ChatGPT Responses: запрос завершился ошибкой"
                    else "ChatGPT/OpenAI: ${detail.take(260)}"
                )
            }
            if (type == "response.completed") {
                completed = event.optJSONObject("response") ?: latestResponse
            }
        }

        execute(
            request(
                endpoint(provider.baseUrl, RESPONSES, "responses"),
                key,
                RESPONSES,
                provider.providerType,
                provider.authScope
            )
                .header("Accept", "text/event-stream")
                .post(body.toString().toRequestBody(jsonType))
                .build()
        ) { response ->
            val reader = response.body?.charStream()?.buffered()
                ?: throw ApiFailure("ChatGPT Responses вернул пустой поток")
            val decoder = SseDecoder()
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = reader.readLine() ?: break
                decoder.feed(line)?.let(::consume)
            }
            decoder.flush()?.let(::consume)
        }

        return completed ?: latestResponse
            ?: throw ApiFailure("ChatGPT Responses завершился без финального response.completed")
    }

    data class OpenAiResponsesImageResult(
        val bytes: ByteArray,
        val mime: String,
        val revisedPrompt: String,
        val responseId: String,
        val imageCallId: String,
        val imageModel: String
    )

    /**
     * Conversational image generation/editing through the Responses API image_generation tool.
     * The top-level model stays the user's selected ChatGPT model; GPT Image is a tool.
     */
    suspend fun generateOpenAiResponsesImage(
        provider: ProviderEntity,
        key: String,
        prompt: String,
        referenceImages: List<LocalAttachment> = emptyList(),
        action: String = "generate",
        requestedSize: String = "1024x1024"
    ): OpenAiResponsesImageResult = withContext(Dispatchers.IO) {
        require(provider.protocol == RESPONSES) { "ChatGPT image generation requires Responses API" }
        require(isOfficialChatGptPlan(provider.providerType)) {
            "OpenAI Responses image_generation доступен только официальному ChatGPT Subscription transport"
        }
        require(provider.baseUrl.toHttpUrlOrNull()?.host == "api.openai.com") {
            "ChatGPT image generation requires the public OpenAI Responses endpoint"
        }
        require(prompt.isNotBlank()) { "Опишите изображение" }
        require(action in setOf("generate", "edit", "auto")) { "Некорректный image action" }

        val content = JSONArray()
            .put(JSONObject().put("type", "input_text").put("text", prompt.take(120_000)))

        val root = File(context.filesDir, "attachments").canonicalFile
        referenceImages
            .asSequence()
            .filter { it.mime in setOf("image/png", "image/jpeg", "image/webp") }
            .take(6)
            .forEach { attachment ->
                val file = runCatching { File(attachment.path).canonicalFile }.getOrNull()
                    ?: throw ApiFailure("Изображение-источник недоступно: ${attachment.name}")
                if (!file.isFile || !file.path.startsWith(root.path + File.separator)) {
                    throw ApiFailure("Изображение-источник недоступно: ${attachment.name}")
                }
                if (file.length() <= 0L || file.length() > 20_000_000L) {
                    throw ApiFailure("Изображение ${attachment.name} должно быть меньше 20 МБ")
                }
                val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                content.put(
                    JSONObject()
                        .put("type", "input_image")
                        .put("image_url", "data:${attachment.mime};base64,$encoded")
                )
            }

        val normalizedSize = requestedSize.takeIf {
            it in setOf("1024x1024", "1024x1536", "1536x1024")
        } ?: "1024x1024"
        val tool = JSONObject()
            .put("type", "image_generation")
            .put("action", if (referenceImages.isNotEmpty() && action == "generate") "edit" else action)
            .put("size", normalizedSize)
        val body = JSONObject()
            .put("model", provider.model)
            .put("store", false)
            .put(
                "input",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", content)
                )
            )
            .put("tools", JSONArray().put(tool))
            .put("tool_choice", JSONObject().put("type", "image_generation"))

        val json = try {
            executeOpenAiResponsesStreamFinal(provider, key, body)
        } catch (error: ApiFailure) {
            when (error.statusCode) {
                401, 403 -> throw ApiFailure(
                    "ChatGPT Subscription не разрешил image_generation для этой сессии/плана. " +
                        "Переподключите ChatGPT и повторите запрос.",
                    error.statusCode
                )
                429 -> throw ApiFailure(
                    "ChatGPT Image: достигнут лимит или временно исчерпана квота.",
                    error.statusCode
                )
                else -> throw error
            }
        }
        val output = json.optJSONArray("output") ?: JSONArray()
        var encoded = ""
        var revised = ""
        var callId = ""
        var actualImageModel = provider.model
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "image_generation_call") continue
            if (encoded.isBlank()) encoded = item.optString("result")
            if (revised.isBlank()) revised = item.optString("revised_prompt")
            if (callId.isBlank()) callId = item.optString("id")
            actualImageModel = item.optString("model").ifBlank { actualImageModel }
        }
        if (encoded.isBlank()) {
            val detail = output.toString().take(500)
            throw ApiFailure(
                if (detail.isBlank() || detail == "[]")
                    "ChatGPT завершил Responses запрос без image_generation_call"
                else "ChatGPT не вернул изображение: $detail"
            )
        }

        val bytes = try {
            Base64.decode(encoded, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            throw ApiFailure("ChatGPT Image вернул повреждённые данные изображения")
        }
        if (bytes.isEmpty() || bytes.size > 40_000_000) {
            throw ApiFailure("ChatGPT Image вернул изображение некорректного размера")
        }

        OpenAiResponsesImageResult(
            bytes = bytes,
            mime = "image/png",
            revisedPrompt = revised,
            responseId = json.optString("id"),
            imageCallId = callId,
            imageModel = actualImageModel
        )
    }

    suspend fun analyzeOpenAiVisualScene(
        provider: ProviderEntity,
        key: String,
        image: LocalAttachment,
        sceneHint: String = ""
    ): String = withContext(Dispatchers.IO) {
        require(provider.protocol == RESPONSES) { "Visual scene analysis requires Responses API" }
        val root = File(context.filesDir, "attachments").canonicalFile
        val file = runCatching { File(image.path).canonicalFile }.getOrNull()
            ?: throw ApiFailure("Изображение для анализа недоступно")
        require(file.isFile && file.path.startsWith(root.path + File.separator)) {
            "Изображение для анализа недоступно"
        }
        require(file.length() in 1..20_000_000L) { "Изображение для анализа слишком большое" }
        val mime = image.mime.takeIf { it.startsWith("image/") } ?: "image/png"
        val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        val analysisPrompt = buildString {
            append(
                "Analyze this image as persistent scene state for later image editing. " +
                    "Return exactly one JSON object and no markdown. Schema: " +
                    "{\"subjects\":[{\"name\":\"short label\",\"description\":\"stable visible attributes and position\"}]," +
                    "\"background\":\"environment/background only\",\"style\":\"rendering/visual style\"," +
                    "\"composition\":\"framing, camera, relative subject positions\",\"lighting\":\"lighting/color temperature\"}. " +
                    "Describe only visible facts. Do not infer identity or hidden attributes. "
            )
            if (sceneHint.isNotBlank()) {
                append("User context, only as a naming hint: ").append(sceneHint.take(1_500))
            }
        }
        val input = JSONArray().put(
            JSONObject()
                .put("role", "user")
                .put(
                    "content",
                    JSONArray()
                        .put(JSONObject().put("type", "input_text").put("text", analysisPrompt))
                        .put(JSONObject().put("type", "input_image")
                            .put("image_url", "data:$mime;base64,$encoded"))
                )
        )
        val body = JSONObject()
            .put("model", provider.model)
            .put("store", false)
            .put("input", input)

        val json = executeOpenAiResponsesStreamFinal(provider, key, body)
        val direct = json.optString("output_text").trim()
        if (direct.isNotBlank()) return@withContext direct
        val output = json.optJSONArray("output") ?: JSONArray()
        val text = StringBuilder()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "message") continue
            val parts = item.optJSONArray("content") ?: continue
            for (j in 0 until parts.length()) {
                val part = parts.optJSONObject(j) ?: continue
                if (part.optString("type") in setOf("output_text", "text")) {
                    text.append(part.optString("text"))
                }
            }
        }
        text.toString().trim().ifBlank {
            throw ApiFailure("ChatGPT scene analysis ответил без текста")
        }
    }

    private fun imageHistoryDebugSummary(history: List<MessageEntity>): String =
        history.joinToString(" | ") { message ->
            val media = attachments(message).joinToString(",") { attachment ->
                attachment.mime + ":" + attachment.origin +
                    if (attachment.replayToModel) ":replay" else ":ui-only"
            }.ifBlank { "none" }
            "role=" + message.role + ",status=" + message.status +
                ",chars=" + message.content.length + ",media=" + media
        }.take(1200)

    private fun providerSessionId(provider: ProviderEntity, history: List<MessageEntity>): String {
        val chatId = history.lastOrNull()?.chatId?.takeIf { it != 0L } ?: provider.id
        if (chatId == 0L) return ""
        if (provider.providerType == LegacySubscriptionIds.PROVIDER_PREFIX +
            SubscriptionProxyStore.ACCOUNT_GEMINI
        ) {
            // Antigravity / Cloud Code expects the same negative decimal session-id
            // shape used by its official client, not an arbitrary UUID.
            return SubscriptionProxyGeminiSupport.stableSessionId(
                "omnichat-gemini-$chatId"
            )
        }
        if (provider.providerType.startsWith(LegacySubscriptionIds.PROVIDER_PREFIX)) {
            return UUID.nameUUIDFromBytes(
                ("omnichat-subscription-proxy-" + provider.providerType + "-" + chatId)
                    .toByteArray(Charsets.UTF_8)
            ).toString()
        }
        return "omnichat-" + chatId.toString().replace("-", "n")
    }

    suspend fun stream(provider: ProviderEntity, key: String, history: List<MessageEntity>,
                       onText: suspend (String) -> Unit,
                       onUsage: suspend (UsageSnapshot) -> Unit = {}): String = withContext(Dispatchers.IO) {
        if (isExperimentalChatGpt(provider.providerType)) {
            return@withContext streamChatGptWeb(provider, key, history, onText, onUsage)
        }
        if (provider.providerType == LegacySubscriptionIds.PROVIDER_PREFIX +
            SubscriptionProxyStore.ACCOUNT_GEMINI
        ) {
            return@withContext streamSubscriptionProxyGemini(provider, key, history, onText, onUsage)
        }
        val protocol = provider.protocol
        if (protocol !in protocols) throw ApiFailure("Неизвестный протокол: $protocol")
        val json = when (protocol) {
            CHAT -> JSONObject().put("model", provider.model).put("stream", true)
                .put("messages", if (provider.providerType == "gigachat") gigaHistory(provider, key, history)
                     else openAiHistory(provider.systemPrompt, history, false)).also {
                    // Many compatible endpoints reject stream_options. Request it for the official API only.
                    if (provider.baseUrl.toHttpUrlOrNull()?.host == "api.openai.com")
                        it.put("stream_options", JSONObject().put("include_usage", true))
                    if (provider.providerType == "gigachat" && history.any { message ->
                            attachments(message).any { attachment -> attachment.mime == "application/pdf" }
                        }) it.put("function_call", "auto")
                }
            RESPONSES -> JSONObject().put("model", provider.model).put("stream", true)
                .put("input", openAiHistory(
                    if (isOpenAiPlanResponses(provider.providerType)
                    ) "" else provider.systemPrompt,
                    history,
                    true
                )).also {
                    if (isOpenAiPlanResponses(provider.providerType)) {
                        it.put("store", false)
                        it.put(
                            "instructions",
                            provider.systemPrompt.ifBlank { "You are OmniChat, a helpful assistant." }
                        )
                    }
                }
            ANTHROPIC -> JSONObject().put("model", provider.model).put("max_tokens", 4096).put("stream", true)
                .put("messages", anthropicHistory(history)).also { if (provider.systemPrompt.isNotBlank()) it.put("system", provider.systemPrompt) }
            GEMINI -> JSONObject().put("contents", geminiHistory(history)).also {
                if (provider.systemPrompt.isNotBlank()) it.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", provider.systemPrompt))))
            }
            else -> JSONObject().put("model", provider.model).put("stream", true)
                .put("messages", ollamaHistory(provider.systemPrompt, history))
        }
        val url = when (protocol) {
            CHAT -> endpoint(provider.baseUrl, protocol, "chat", "completions")
            RESPONSES -> endpoint(provider.baseUrl, protocol, "responses")
            ANTHROPIC -> endpoint(provider.baseUrl, protocol, "messages")
            GEMINI -> endpoint(provider.baseUrl, protocol, "models", "${provider.model.removePrefix("models/")}:streamGenerateContent")
                .newBuilder().addQueryParameter("alt", "sse").build()
            else -> endpoint(provider.baseUrl, protocol, "api", "chat")
        }
        val req = request(url, key, protocol, provider.providerType, provider.authScope, providerSessionId(provider, history)).header("Accept", if (protocol == OLLAMA) "application/x-ndjson" else "text/event-stream")
            .post(json.toString().toRequestBody(jsonType)).build()
        val output = StringBuilder()
        var done = false
        var completionFinished = false
        var inputTokens: Long? = null
        var outputTokens: Long? = null
        suspend fun handle(payload: String) {
            if (payload == "[DONE]") { done = true; return }
            val event = try { JSONObject(payload) } catch (_: Exception) { return }
            if (event.has("error") && !event.isNull("error")) {
                throw ApiFailure(streamErrorMessage(event))
            }
            val usage = when (protocol) {
                RESPONSES -> event.optJSONObject("response")?.optJSONObject("usage")
                GEMINI -> event.optJSONObject("usageMetadata")
                ANTHROPIC -> event.optJSONObject("message")?.optJSONObject("usage") ?: event.optJSONObject("usage")
                else -> event.optJSONObject("usage")
            }
            fun count(vararg keys: String): Long? = usage?.let { u -> keys.firstOrNull { u.has(it) && !u.isNull(it) }?.let { u.optLong(it) } }
            count("prompt_tokens", "input_tokens", "promptTokenCount")?.let { inputTokens = it }
            count("completion_tokens", "output_tokens", "candidatesTokenCount")?.let { outputTokens = it }
            if (protocol == OLLAMA && event.optBoolean("done")) {
                if (event.has("prompt_eval_count")) inputTokens = event.optLong("prompt_eval_count")
                if (event.has("eval_count")) outputTokens = event.optLong("eval_count")
            }
            val addition = when (protocol) {
                CHAT -> {
                    val choice = event.optJSONArray("choices")?.optJSONObject(0)
                    if (choice != null && !choice.isNull("finish_reason")) completionFinished = true
                    choice?.optJSONObject("delta")?.optString("content", "").orEmpty()
                }
                RESPONSES -> {
                    if (event.optString("type") == "response.completed") done = true
                    if (event.optString("type") == "response.failed") {
                        val failure = event.optJSONObject("response")?.optJSONObject("error")
                        val detail = failure?.optString("message").orEmpty()
                        throw ApiFailure(
                            if (detail.isBlank()) "Не удалось завершить ответ модели"
                            else if (isOpenAiPlanResponses(provider.providerType))
                                "ChatGPT/OpenAI: ${detail.take(260)}"
                            else detail.take(260)
                        )
                    }
                    if (event.optString("type") == "response.output_text.delta") event.optString("delta", "") else ""
                }
                ANTHROPIC -> {
                    if (event.optString("type") == "message_stop") done = true
                    if (event.optString("type") == "error") throw ApiFailure("Ошибка генерации Anthropic")
                    if (event.optString("type") == "content_block_delta") event.optJSONObject("delta")?.optString("text", "").orEmpty() else ""
                }
                GEMINI -> {
                    val candidate = event.optJSONArray("candidates")?.optJSONObject(0)
                    if (candidate?.optString("finishReason").orEmpty().isNotEmpty()) done = true
                    val parts = candidate?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                    (0 until parts.length()).joinToString("") { i -> parts.optJSONObject(i)?.optString("text", "").orEmpty() }
                }
                else -> {
                    if (event.optBoolean("done")) done = true
                    event.optJSONObject("message")?.optString("content", "").orEmpty()
                }
            }
            if (addition.isNotEmpty() && addition != "null") { output.append(addition); onText(output.toString()) }
        }
        execute(req) { response ->
            val reader = response.body?.charStream()?.buffered() ?: throw ApiFailure("Пустой ответ сервера")
            if (protocol == OLLAMA) {
                while (!done) {
                    currentCoroutineContext().ensureActive()
                    handle(reader.readLine() ?: break)
                }
            } else {
                val decoder = SseDecoder()
                while (!done) {
                    currentCoroutineContext().ensureActive()
                    decoder.feed(reader.readLine() ?: break)?.let { handle(it) }
                }
                if (!done) decoder.flush()?.let { handle(it) }
            }
        }
        if (!done && !(protocol == CHAT && completionFinished)) throw ApiFailure("Соединение оборвалось до окончания генерации")
        if (output.isEmpty()) throw ApiFailure("Модель не вернула текстовый ответ")
        onUsage(UsageSnapshot(inputTokens, outputTokens))
        output.toString()
    }

    /**
     * Experimental ChatGPT Web Session transport.
     *
     * This intentionally talks to chatgpt.com/backend-api/conversation and never to
     * /backend-api/codex or api.openai.com. It uses the accessToken imported from the
     * user's own /api/auth/session JSON. OmniChat supplies its bounded local context as
     * one web turn, so changing local models/providers never forks the local chat.
     */
    private suspend fun streamChatGptWeb(
        provider: ProviderEntity,
        key: String,
        history: List<MessageEntity>,
        onText: suspend (String) -> Unit,
        onUsage: suspend (UsageSnapshot) -> Unit
    ): String {
        require(key.isNotBlank()) { "Импортируйте свежую ChatGPT Web JSON session" }
        val url = "https://chatgpt.com/backend-api/conversation".toHttpUrlOrNull()
            ?: throw ApiFailure("Некорректный ChatGPT Web endpoint")

        val imageAttachments = history.flatMap(::attachments)
            .filter { it.replayToModel && it.mime.startsWith("image/") }
        if (imageAttachments.isNotEmpty()) {
            throw ApiFailure(
                "ChatGPT Web JSON: передача изображений требует browser-backed upload. " +
                    "Текстовая web-сессия подключена, но media upload в этом experimental transport ещё не активирован."
            )
        }

        val prompt = buildString {
            if (provider.systemPrompt.isNotBlank()) {
                append("SYSTEM CONTEXT:\n")
                append(provider.systemPrompt.take(12_000))
                append("\n\n")
            }
            append("OMNICHAT CONVERSATION CONTEXT:\n")
            history.takeLast(40).forEach { message ->
                val text = message.content.trim()
                if (text.isBlank()) return@forEach
                append(if (message.role == "assistant") "ASSISTANT: " else "USER: ")
                append(text.take(16_000))
                append("\n\n")
            }
            append(
                "Continue the conversation by answering the final USER message only. " +
                    "Do not mention this transcript wrapper."
            )
        }.take(120_000)

        val nowMs = System.currentTimeMillis()
        val zone = TimeZone.getDefault()
        val offsetMinutes = -(zone.getOffset(nowMs) / 60_000)
        val messageId = UUID.randomUUID().toString()
        val parentId = UUID.nameUUIDFromBytes(
            ("omnichat-web-parent-" + (history.lastOrNull()?.chatId ?: 0L))
                .toByteArray(Charsets.UTF_8)
        ).toString()
        val body = JSONObject()
            .put("action", "next")
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("id", messageId)
                        .put("author", JSONObject().put("role", "user"))
                        .put("create_time", nowMs / 1000.0)
                        .put(
                            "content",
                            JSONObject()
                                .put("content_type", "text")
                                .put("parts", JSONArray().put(prompt))
                        )
                )
            )
            .put("parent_message_id", parentId)
            .put("model", provider.model.ifBlank { "auto" })
            .put("timezone", zone.id)
            .put("timezone_offset_min", offsetMinutes)
            .put("history_and_training_disabled", true)
            .put("supports_buffering", true)
            .put("conversation_mode", JSONObject().put("kind", "primary_assistant"))

        val req = request(
            url = url,
            key = key,
            protocol = CHATGPT_WEB,
            providerType = provider.providerType
        )
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody(jsonType))
            .build()

        var currentText = ""
        var done = false
        execute(req) { response ->
            val reader = response.body?.charStream()?.buffered()
                ?: throw ApiFailure("ChatGPT Web вернул пустой поток")
            val decoder = SseDecoder()
            suspend fun consume(payload: String) {
                if (payload == "[DONE]") {
                    done = true
                    return
                }
                val event = runCatching { JSONObject(payload) }.getOrNull() ?: return
                if (event.has("error") && !event.isNull("error")) {
                    throw ApiFailure(streamErrorMessage(event))
                }
                val message = event.optJSONObject("message") ?: return
                if (message.optJSONObject("author")?.optString("role") != "assistant") return
                val content = message.optJSONObject("content") ?: return
                val parts = content.optJSONArray("parts") ?: return
                val text = buildString {
                    for (i in 0 until parts.length()) {
                        val part = parts.opt(i)
                        when (part) {
                            is String -> append(part)
                            is JSONObject -> append(
                                part.optString("text")
                                    .ifBlank { part.optString("content") }
                            )
                        }
                    }
                }.trim()
                if (text.isNotBlank() && text != currentText) {
                    currentText = text
                    onText(currentText)
                }
            }
            while (!done) {
                currentCoroutineContext().ensureActive()
                val line = reader.readLine() ?: break
                decoder.feed(line)?.let { consume(it) }
            }
            if (!done) decoder.flush()?.let { consume(it) }
        }

        if (currentText.isBlank()) {
            throw ApiFailure(
                "ChatGPT Web session не вернула текст. Если сервер требует browser/session-cookie " +
                    "контекст, переподключите experimental web session."
            )
        }
        onUsage(UsageSnapshot(null, null))
        return currentText
    }

    data class NativeToolCall(val id: String, val name: String, val arguments: JSONObject)
    data class NativeAgentTurn(val content: String, val calls: List<NativeToolCall>, val usage: UsageSnapshot, val assistantMessage: JSONObject)

    /** Native function calling; when the provider supports it, tool results are preserved in the API's proper roles. */
    private suspend fun streamSubscriptionProxyGemini(
        provider: ProviderEntity,
        key: String,
        history: List<MessageEntity>,
        onText: suspend (String) -> Unit,
        onUsage: suspend (UsageSnapshot) -> Unit
    ): String {
        require(key.isNotBlank()) { "Войдите в Google Gemini в Subscription Proxy" }
        require(provider.authScope.isNotBlank()) {
            "Subscription Proxy Gemini: отсутствует Google Cloud Code project ID"
        }
        val requestPayload = JSONObject()
            .put("contents", geminiHistory(history))
            .put("sessionId", providerSessionId(provider, history))
        if (provider.systemPrompt.isNotBlank()) {
            requestPayload.put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", provider.systemPrompt))
                )
            )
        }
        val body = JSONObject()
            .put("model", provider.model)
            .put("userAgent", "antigravity")
            .put("requestType", "agent")
            .put("project", provider.authScope)
            .put("requestId", "agent-" + UUID.randomUUID())
            .put("request", requestPayload)

        val url = (provider.baseUrl.trimEnd('/') +
            "/v1internal:streamGenerateContent?alt=sse").toHttpUrlOrNull()
            ?: throw ApiFailure("Некорректный Google Cloud Code endpoint")
        val request = Request.Builder()
            .url(url)
            .apply { SubscriptionProxyGeminiClient.cloudCodeHeaders(this, key) }
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody(jsonType))
            .build()

        val output = StringBuilder()
        var inputTokens: Long? = null
        var outputTokens: Long? = null
        execute(request) { response ->
            val reader = response.body?.charStream()?.buffered()
                ?: throw ApiFailure("Google Cloud Code вернул пустой поток")
            val decoder = SseDecoder()
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = reader.readLine() ?: break
                val payload = decoder.feed(line) ?: continue
                if (payload == "[DONE]") break
                val event = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                val nested = event.optJSONObject("response") ?: event
                val usage = nested.optJSONObject("usageMetadata")
                if (usage != null) {
                    if (usage.has("promptTokenCount"))
                        inputTokens = usage.optLong("promptTokenCount")
                    if (usage.has("candidatesTokenCount"))
                        outputTokens = usage.optLong("candidatesTokenCount")
                }
                val candidates = nested.optJSONArray("candidates") ?: continue
                for (i in 0 until candidates.length()) {
                    val parts = candidates.optJSONObject(i)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts") ?: continue
                    for (j in 0 until parts.length()) {
                        val part = parts.optJSONObject(j) ?: continue
                        if (part.optJSONObject("functionCall") != null) {
                            throw ApiFailure(
                                "Gemini запросил инструмент вместо текста. Включите агентный режим для выполнения functionCall."
                            )
                        }
                        if (part.optBoolean("thought", false)) continue
                        val addition = part.optString("text").orEmpty()
                        if (addition.isNotEmpty()) {
                            output.append(addition)
                            onText(output.toString())
                        }
                    }
                }
            }
            decoder.flush()?.let { payload ->
                val event = runCatching { JSONObject(payload) }.getOrNull()
                val nested = event?.optJSONObject("response") ?: event
                nested?.optJSONObject("usageMetadata")?.let { usage ->
                    if (usage.has("promptTokenCount"))
                        inputTokens = usage.optLong("promptTokenCount")
                    if (usage.has("candidatesTokenCount"))
                        outputTokens = usage.optLong("candidatesTokenCount")
                }
            }
        }
        onUsage(UsageSnapshot(inputTokens, outputTokens))
        if (output.isEmpty()) throw ApiFailure("Google Gemini не вернул текстовый ответ")
        return output.toString()
    }

    suspend fun geminiAgentTurn(
        provider: ProviderEntity,
        key: String,
        history: List<MessageEntity>,
        extraContents: JSONArray,
        tools: JSONArray
    ): NativeAgentTurn = withContext(Dispatchers.IO) {
        require(
            provider.providerType == LegacySubscriptionIds.PROVIDER_PREFIX +
                SubscriptionProxyStore.ACCOUNT_GEMINI
        ) { "Gemini native tools доступны только Subscription Proxy Gemini" }
        require(key.isNotBlank()) { "Войдите в Google Gemini в Subscription Proxy" }
        require(provider.authScope.isNotBlank()) {
            "Subscription Proxy Gemini: отсутствует Google Cloud Code project ID"
        }

        val contents = geminiHistory(history)
        for (i in 0 until extraContents.length()) {
            extraContents.optJSONObject(i)?.let(contents::put)
        }

        val requestPayload = JSONObject()
            .put("contents", contents)
            .put("sessionId", providerSessionId(provider, history))

        if (provider.systemPrompt.isNotBlank()) {
            requestPayload.put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", provider.systemPrompt))
                )
            )
        }

        val geminiTools = geminiFunctionTools(tools)
        if (geminiTools.length() > 0) {
            requestPayload
                .put("tools", geminiTools)
                .put(
                    "toolConfig",
                    JSONObject().put(
                        "functionCallingConfig",
                        JSONObject().put("mode", "AUTO")
                    )
                )
        }

        val body = JSONObject()
            .put("model", provider.model)
            .put("userAgent", "antigravity")
            .put("requestType", "agent")
            .put("project", provider.authScope)
            .put("requestId", "agent-" + UUID.randomUUID())
            .put("request", requestPayload)

        val url = (provider.baseUrl.trimEnd('/') +
            "/v1internal:streamGenerateContent?alt=sse").toHttpUrlOrNull()
            ?: throw ApiFailure("Некорректный Google Cloud Code endpoint")

        val request = Request.Builder()
            .url(url)
            .apply { SubscriptionProxyGeminiClient.cloudCodeHeaders(this, key) }
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody(jsonType))
            .build()

        val visibleText = StringBuilder()
        val assistantParts = JSONArray()
        val calls = linkedMapOf<String, NativeToolCall>()
        var inputTokens: Long? = null
        var outputTokens: Long? = null
        var callSequence = 0

        fun consume(payload: String) {
            if (payload.isBlank() || payload == "[DONE]") return
            val event = runCatching { JSONObject(payload) }.getOrNull() ?: return
            if (event.has("error") && !event.isNull("error")) {
                throw ApiFailure(streamErrorMessage(event))
            }
            val nested = event.optJSONObject("response") ?: event
            val usage = nested.optJSONObject("usageMetadata")
                ?: nested.optJSONObject("cpaUsageMetadata")
            if (usage != null) {
                if (usage.has("promptTokenCount")) inputTokens = usage.optLong("promptTokenCount")
                if (usage.has("candidatesTokenCount")) outputTokens = usage.optLong("candidatesTokenCount")
            }

            val candidates = nested.optJSONArray("candidates") ?: return
            for (i in 0 until candidates.length()) {
                val content = candidates.optJSONObject(i)?.optJSONObject("content") ?: continue
                val parts = content.optJSONArray("parts") ?: continue
                for (j in 0 until parts.length()) {
                    val part = parts.optJSONObject(j) ?: continue
                    val call = part.optJSONObject("functionCall")
                    if (call != null) {
                        val name = call.optString("name")
                        if (name.isBlank()) continue
                        val id = call.optString("id").ifBlank {
                            "gemini-call-" + (++callSequence)
                        }
                        val args = call.optJSONObject("args") ?: JSONObject()
                        calls[id] = NativeToolCall(id, name, args)
                        assistantParts.put(JSONObject(part.toString()))
                        continue
                    }

                    if (part.optBoolean("thought", false)) {
                        assistantParts.put(JSONObject(part.toString()))
                        continue
                    }

                    val text = part.optString("text")
                    if (text.isNotEmpty()) {
                        visibleText.append(text)
                        assistantParts.put(JSONObject(part.toString()))
                    } else if (part.has("thoughtSignature")) {
                        assistantParts.put(JSONObject(part.toString()))
                    }
                }
            }
        }

        execute(request) { response ->
            val reader = response.body?.charStream()?.buffered()
                ?: throw ApiFailure("Google Cloud Code вернул пустой поток")
            val decoder = SseDecoder()
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = reader.readLine() ?: break
                val payload = decoder.feed(line) ?: continue
                if (payload == "[DONE]") break
                consume(payload)
            }
            decoder.flush()?.let(::consume)
        }

        if (visibleText.isEmpty() && calls.isEmpty()) {
            throw ApiFailure("Google Gemini завершил ответ без текста и без functionCall")
        }

        NativeAgentTurn(
            content = visibleText.toString(),
            calls = calls.values.toList(),
            usage = UsageSnapshot(inputTokens, outputTokens),
            assistantMessage = JSONObject()
                .put("role", "model")
                .put("parts", assistantParts)
        )
    }

    suspend fun nativeAgentTurn(provider: ProviderEntity, key: String, history: List<MessageEntity>,
                                extraMessages: JSONArray, tools: JSONArray): NativeAgentTurn = withContext(Dispatchers.IO) {
        require(provider.protocol == CHAT) { "Tool calling currently requires Chat Completions" }
        val messages = if (provider.providerType == "gigachat") gigaHistory(provider, key, history)
                       else openAiHistory(provider.systemPrompt, history, false)
        for (i in 0 until extraMessages.length()) messages.put(extraMessages.getJSONObject(i))
        val body = JSONObject().put("model", provider.model).put("messages", messages)
            .put("stream", false).put("tools", chatCompletionTools(tools)).put("tool_choice", "auto")
        var answer: NativeAgentTurn? = null
        execute(request(endpoint(provider.baseUrl, CHAT, "chat", "completions"), key, CHAT, provider.providerType, provider.authScope,
            providerSessionId(provider, history))
            .post(body.toString().toRequestBody(jsonType)).build()) { response ->
            val json = JSONObject(response.body?.string() ?: "{}")
            val message = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: throw ApiFailure("Модель не вернула ответ агента")
            val toolCalls = message.optJSONArray("tool_calls") ?: JSONArray()
            val calls = (0 until toolCalls.length()).map { i ->
                val entry = toolCalls.getJSONObject(i)
                val fn = entry.getJSONObject("function")
                NativeToolCall(entry.getString("id"), fn.getString("name"), JSONObject(fn.getString("arguments")))
            }
            val usage = json.optJSONObject("usage")
            answer = NativeAgentTurn(message.optString("content", "").takeIf { it != "null" }.orEmpty(), calls,
                UsageSnapshot(usage?.takeIf { it.has("prompt_tokens") }?.optLong("prompt_tokens"),
                    usage?.takeIf { it.has("completion_tokens") }?.optLong("completion_tokens")), message)
        }
        answer ?: throw ApiFailure("Пустой ответ агента")
    }

    /** Native Anthropic tool_use/tool_result turn for Claude Code OAuth. */
    suspend fun anthropicAgentTurn(
        provider: ProviderEntity,
        key: String,
        history: List<MessageEntity>,
        extraMessages: JSONArray,
        tools: JSONArray
    ): NativeAgentTurn = withContext(Dispatchers.IO) {
        require(provider.protocol == ANTHROPIC) { "Anthropic tool calling requires Anthropic protocol" }
        require(
            provider.providerType == LegacySubscriptionIds.PROVIDER_PREFIX +
                SubscriptionProxyStore.ACCOUNT_CLAUDE
        ) { "Anthropic native tools are enabled only for Subscription Proxy Claude" }

        val messages = anthropicHistory(history)
        for (i in 0 until extraMessages.length()) {
            extraMessages.optJSONObject(i)?.let(messages::put)
        }

        val body = JSONObject()
            .put("model", provider.model)
            .put("max_tokens", 8192)
            .put("stream", false)
            .put("messages", messages)
            .put("tools", anthropicFunctionTools(tools))
        if (provider.systemPrompt.isNotBlank()) body.put("system", provider.systemPrompt)

        var answer: NativeAgentTurn? = null
        execute(
            request(
                endpoint(provider.baseUrl, ANTHROPIC, "messages"),
                key,
                ANTHROPIC,
                provider.providerType,
                provider.authScope,
                providerSessionId(provider, history)
            ).post(body.toString().toRequestBody(jsonType)).build()
        ) { response ->
            val json = JSONObject(response.body?.string() ?: "{}")
            val blocks = json.optJSONArray("content") ?: JSONArray()
            val text = StringBuilder()
            val calls = mutableListOf<NativeToolCall>()

            for (i in 0 until blocks.length()) {
                val block = blocks.optJSONObject(i) ?: continue
                when (block.optString("type")) {
                    "text" -> text.append(block.optString("text"))
                    "tool_use" -> {
                        val id = block.optString("id")
                        val name = block.optString("name")
                        val input = when (val raw = block.opt("input")) {
                            is JSONObject -> raw
                            is String -> runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
                            else -> JSONObject()
                        }
                        if (id.isNotBlank() && name.isNotBlank()) {
                            calls += NativeToolCall(id, name, input)
                        }
                    }
                }
            }

            val usage = json.optJSONObject("usage")
            answer = NativeAgentTurn(
                content = text.toString(),
                calls = calls,
                usage = UsageSnapshot(
                    usage?.takeIf { it.has("input_tokens") }?.optLong("input_tokens"),
                    usage?.takeIf { it.has("output_tokens") }?.optLong("output_tokens")
                ),
                assistantMessage = JSONObject()
                    .put("role", "assistant")
                    .put("content", blocks)
            )
        }
        answer ?: throw ApiFailure("Claude вернул пустой ответ агента")
    }

    /**
     * Native Responses API tool turn used by the ChatGPT/Codex subscription.
     * Response items are replayed verbatim in input so function_call_output keeps
     * the original call_id, matching the official Codex conversation protocol.
     */
    suspend fun responsesAgentTurn(
        provider: ProviderEntity,
        key: String,
        history: List<MessageEntity>,
        extraItems: JSONArray,
        tools: JSONArray
    ): NativeAgentTurn = withContext(Dispatchers.IO) {
        require(provider.protocol == RESPONSES) { "Responses tool calling requires Responses API" }
        val input = openAiHistory("", history, true)
        for (i in 0 until extraItems.length()) input.put(extraItems.get(i))
        val body = JSONObject()
            .put("model", provider.model)
            .put("stream", true)
            .put("store", false)
            .put("instructions", provider.systemPrompt.ifBlank { "You are OmniChat, a helpful development assistant." })
            .put("input", input)
            .put("tools", tools)
            .put("tool_choice", "auto")

        var completed: JSONObject? = null
        val outputItems = JSONArray()
        val outputById = linkedMapOf<String, JSONObject>()
        val calls = linkedMapOf<String, NativeToolCall>()
        val text = StringBuilder()

        fun rememberItem(item: JSONObject) {
            val id = item.optString("id")
            if (id.isNotBlank()) outputById[id] = item
            else outputItems.put(item)
        }

        fun parseCall(item: JSONObject) {
            if (item.optString("type") != "function_call") return
            val callId = item.optString("call_id")
            val name = item.optString("name")
            val rawArgs = item.optString("arguments", "{}")
            if (callId.isNotBlank() && name.isNotBlank()) {
                val args = runCatching { JSONObject(rawArgs.ifBlank { "{}" }) }.getOrElse { JSONObject() }
                calls[callId] = NativeToolCall(callId, name, args)
            }
        }

        val req = request(endpoint(provider.baseUrl, RESPONSES, "responses"), key, RESPONSES,
            provider.providerType, provider.authScope, providerSessionId(provider, history))
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody(jsonType)).build()

        execute(req) { response ->
            val reader = response.body?.charStream()?.buffered() ?: throw ApiFailure("Responses API вернул пустой поток")
            val decoder = SseDecoder()
            var done = false

            fun handle(payload: String) {
                if (payload == "[DONE]") { done = true; return }
                val event = runCatching { JSONObject(payload) }.getOrNull() ?: return
                when (event.optString("type")) {
                    "response.output_text.delta" -> text.append(event.optString("delta", ""))
                    "response.output_item.added", "response.output_item.done" -> {
                        val item = event.optJSONObject("item") ?: return
                        rememberItem(item)
                        parseCall(item)
                    }
                    "response.completed" -> {
                        completed = event.optJSONObject("response")
                        done = true
                    }
                    "response.failed" -> {
                        val failure = event.optJSONObject("response")?.optJSONObject("error")
                        throw ApiFailure(failure?.optString("message").orEmpty()
                            .ifBlank { "Responses API не смог завершить агентный ответ" })
                    }
                    "error" -> throw ApiFailure(streamErrorMessage(event))
                }
            }

            while (!done) {
                currentCoroutineContext().ensureActive()
                val line = reader.readLine() ?: break
                decoder.feed(line)?.let { handle(it) }
            }
            if (!done) decoder.flush()?.let { handle(it) }
            if (!done) throw ApiFailure("Соединение Responses API оборвалось до окончания агентного ответа")
        }

        // The completed response is authoritative and contains fully assembled function arguments.
        completed?.optJSONArray("output")?.let { output ->
            outputById.clear()
            while (outputItems.length() > 0) outputItems.remove(0)
            for (i in 0 until output.length()) {
                val item = output.optJSONObject(i) ?: continue
                rememberItem(item)
                parseCall(item)
            }
        }
        outputById.values.forEach { outputItems.put(it) }

        // Some servers emit final text only inside response.completed rather than delta events.
        if (text.isEmpty()) {
            for (i in 0 until outputItems.length()) {
                val item = outputItems.optJSONObject(i) ?: continue
                if (item.optString("type") != "message") continue
                val content = item.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    val part = content.optJSONObject(j) ?: continue
                    if (part.optString("type") == "output_text") text.append(part.optString("text"))
                }
            }
        }

        val usage = completed?.optJSONObject("usage")
        NativeAgentTurn(
            text.toString(),
            calls.values.toList(),
            UsageSnapshot(
                usage?.takeIf { it.has("input_tokens") }?.optLong("input_tokens"),
                usage?.takeIf { it.has("output_tokens") }?.optLong("output_tokens")
            ),
            JSONObject().put("type", "response_items").put("items", outputItems)
        )
    }

    private fun attachments(message: MessageEntity): List<LocalAttachment> = LocalAttachment.fromJson(message.attachmentsJson)

    /** Fail closed for unsupported MIME types rather than silently stripping context. */
    private fun textAndImages(message: MessageEntity, protocol: String): Pair<String, List<Pair<String, String>>> {
        val text = StringBuilder(message.content)
        val images = mutableListOf<Pair<String, String>>()
        val replayableAttachments = if (message.role == "user") {
            attachments(message).filter { it.replayToModel }
        } else {
            emptyList()
        }
        for (attachment in replayableAttachments) {
            val root = File(context.filesDir, "attachments").canonicalFile
            val file = File(attachment.path).canonicalFile
            if (!file.path.startsWith(root.path + File.separator) || !file.isFile) throw ApiFailure("Вложение недоступно: ${attachment.name}")
            if (attachment.mime.startsWith("text/") || attachment.mime == "application/json") {
                if (file.length() > 512_000L) throw ApiFailure("Текстовый файл слишком большой")
                text.append("\n\n--- Файл: ").append(attachment.name).append(" ---\n")
                    .append(file.readText(Charsets.UTF_8)).append("\n--- Конец файла ---")
            } else if (attachment.mime == "application/zip" || attachment.mime == "application/x-zip-compressed" ||
                       attachment.name.endsWith(".zip", ignoreCase = true)) {
                text.append(ArchiveContext.summarize(file, attachment.name))
            } else if (attachment.mime == "application/pdf") {
                // Android's built-in PdfRenderer provides a dependency-free PDF image path.
                // Limit cost and memory; vision-capable models can inspect the page images.
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { pdf ->
                        val count = minOf(pdf.pageCount, 5)
                        if (pdf.pageCount > 5) throw ApiFailure("PDF содержит более 5 страниц. Разделите документ на части.")
                        text.append("\n\nPDF: ").append(attachment.name).append("; страницы переданы в виде изображений.")
                        for (pageNumber in 0 until count) {
                            pdf.openPage(pageNumber).use { page ->
                                val sourceWidth = page.width.coerceAtLeast(1)
                                val width = minOf(sourceWidth * 2, 1600).coerceAtLeast(1)
                                val height = (page.height.toDouble() * width / sourceWidth).toInt().coerceIn(1, 2200)
                                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                try {
                                    bitmap.eraseColor(android.graphics.Color.WHITE)
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    val encoded = ByteArrayOutputStream().use { out ->
                                        bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
                                        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                                    }
                                    images += "image/jpeg" to encoded
                                } finally { bitmap.recycle() }
                            }
                        }
                    }
                }
            } else if (attachment.mime == "image/png" || attachment.mime == "image/jpeg" || attachment.mime == "image/webp") {
                if (protocol == RESPONSES && attachment.mime == "image/webp") throw ApiFailure("Для Responses API используйте PNG/JPEG")
                if (file.length() > 8_000_000L) throw ApiFailure("Изображение превышает 8 МБ")
                images += attachment.mime to Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
            } else if (protocol == GEMINI &&
                (attachment.mime.startsWith("audio/") || attachment.mime.startsWith("video/"))) {
                // Cloud Code's Gemini transport accepts native inlineData just like Gemini.
                // Keep the direct-inline path conservative; larger media needs a remote file API.
                if (file.length() > 20_000_000L)
                    throw ApiFailure("Аудио/видео для Gemini превышает 20 МБ. Используйте более короткий файл.")
                images += attachment.mime to Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
            } else throw ApiFailure("Формат вложения ${attachment.mime} не поддерживается в текущей версии")
        }
        return text.toString() to images
    }

    /** GigaChat uses file IDs in message.attachments, not OpenAI image_url/data URLs. */
    private suspend fun gigaHistory(
        provider: ProviderEntity, key: String, history: List<MessageEntity>
    ): JSONArray {
        val result = JSONArray()
        if (provider.systemPrompt.isNotBlank())
            result.put(JSONObject().put("role", "system").put("content", provider.systemPrompt))
        var totalImages = 0
        history.filter { it.role == "user" || it.role == "assistant" }.forEach { message ->
            val text = StringBuilder(message.content)
            val ids = JSONArray()
            attachments(message)
                .filter { message.role == "user" && it.replayToModel }
                .forEach { attachment ->
                val file = checkedAttachmentFile(attachment)
                when {
                    attachment.mime.startsWith("text/") || attachment.mime == "application/json" -> {
                        if (file.length() > 512_000L) throw ApiFailure("Текстовый файл превышает 512 КБ")
                        text.append("\n\n--- ").append(attachment.name).append(" ---\n")
                            .append(file.readText(Charsets.UTF_8))
                    }
                    attachment.mime == "application/zip" || attachment.mime == "application/x-zip-compressed" ||
                        attachment.name.endsWith(".zip", ignoreCase = true) ->
                        text.append(ArchiveContext.summarize(file, attachment.name))
                    attachment.mime == "image/jpeg" || attachment.mime == "image/png" ||
                        attachment.mime == "image/webp" || attachment.mime == "application/pdf" -> {
                        if (attachment.mime.startsWith("image/")) {
                            totalImages++
                            if (totalImages > 10) throw ApiFailure(
                                "GigaChat принимает не более 10 изображений в запросе. Начните новый чат для следующей группы снимков.")
                        }
                        ids.put(uploadGigaFile(provider, key, attachment, file))
                        text.append("\n[Прикреплён файл: ").append(attachment.name).append("]")
                    }
                    else -> throw ApiFailure("Формат вложения ${attachment.mime} не поддерживается")
                }
            }
            val item = JSONObject().put("role", message.role).put("content", text.toString())
            if (ids.length() > 0) {
                // GigaChat limits each message to a single image. Separate additional
                // files rather than sending an OpenAI-style multipart message.
                item.put("attachments", JSONArray().put(ids.getString(0)))
            }
            result.put(item)
            for (index in 1 until ids.length()) {
                result.put(JSONObject().put("role", message.role)
                    .put("content", "Дополнительный файл к предыдущему сообщению (часть ${index + 1})")
                    .put("attachments", JSONArray().put(ids.getString(index))))
            }
        }
        return result
    }

    private fun checkedAttachmentFile(attachment: LocalAttachment): File {
        val root = File(context.filesDir, "attachments").canonicalFile
        val file = File(attachment.path).canonicalFile
        if (!file.path.startsWith(root.path + File.separator) || !file.isFile)
            throw ApiFailure("Вложение недоступно: ${attachment.name}")
        return file
    }

    /** GigaChat files are private to the user's account. Cache the remote file IDs per credential and local file. */
    private suspend fun uploadGigaFile(
        provider: ProviderEntity, key: String, attachment: LocalAttachment, original: File
    ): String {
        if (original.length() > 8_000_000L) throw ApiFailure("Файл превышает 8 МБ")
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest((key + "|" + provider.authScope).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val cacheKey = listOf(fingerprint, original.path, original.length(), original.lastModified()).joinToString("|")
        gigaUploadedFiles[cacheKey]?.let { return it }
        var upload = original
        var mime = attachment.mime
        var filename = attachment.name
        var temporary: File? = null
        try {
            if (mime == "image/webp") {
                val bitmap = BitmapFactory.decodeFile(original.absolutePath)
                    ?: throw ApiFailure("Не удалось декодировать WEBP")
                temporary = File.createTempFile("giga-", ".jpg", context.cacheDir)
                temporary.outputStream().use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out))
                        throw ApiFailure("Не удалось преобразовать WEBP")
                }
                bitmap.recycle()
                upload = temporary
                mime = "image/jpeg"
                filename = filename.substringBeforeLast('.') + ".jpg"
            }
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("purpose", "general")
                .addFormDataPart("file", filename.take(120), upload.asRequestBody(mime.toMediaType()))
                .build()
            var id = ""
            execute(request(endpoint(provider.baseUrl, CHAT, "files"), key, CHAT,
                provider.providerType, provider.authScope).post(body).build()) { response ->
                id = JSONObject(response.body?.string().orEmpty()).optString("id")
            }
            if (id.isBlank()) throw ApiFailure("GigaChat не вернул ID загруженного файла")
            gigaUploadedFiles[cacheKey] = id
            return id
        } finally { temporary?.delete() }
    }

    private fun openAiHistory(system: String, history: List<MessageEntity>, responses: Boolean): JSONArray = JSONArray().also { arr ->
        if (system.isNotBlank()) arr.put(JSONObject().put("role", if (responses) "developer" else "system").put("content", system))
        history.filter { it.role in listOf("user", "assistant") }.forEach { message ->
            val (text, imgs) = textAndImages(message, if (responses) RESPONSES else CHAT)
            val content: Any = if (imgs.isEmpty()) text else JSONArray().also { parts ->
                parts.put(JSONObject().put("type", if (responses) "input_text" else "text").put("text", text))
                imgs.forEach { (mime, data) ->
                    val uri = "data:$mime;base64,$data"
                    parts.put(if (responses) JSONObject().put("type", "input_image").put("image_url", uri)
                              else JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", uri)))
                }
            }
            arr.put(JSONObject().put("role", message.role).put("content", content))
        }
    }

    private fun anthropicHistory(history: List<MessageEntity>): JSONArray = JSONArray().also { arr ->
        history.filter { it.role in listOf("user", "assistant") }.forEach { msg ->
            val (text, imgs) = textAndImages(msg, ANTHROPIC)
            val parts = JSONArray().put(JSONObject().put("type", "text").put("text", text.ifBlank { " " }))
            imgs.forEach { (mime, data) -> parts.put(JSONObject().put("type", "image")
                .put("source", JSONObject().put("type", "base64").put("media_type", mime).put("data", data))) }
            arr.put(JSONObject().put("role", msg.role).put("content", parts))
        }
    }

    private fun geminiHistory(history: List<MessageEntity>): JSONArray = JSONArray().also { arr ->
        history.filter { it.role in listOf("user", "assistant") }.forEach { msg ->
            val (text, imgs) = textAndImages(msg, GEMINI)
            val parts = JSONArray().put(JSONObject().put("text", text.ifBlank { " " }))
            imgs.forEach { (mime, data) -> parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", mime).put("data", data))) }
            arr.put(JSONObject().put("role", if (msg.role == "assistant") "model" else "user").put("parts", parts))
        }
    }

    private fun ollamaHistory(system: String, history: List<MessageEntity>): JSONArray = JSONArray().also { arr ->
        if (system.isNotBlank()) arr.put(JSONObject().put("role", "system").put("content", system))
        history.filter { it.role in listOf("user", "assistant") }.forEach { msg ->
            val (text, imgs) = textAndImages(msg, OLLAMA)
            val item = JSONObject().put("role", msg.role).put("content", text)
            if (imgs.isNotEmpty()) item.put("images", JSONArray().also { a -> imgs.forEach { a.put(it.second) } })
            arr.put(item)
        }
    }

    private fun streamErrorMessage(event: JSONObject): String {
        val raw = event.opt("error")
        val message = when (raw) {
            is JSONObject -> raw.optString("message")
                .ifBlank { raw.optString("error") }
                .ifBlank { raw.optString("code") }
            is String -> raw
            else -> ""
        }.ifBlank {
            event.optString("message")
                .ifBlank { event.optString("error_description") }
                .ifBlank { event.optString("detail") }
        }.take(260)
        return if (message.isBlank()) "Провайдер вернул ошибку генерации" else "API: $message"
    }

    private fun statusError(code: Int, rawBody: String = ""): ApiFailure {
        val serverMessage = runCatching {
            val json = JSONObject(rawBody)
            val error = json.opt("error")
            when (error) {
                is JSONObject -> error.optString("message")
                    .ifBlank { error.optString("error") }
                    .ifBlank { error.optString("code") }
                is String -> error
                else -> json.optString("message")
                    .ifBlank { json.optString("error_description") }
                    .ifBlank { json.optString("detail") }
            }
        }.getOrDefault("").take(220)
        val message = when {
            serverMessage.isNotBlank() -> "API: $serverMessage (HTTP $code)"
            code in listOf(401, 403) -> "Неверный API-ключ или нет доступа (HTTP $code)"
            code == 404 -> "Модель или адрес API не найдены (HTTP 404)"
            code == 413 -> "Запрос или вложение слишком большие (HTTP 413)"
            code == 429 -> "Превышены лимиты запросов либо закончилась квота (HTTP 429)"
            code in 500..599 -> "Сервер модели недоступен (HTTP $code)"
            else -> "Ошибка API: HTTP $code"
        }
        return ApiFailure(message, code)
    }
}

/** Non-exportable app-private attachment metadata; no full-file content stored in the DB. */
data class LocalAttachment(
    val name: String,
    val mime: String,
    val path: String,
    val size: Long,
    val origin: String = ORIGIN_USER,
    val replayToModel: Boolean = true,
    val displayTitle: String = "",
    val effectivePrompt: String = "",
    val variantIndex: Int = 0
) {
    fun toJson() = JSONObject()
        .put("name", name)
        .put("mime", mime)
        .put("path", path)
        .put("size", size)
        .put("origin", origin)
        .put("replayToModel", replayToModel)
        .put("displayTitle", displayTitle)
        .put("effectivePrompt", effectivePrompt)
        .put("variantIndex", variantIndex)

    companion object {
        const val ORIGIN_USER = "user"
        const val ORIGIN_ASSISTANT_GENERATED = "assistant_generated"

        fun generated(
            name: String,
            mime: String,
            path: String,
            size: Long,
            displayTitle: String = "",
            effectivePrompt: String = "",
            variantIndex: Int = 0
        ) = LocalAttachment(
            name = name,
            mime = mime,
            path = path,
            size = size,
            origin = ORIGIN_ASSISTANT_GENERATED,
            replayToModel = false,
            displayTitle = displayTitle,
            effectivePrompt = effectivePrompt,
            variantIndex = variantIndex
        )

        fun fromJson(raw: String): List<LocalAttachment> = try {
            val items = JSONArray(raw)
            (0 until items.length()).mapNotNull { i -> items.optJSONObject(i)?.let { item ->
                val origin = item.optString("origin").ifBlank { ORIGIN_USER }
                LocalAttachment(
                    name = item.optString("name"),
                    mime = item.optString("mime"),
                    path = item.optString("path"),
                    size = item.optLong("size"),
                    origin = origin,
                    replayToModel = if (item.has("replayToModel")) item.optBoolean("replayToModel", true)
                        else origin != ORIGIN_ASSISTANT_GENERATED,
                    displayTitle = item.optString("displayTitle"),
                    effectivePrompt = item.optString("effectivePrompt"),
                    variantIndex = item.optInt("variantIndex", 0)
                )
            } }
        } catch (_: Exception) { emptyList() }

        fun encode(items: List<LocalAttachment>): String =
            JSONArray().also { a -> items.forEach { a.put(it.toJson()) } }.toString()
    }
}

