package app.githubble.mobile.network

import app.githubble.mobile.agent.ProjectRuntime
import app.githubble.mobile.data.ProviderEntity
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

class AgentProtocolNegotiatorTest {
    private fun frame(json: JSONObject) = "data: $json\n\n"
    private fun reply(wire: String, calls: JSONArray = JSONArray(), text: String = ""): MockResponse {
        val stream = if (wire == ApiGateway.CHAT) {
            val delta = JSONObject().put("content", text)
            if (calls.length() > 0) delta.put("tool_calls", calls)
            frame(JSONObject().put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", delta)))) +
                frame(JSONObject().put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", JSONObject()).put("finish_reason", if (calls.length() > 0) "tool_calls" else "stop")))) + "data: [DONE]\n\n"
        } else {
            val output = JSONArray()
            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i); val fn = call.getJSONObject("function")
                output.put(JSONObject().put("type", "function_call").put("id", "item-$i").put("call_id", call.getString("id")).put("name", fn.getString("name")).put("arguments", fn.getString("arguments")))
            }
            if (text.isNotEmpty()) output.put(JSONObject().put("type", "message").put("role", "assistant").put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text))))
            frame(JSONObject().put("type", "response.completed").put("response", JSONObject().put("status", "completed").put("output", output)))
        }
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(stream)
    }
    private fun call(name: String, args: JSONObject, index: Int = 0) = JSONObject().put("index", index).put("id", "call-$index").put("type", "function").put("function", JSONObject().put("name", name).put("arguments", args.toString()))
    private fun provider(server: MockWebServer) = ProviderEntity(name = "fixture", baseUrl = server.url("/v1").toString(), encryptedApiKey = "", model = "nemotron-fixture", protocol = ApiGateway.RESPONSES)
    private fun gateway() = ApiGateway(OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build())
    private fun probe(gateway: ApiGateway, provider: ProviderEntity) = AgentProtocolNegotiator.Probe { wire, prompt, extra, tools ->
        val items = JSONArray().put(JSONObject().put("role", "user").put("content", prompt))
        for (i in 0 until extra.length()) items.put(extra.get(i))
        gateway.openAiAgentTurn(provider.copy(protocol = wire), "fixture-secret", emptyList(), items, tools)
    }
    private class Fixture(val handler: (RecordedRequest, JSONObject, String, JSONArray) -> MockResponse) : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val body = JSONObject(request.body.readUtf8())
            val wire = if (request.path == "/v1/responses") ApiGateway.RESPONSES else ApiGateway.CHAT
            return handler(request, body, wire, body.optJSONArray("messages") ?: body.optJSONArray("input") ?: JSONArray())
        }
    }
    private fun probeReply(body: JSONObject, wire: String, messages: JSONArray): MockResponse {
        val result = (0 until messages.length()).map { messages.getJSONObject(it) }.lastOrNull { it.optString("role") == "tool" || it.optString("type") == "function_call_output" }
        if (result != null) return reply(wire, text = result.optString(if (wire == ApiGateway.CHAT) "content" else "output"))
        val prompt = (0 until messages.length()).map { messages.getJSONObject(it) }.first { it.optString("role") == "user" }.getString("content")
        val nonce = Regex("nonce ([a-f0-9-]+)").find(prompt)!!.groupValues[1]
        return reply(wire, JSONArray().put(call("hubble_capability_probe", JSONObject().put("nonce", nonce))))
    }

    @Test fun responsesUnsupportedFallsBackAndNativeFileAndRepositoryCallsReachGuardedHost() {
        MockWebServer().use { server ->
            var completed = false
            server.dispatcher = Fixture { request, body, wire, messages ->
                assertEquals("Bearer fixture-secret", request.getHeader("Authorization"))
                assertTrue(body.getBoolean("stream"))
                if (wire == ApiGateway.RESPONSES) MockResponse().setResponseCode(400).setBody("{\"error\":{\"message\":\"responses unsupported\"}}")
                else if (body.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name") == "hubble_capability_probe") probeReply(body, wire, messages)
                else {
                    val results = (0 until messages.length()).map { messages.getJSONObject(it) }.filter { it.optString("role") == "tool" }
                    if (results.isEmpty()) reply(wire, JSONArray()
                        .put(call("hubble_action", JSONObject().put("name", "read_file").put("args", JSONObject().put("path", "README.md")), 0))
                        .put(call("hubble_action", JSONObject().put("name", "repository_info").put("args", JSONObject()), 1)))
                    else {
                        assertEquals(listOf("call-0", "call-1"), results.map { it.getString("tool_call_id") })
                        assertTrue(results[0].getString("content").contains("verified-file"));assertTrue(results[1].getString("content").contains("verified-repo"))
                        completed = true;reply(wire, text = "Files and repository inspected")
                    }
                }
            }
            val completions = NativeOpenAiAgentCompletion(provider(server), "fixture-secret", "owner-endpoint-model-key", gateway(), AgentProtocolNegotiator(), Consumer {}, Runnable {})
            val executed = mutableListOf<String>()
            val answer = ProjectRuntime.analyze(completions, null, null, "owner", "", "main", "", "Inspect files and repository", "", false, false, Runnable {}, object : ProjectRuntime.ToolHost {
                override fun catalog() = "read_file {path}, repository_info {}"
                override fun handles(name: String) = name in setOf("read_file", "repository_info")
                override fun execute(name: String, args: JSONObject): String { executed += name;return if (name == "read_file") "verified-file" else "verified-repo" }
            })
            assertEquals("Files and repository inspected", answer);assertTrue(completed)
            assertEquals(listOf("read_file", "repository_info"), executed);assertEquals(5, server.requestCount)
        }
    }

    @Test fun responsesRoundTripIsPreferredAndOnlyPositiveEvidenceIsCachedWithExpiry() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = Fixture { _, body, wire, messages -> assertEquals(ApiGateway.RESPONSES, wire);probeReply(body, wire, messages) }
            var time = 0L;val resolver = AgentProtocolNegotiator { time };val probe = probe(gateway(), provider(server))
            val candidates = listOf(ApiGateway.RESPONSES, ApiGateway.CHAT)
            assertEquals(ApiGateway.RESPONSES, resolver.resolve("scope-a", candidates, probe));assertEquals(2, server.requestCount)
            resolver.resolve("scope-a", candidates, probe);assertEquals(2, server.requestCount)
            resolver.resolve("scope-b", candidates, probe);assertEquals(4, server.requestCount)
            time += 31 * 60_000;resolver.resolve("scope-a", candidates, probe);assertEquals(6, server.requestCount)
        }
    }

    @Test fun streamingTextWithoutToolsDoesNotQualifyForAgent() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = Fixture { _, _, wire, _ -> reply(wire, text = "hello") }
            val resolver = AgentProtocolNegotiator()
            repeat(2) {
                try { resolver.resolve("no-tools", listOf(ApiGateway.RESPONSES, ApiGateway.CHAT), probe(gateway(), provider(server)));fail("Text-only API was accepted") }
                catch (e: ApiFailure) { assertTrue(e.message!!.contains("не подтверждены")) }
            }
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun authRateLimitAndOutageNeverTriggerProtocolFallback() = runBlocking {
        for (status in listOf(401, 403, 429, 500, 503)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(status).setBody("denied"))
            try { AgentProtocolNegotiator().resolve("error", listOf(ApiGateway.RESPONSES, ApiGateway.CHAT), probe(gateway(), provider(server)));fail("HTTP failure accepted") }
            catch (e: ApiFailure) { assertEquals(status, e.statusCode) }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun cancellationStopsBlockedReadWithoutTryingAnotherProtocol() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val pending=async { AgentProtocolNegotiator().resolve("cancel",listOf(ApiGateway.RESPONSES,ApiGateway.CHAT),probe(gateway(),provider(server))) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5,java.util.concurrent.TimeUnit.SECONDS)) }
            pending.cancel()
            try { withTimeout(2_000) { pending.await() };fail("Cancellation ignored") }
            catch (_: TimeoutCancellationException) { fail("Blocked HTTP read did not cancel in time") }
            catch (_: CancellationException) {}
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun jsonResponseDespiteStreamRequestDoesNotQualify() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{\"choices\":[{\"message\":{\"content\":\"hello\"}}]}")) }
            try { AgentProtocolNegotiator().resolve("json", listOf(ApiGateway.RESPONSES,ApiGateway.CHAT), probe(gateway(), provider(server)));fail("Non-streaming response accepted") }
            catch (e: ApiFailure) { assertTrue(e.message!!.contains("не подтверждены")) }
            assertEquals(2,server.requestCount)
        }
    }

    @Test fun wrongToolResultCannotCreatePositiveEvidence() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = Fixture { _, body, wire, messages ->
                val hasResult = (0 until messages.length()).map { messages.getJSONObject(it) }.any { it.optString("role")=="tool" || it.optString("type")=="function_call_output" }
                if(hasResult)reply(wire,text="made up result") else probeReply(body,wire,messages)
            }
            val resolver=AgentProtocolNegotiator()
            try { resolver.resolve("wrong",listOf(ApiGateway.RESPONSES,ApiGateway.CHAT),probe(gateway(),provider(server)));fail("Invented result accepted") }
            catch (_: ApiFailure) {}
            assertNull(resolver.cached("wrong"));assertEquals(4,server.requestCount)
        }
    }

    @Test fun textualJsonCannotExecuteToolsOnTheNativePath() {
        MockWebServer().use { server ->
            val text="{\"tools\":[{\"name\":\"write_file\",\"args\":{}}]}"
            server.dispatcher=Fixture { _,body,wire,messages ->
                val schema=body.getJSONArray("tools").getJSONObject(0)
                val name=if(wire==ApiGateway.CHAT)schema.getJSONObject("function").getString("name") else schema.getString("name")
                if(name=="hubble_capability_probe")probeReply(body,wire,messages) else reply(wire,text=text)
            }
            val completion=NativeOpenAiAgentCompletion(provider(server),"fixture-secret","text",gateway(),AgentProtocolNegotiator(),Consumer {},Runnable {})
            val answer=ProjectRuntime.analyze(completion,null,null,"owner","","main","","Discuss","",false,false,null,object:ProjectRuntime.ToolHost {
                override fun catalog()="write_file {}"
                override fun handles(name:String)=true
                override fun execute(name:String,args:JSONObject):String=throw AssertionError("Text masquerading as a native tool executed")
            })
            assertEquals(text,answer)
        }
    }

    @Test fun connectionIdentityChangesForAccountEndpointModelProtocolScopeAndCredential() {
        val fields = arrayOf("owner", "provider", "https://fixture/v1", "model", ApiGateway.RESPONSES, "scope", "secret")
        val original = AgentProtocolNegotiator.fingerprint(*fields)
        fields.indices.forEach { index -> val changed = fields.copyOf();changed[index] += "-changed";assertNotEquals(original, AgentProtocolNegotiator.fingerprint(*changed)) }
        assertEquals(64, original.length);assertFalse(original.contains("secret"))
    }
}
