package app.githubble.mobile.agent;

import static org.junit.Assert.*;
import java.util.Map;
import org.junit.Test;

/** Pure deterministic checks: no live credentials, requests, or device side effects. */
public class OmniChatTransportTest {
    @Test public void tenSupportedApiProvidersHaveFixedTlsEndpoints() {
        Map<String, String> providers = OmniChatConnectionStore.supported();
        assertEquals(10, providers.size());
        for (String id : providers.keySet()) {
            String endpoint = OmniChatChatClient.endpoint(id);
            assertTrue("Non-TLS API endpoint for " + id, endpoint.startsWith("https://"));
            assertTrue("Wrong text API: " + id,
                    endpoint.endsWith("/chat/completions") || endpoint.endsWith("/responses"));
            assertFalse("Unexpected userinfo in endpoint: " + id, endpoint.contains("@"));
        }
    }

    @Test public void unsupportedProviderCannotUseArbitraryNetworkHost() {
        for (String id : new String[]{"my-private-server", "http://127.0.0.1", "../admin", "chatgpt-web"}) {
            try {
                OmniChatChatClient.endpoint(id);
                fail("Unknown provider was allowed: " + id);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("не поддерживается"));
            }
        }
    }

    @Test public void providerCatalogMappingIsImmutable() {
        try {
            OmniChatConnectionStore.supported().put("attacker", "http://example.com");
            fail("Provider registry should be immutable");
        } catch (UnsupportedOperationException expected) {
            // Good.
        }
    }

    @Test public void modelsAndResponseEndpointsAreDistinct() {
        assertEquals("https://api.openai.com/v1/responses",
                OmniChatChatClient.endpoint("openai"));
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
                OmniChatChatClient.endpoint("gemini"));
        assertEquals("https://integrate.api.nvidia.com/v1/chat/completions",
                OmniChatChatClient.endpoint("nvidia"));
    }
}
