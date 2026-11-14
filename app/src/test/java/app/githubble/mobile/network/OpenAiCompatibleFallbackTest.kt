package app.githubble.mobile.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleFallbackTest {
    private val base = "https://api.keydealer.ru/v1"
    private val unsupported = ApiFailure(
        "API: Для модели не подтверждены обязательные capabilities: responses, responses_streaming (HTTP 400)",
        400
    )

    @Test fun retriesOnlyWhenGatewayRejectsResponsesCapabilities() {
        assertTrue(OpenAiCompatibleFallback.shouldRetryAsChat(
            ApiGateway.RESPONSES, "openai", base, unsupported))
        assertTrue(OpenAiCompatibleFallback.shouldRetryAsChat(
            ApiGateway.RESPONSES, "custom", base,
            ApiFailure("Responses API unsupported", 404)))
        assertFalse(OpenAiCompatibleFallback.shouldRetryAsChat(
            ApiGateway.CHAT, "openai", base, unsupported))
    }

    @Test fun subscriptionAndOfficialOpenAiNeverDowngrade() {
        assertFalse(OpenAiCompatibleFallback.shouldRetryAsChat(
            ApiGateway.RESPONSES, "subscription-proxy-chatgpt", base, unsupported))
        assertFalse(OpenAiCompatibleFallback.shouldRetryAsChat(
            ApiGateway.RESPONSES, "openai-codex", base, unsupported))
        assertFalse(OpenAiCompatibleFallback.shouldRetryAsChat(
            ApiGateway.RESPONSES, "openai", "https://api.openai.com/v1", unsupported))
    }

    @Test fun authenticationQuotasAndUnrelatedErrorsDoNotRetry() {
        for ((message, code) in listOf(
            "Invalid API key" to 401,
            "Not enough credits" to 402,
            "Rate limit exceeded" to 429,
            "Invalid model" to 400,
            "Responses capability not available" to 500
        )) {
            assertFalse(OpenAiCompatibleFallback.shouldRetryAsChat(
                ApiGateway.RESPONSES, "openai", base, ApiFailure(message, code)))
        }
    }

    @Test fun fullEndpointIsNormalizedWhenSwitchingProtocols() {
        assertEquals(
            "https://api.keydealer.ru/v1/chat/completions",
            ApiGateway.endpoint("$base/responses", ApiGateway.CHAT, "chat", "completions").toString()
        )
        assertEquals(
            "https://api.keydealer.ru/v1/responses",
            ApiGateway.endpoint("$base/chat/completions", ApiGateway.RESPONSES, "responses").toString()
        )
        assertEquals(
            "https://api.keydealer.ru/v1/models",
            ApiGateway.endpoint("$base/chat/completions", ApiGateway.CHAT, "models").toString()
        )
    }
}
