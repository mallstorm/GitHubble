package app.githubble.mobile.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkEndpointPolicyTest {
    @Test
    fun allowsLocalHttpForProxyAndOllamaStyleTransports() {
        NetworkEndpointPolicy.requireApiUrl("http://127.0.0.1:8787/v1".toHttpUrl(), allowLocalHttp = true)
        NetworkEndpointPolicy.requireApiUrl("http://localhost:8787/v1".toHttpUrl(), allowLocalHttp = true)
    }

    @Test(expected = ApiFailure::class)
    fun rejectsRemoteHttpEvenWhenLocalHttpIsEnabled() {
        NetworkEndpointPolicy.requireApiUrl("http://example.com/v1".toHttpUrl(), allowLocalHttp = true)
    }

    @Test
    fun apiGatewayChatEndpointAcceptsLocalCliProxy() {
        val url = ApiGateway.endpoint(
            SubscriptionProxyStore.DEFAULT_EXTERNAL_BASE,
            ApiGateway.CHAT,
            "chat",
            "completions"
        )
        assertEquals("127.0.0.1", url.host)
        assertEquals("/v1/chat/completions", url.encodedPath)
    }
}

