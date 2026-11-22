package app.githubble.mobile.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolResolverTest {
    @Test fun protocolErrorsTriggerNegotiation() {
        assertTrue(ProtocolResolver.mismatch(ApiFailure("Unknown endpoint /responses", 404)))
        assertTrue(ProtocolResolver.mismatch(ApiFailure("Extra inputs are not permitted: input", 422)))
    }

    @Test fun credentialQuotaAndModelErrorsNeverTriggerNegotiation() {
        assertFalse(ProtocolResolver.mismatch(ApiFailure("Invalid API key", 401)))
        assertFalse(ProtocolResolver.mismatch(ApiFailure("insufficient balance", 400)))
        assertFalse(ProtocolResolver.mismatch(ApiFailure("model not found", 400)))
        assertFalse(ProtocolResolver.mismatch(ApiFailure("rate limit", 429)))
    }

    @Test fun officialAndSubscriptionEndpointsAreNeverRewritten() {
        assertFalse(ProtocolResolver.eligible("subscription-proxy-chatgpt", "https://keydealer.example/v1"))
        assertFalse(ProtocolResolver.eligible("custom", "https://api.openai.com/v1"))
        assertTrue(ProtocolResolver.eligible("custom", "https://keydealer.example/v1"))
    }

    @Test fun configuredProtocolIsNotRetriedFirst() {
        assertEquals(listOf("openai-chat", "anthropic"), ProtocolResolver.candidates("openai-responses"))
        assertEquals(listOf("openai-responses", "anthropic"), ProtocolResolver.candidates("openai-chat"))
    }
}
