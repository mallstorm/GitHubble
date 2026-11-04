package app.githubble.mobile.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeProviderRestoreTest {
    @Test fun chatGptDirectOAuthCatalogUsesPublicApiAndBothServerFormats()=runBlocking {
        val server=MockWebServer();server.start()
        try{
            val client=ChatGptPlanClient(apiBaseUrl=server.url("/v1").toString().trimEnd('/'),client=OkHttpClient())
            val creds=ChatGptPlanClient.Credentials("oaiapp_fixture","fixture-access","fixture-refresh","","Bearer",Long.MAX_VALUE,setOf("chatgpt.tokens.use.direct"),"sub","","","","","public-responses")
            for(body in listOf("""{"data":[{"id":"server-model-a"},{"id":"server-model-b"}]}""","""{"models":[{"slug":"server-model-a","visibility":"list"},{"slug":"server-model-b"}]}""")){
                server.enqueue(MockResponse().setBody(body))
                assertEquals(listOf("server-model-a","server-model-b"),client.modelCatalog(creds))
                val req=server.takeRequest();assertEquals("/v1/models",req.path);assertEquals("Bearer fixture-access",req.getHeader("Authorization"))
                assertFalse(req.path!!.contains("codex"))
            }
            server.enqueue(MockResponse().setBody("{\"data\":[]}"))
            assertTrue(runCatching{client.modelCatalog(creds)}.isFailure)
        }finally{server.shutdown()}
    }
    @Test fun geminiCatalogKeepsServerAuthorizedNamesWithoutFamilyAllowlist(){
        val ids=SubscriptionProxyGeminiClient.serverModelIds(JSONObject("""{"models/gemini-3-pro":{},"claude-server-enabled":{},"future-family-image":{},"internal-server-enabled":{}}"""))
        assertEquals(listOf("claude-server-enabled","future-family-image","internal-server-enabled","models/gemini-3-pro"),ids)
    }
    @Test fun providerTemplatesChooseNativeProtocolsAndSupportPrivateEndpoints(){
        val catalog=NativeProviderCatalog.parse("""[{"id":"anthropic","name":"Claude","auth_type":"x-api-key"},{"id":"gemini","auth_type":"bearer"},{"id":"ollama","auth_type":"none"},{"id":"v","auth_type":"bearer","base_url":"https://example.org/v1","protocols":["responses"]}]""")
        assertEquals(ApiGateway.ANTHROPIC,catalog.getValue("anthropic").protocol)
        assertEquals(ApiGateway.GEMINI,catalog.getValue("gemini").protocol)
        assertEquals(ApiGateway.RESPONSES,catalog.getValue("v").protocol)
        assertTrue(catalog.getValue("ollama").base.startsWith("http://127.0.0.1:"))
        assertTrue(catalog.containsKey("proxy-external"))
        assertEquals(ApiGateway.ANTHROPIC,NativeProviderCatalog.wireForModel("opencode-zen","claude-server",ApiGateway.CHAT))
        assertEquals(ApiGateway.RESPONSES,NativeProviderCatalog.wireForModel("opencode-go","gpt-server",ApiGateway.CHAT))
    }
    @Test fun proxyAliasesRouteToNativeAccountsAndKeepTheirOwnCredentialIds(){
        for((alias,native) in mapOf("proxy-chatgpt" to "chatgpt-plan","proxy-gemini" to "gemini-antigravity","proxy-claude" to "claude-subscription","proxy-grok" to "grok-subscription")){
            assertTrue(NativeSubscriptionRuntime.supports(alias));assertEquals(native,NativeSubscriptionRuntime.base(alias));assertNotEquals(alias,native)
        }
        assertFalse(NativeSubscriptionRuntime.supports("openai-codex"))
    }
}
