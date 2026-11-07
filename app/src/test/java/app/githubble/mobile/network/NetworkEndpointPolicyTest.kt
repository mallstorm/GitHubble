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
    fun rejectsCredentialsQueryAndFragmentsInConfiguredEndpoints() {
        for(value in listOf("https://user:pass@example.com/v1","https://example.com/v1?key=test","https://example.com/v1#fragment")) {
            org.junit.Assert.assertTrue(runCatching { NetworkEndpointPolicy.requireApiUrl(value.toHttpUrl()) }.isFailure)
        }
    }
}
