package app.githubble.mobile.agent;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class NativeProtocolsTest {
    private static JSONArray tools() throws Exception {
        return new JSONArray().put(new JSONObject().put("name", "read_file").put("description", "d")
            .put("parameters", new JSONObject().put("type", "object").put("additionalProperties", false)
                .put("properties", new JSONObject().put("path", new JSONObject().put("type", "string").put("maxLength", 600)))
                .put("required", new JSONArray().put("path"))));
    }

    @Test public void anthropicToolUseRoundTrip() throws Exception {
        List<JSONObject> sent = new ArrayList<>();
        JSONObject response = new JSONObject("{\"content\":[{\"type\":\"text\",\"text\":\"ok\"},{\"type\":\"tool_use\",\"id\":\"tu1\",\"name\":\"read_file\",\"input\":{\"path\":\"a.java\"}}],\"usage\":{\"input_tokens\":7,\"output_tokens\":3}}");
        NativeToolTransport t = new NativeToolTransport(NativeToolTransport.Protocol.ANTHROPIC_MESSAGES, "claude", body -> { sent.add(body); return response; });
        ModelTransport.Turn turn = t.complete("sys", tools(), new JSONArray());
        assertEquals("tu1", turn.calls.getJSONObject(0).getString("id"));
        assertEquals("a.java", turn.calls.getJSONObject(0).getJSONObject("args").getString("path"));
        assertEquals(7, turn.inputTokens);
        assertTrue(sent.get(0).getJSONArray("tools").getJSONObject(0).has("input_schema"));
        JSONArray history = new JSONArray().put(new JSONObject().put("nativeResponse", turn.nativeResponse)
            .put("results", new JSONArray().put(new JSONObject().put("id", "tu1").put("name", "read_file").put("data", "src"))));
        t.complete("sys", tools(), history);
        JSONArray messages = sent.get(1).getJSONArray("messages");
        JSONObject last = messages.getJSONObject(messages.length() - 1);
        assertEquals("user", last.getString("role"));
        assertEquals("tool_result", last.getJSONArray("content").getJSONObject(0).getString("type"));
        assertEquals("tu1", last.getJSONArray("content").getJSONObject(0).getString("tool_use_id"));
        assertEquals("assistant", messages.getJSONObject(messages.length() - 2).getString("role"));
    }

    @Test public void geminiFunctionCallRoundTripAndSchemaSanitizing() throws Exception {
        List<JSONObject> sent = new ArrayList<>();
        JSONObject response = new JSONObject("{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"name\":\"read_file\",\"args\":{\"path\":\"a.java\"}}}]}}],\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":2}}");
        NativeToolTransport t = new NativeToolTransport(NativeToolTransport.Protocol.GEMINI, "gemini-x", body -> { sent.add(body); return response; });
        ModelTransport.Turn turn = t.complete("sys", tools(), new JSONArray());
        assertEquals("call_0", turn.calls.getJSONObject(0).getString("id"));
        JSONObject parameters = sent.get(0).getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations").getJSONObject(0).getJSONObject("parameters");
        assertFalse(parameters.has("additionalProperties"));
        assertFalse(parameters.getJSONObject("properties").getJSONObject("path").has("maxLength"));
        JSONArray history = new JSONArray().put(new JSONObject().put("nativeResponse", turn.nativeResponse)
            .put("results", new JSONArray().put(new JSONObject().put("id", "call_0").put("name", "read_file").put("data", "src"))));
        t.complete("sys", tools(), history);
        JSONArray contents = sent.get(1).getJSONArray("contents");
        JSONObject reply = contents.getJSONObject(contents.length() - 1);
        assertEquals("user", reply.getString("role"));
        assertEquals("read_file", reply.getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse").getString("name"));
    }

    @Test public void adaptiveUsesNativeOnlyAfterSuccessfulProbeAndCachesVerdict() throws Exception {
        AtomicInteger nativeCalls = new AtomicInteger(), fallbackCalls = new AtomicInteger();
        java.util.Map<String, Boolean> store = new java.util.HashMap<>();
        ModelTransport nativeTools = (i, t, h) -> {
            nativeCalls.incrementAndGet();
            JSONArray calls = new JSONArray();
            if (t.length() == 1 && "probe_ping".equals(t.getJSONObject(0).getString("name")))
                calls.put(new JSONObject().put("id", "p").put("name", "probe_ping").put("args", new JSONObject()));
            return new ModelTransport.Turn("native", calls, null, 0, 0);
        };
        ModelTransport fallback = (i, t, h) -> { fallbackCalls.incrementAndGet(); return new ModelTransport.Turn("env", null, null, 0, 0); };
        AdaptiveToolTransport.Cache cache = new AdaptiveToolTransport.Cache() {
            public Boolean get(String k) { return store.get(k); }
            public void put(String k, boolean v) { store.put(k, v); }
        };
        AdaptiveToolTransport transport = new AdaptiveToolTransport(nativeTools, fallback, cache, "k");
        assertEquals("native", transport.complete("x", tools(), new JSONArray()).answer);
        assertEquals(Boolean.TRUE, store.get("k"));
        assertEquals(0, fallbackCalls.get());
    }

    @Test public void adaptiveFallsBackWhenToolsIgnoredOrRejectedButNotOnAuthErrors() throws Exception {
        java.util.Map<String, Boolean> store = new java.util.HashMap<>();
        AdaptiveToolTransport.Cache cache = new AdaptiveToolTransport.Cache() {
            public Boolean get(String k) { return store.get(k); }
            public void put(String k, boolean v) { store.put(k, v); }
        };
        ModelTransport fallback = (i, t, h) -> new ModelTransport.Turn("env", null, null, 0, 0);
        ModelTransport ignoring = (i, t, h) -> new ModelTransport.Turn("plain text", null, null, 0, 0);
        assertEquals("env", new AdaptiveToolTransport(ignoring, fallback, cache, "a").complete("x", tools(), new JSONArray()).answer);
        assertEquals(Boolean.FALSE, store.get("a"));
        ModelTransport unauthorized = (i, t, h) -> { throw new app.githubble.mobile.network.ApiFailure("Invalid API key", 401); };
        assertEquals("env", new AdaptiveToolTransport(unauthorized, fallback, cache, "b").complete("x", tools(), new JSONArray()).answer);
        assertNull(store.get("b"));
        assertTrue(AdaptiveToolTransport.rejected(new app.githubble.mobile.network.ApiFailure("tools are not supported", 400)));
        assertFalse(AdaptiveToolTransport.rejected(new app.githubble.mobile.network.ApiFailure("insufficient balance", 400)));
    }
}
