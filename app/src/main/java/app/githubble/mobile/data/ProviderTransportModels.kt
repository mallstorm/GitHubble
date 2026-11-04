package app.githubble.mobile.data

data class ProviderEntity(
    val id: Long = 0,
    val name: String,
    val baseUrl: String,
    val encryptedApiKey: String,
    val model: String,
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val protocol: String = "openai-chat",
    val systemPrompt: String = "",
    val allowLocalHttp: Boolean = false,
    val inputUsdPerMillion: Double = 0.0,
    val outputUsdPerMillion: Double = 0.0,
    /** Stable registry ID (not display name): dispatches vendor-specific auth. */
    val providerType: String = "custom",
    /** GigaChat OAuth scope or Yandex AI Studio folder/project ID. Not a secret. */
    val authScope: String = "",
    /** Last successful per-connection model discovery, persisted for offline picker. */
    val modelsJson: String = "[]"
)

data class MessageEntity(
    val id: Long = 0,
    val chatId: Long,
    val role: String,
    val content: String,
    val status: String = "complete",
    val model: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /** JSON array of attachments. Paths stay private to the application. */
    val attachmentsJson: String = "[]"
)

