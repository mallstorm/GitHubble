package app.githubble.mobile.network

object SubscriptionProxyStore {
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
