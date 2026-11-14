package app.githubble.mobile.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/**
 * Retry a third-party OpenAI-compatible Responses request as Chat Completions only
 * when the upstream explicitly rejects the Responses protocol. A bad credential,
 * exhausted quota, invalid model or server outage must never trigger a retry.
 *
 * Official ChatGPT/Codex subscription traffic is intentionally excluded.
 */
internal object OpenAiCompatibleFallback {
    fun shouldRetryAsChat(
        protocol: String,
        providerType: String,
        baseUrl: String,
        error: ApiFailure
    ): Boolean {
        if (protocol != ApiGateway.RESPONSES) return false
        if (providerType == "openai-codex" || providerType.startsWith("subscription-proxy-")) return false
        val host = baseUrl.toHttpUrlOrNull()?.host ?: return false
        if (host == "api.openai.com" || host == "chatgpt.com") return false
        if (error.statusCode !in setOf(400, 404, 405, 422, 501)) return false

        val message = error.message.orEmpty().lowercase(Locale.ROOT)
        if ("responses_streaming" in message) return true
        if ("responses" !in message) return false
        return listOf(
            "capabilit", "не подтвержд", "не поддерж", "unsupported",
            "not supported", "does not support", "unknown endpoint",
            "endpoint not found", "not implemented"
        ).any { it in message }
    }
}
