package app.githubble.mobile.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/**
 * Universal protocol negotiation for API-key providers. A key or custom endpoint may speak only
 * Chat Completions, only Responses or only Anthropic Messages. When the upstream rejects the
 * configured wire format (not the credentials, the quota or the model), the remaining formats are
 * tried in order and the working one is remembered per (account, provider, model, endpoint).
 * Official OAuth/subscription traffic is never rewritten.
 */
internal object ProtocolResolver {
    private val OFFICIAL_HOSTS = setOf(
        "api.openai.com", "chatgpt.com", "api.anthropic.com", "generativelanguage.googleapis.com",
        "api.githubcopilot.com", "api.kilo.ai", "openrouter.ai"
    )
    private val NOT_A_MISMATCH = listOf(
        "api key", "api_key", "apikey", "unauthorized", "authentication", "invalid_api", "incorrect api",
        "quota", "rate limit", "rate_limit", "insufficient", "balance", "billing", "credit", "payment",
        "model not found", "model_not_found", "does not exist", "no such model", "unknown model",
        "invalid model", "context length", "context_length", "maximum context", "too many tokens"
    )

    fun eligible(providerType: String, baseUrl: String): Boolean {
        if (providerType == "openai-codex" || providerType.startsWith("subscription-proxy-")) return false
        if (providerType in setOf("github-copilot", "kilo-gateway", "openrouter")) return false
        val host = baseUrl.toHttpUrlOrNull()?.host ?: return false
        return host !in OFFICIAL_HOSTS
    }

    fun mismatch(error: ApiFailure): Boolean {
        val message = error.message.orEmpty().lowercase(Locale.ROOT)
        return when (error.statusCode) {
            404, 405, 415, 501 -> true
            400, 422 -> NOT_A_MISMATCH.none { it in message }
            else -> false
        }
    }

    /** Formats to try after [configured] failed, most common first. */
    fun candidates(configured: String): List<String> =
        listOf(ApiGateway.CHAT, ApiGateway.RESPONSES, ApiGateway.ANTHROPIC).filter { it != configured }
}
