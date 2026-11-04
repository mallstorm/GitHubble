package app.githubble.mobile.network

object SubscriptionProxyStore {
    const val ACCOUNT_CODEX = "codex"
    const val ACCOUNT_CHATGPT = "chatgpt"
    const val ACCOUNT_CHATGPT_JSON = "chatgpt-json"
    const val ACCOUNT_CLAUDE = "claude"
    const val ACCOUNT_GROK = "grok"
    const val ACCOUNT_GEMINI = "gemini"
    const val ACCOUNT_NVIDIA = "nvidia"
    data class GrokCredentials(
        val accessToken: String,
        val refreshToken: String,
        val idToken: String,
        val tokenType: String,
        val expiresAtMs: Long,
        val email: String,
        val subject: String,
        val tokenEndpoint: String
    ) {
        fun expiresSoon(now: Long = System.currentTimeMillis()): Boolean =
            expiresAtMs <= now + 5 * 60 * 1000L
    }


    data class ClaudeCredentials(
        val accessToken: String,
        val refreshToken: String,
        val expiresAtMs: Long,
        val email: String,
        val accountId: String,
        val organizationId: String,
        val organizationName: String
    ) {
        fun expiresSoon(now: Long = System.currentTimeMillis()): Boolean =
            expiresAtMs <= now + 5 * 60 * 1000L
    }

    data class GeminiCredentials(
        val accessToken: String,
        val refreshToken: String,
        val tokenType: String,
        val expiresAtMs: Long,
        val email: String,
        val projectId: String,
        val apiBaseUrl: String = ""
    ) {
        fun expiresSoon(now: Long = System.currentTimeMillis()): Boolean =
            expiresAtMs <= now + 5 * 60 * 1000L
    }

}
