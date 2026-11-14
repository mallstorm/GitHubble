package app.githubble.mobile.agent;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class NativeTransportTest {
    private JSONObject response() throws Exception {
        return new JSONObject("{\"output\":[{\"type\":\"function_call\",\"call_id\":\"call1\",\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"a.java\\\"}\"}]}");
    }
    @Test public void responsesRetainsCallIdAndProducesFunctionOutputs() throws Exception {
        java.util.List<JSONObject> sent=new java.util.ArrayList<>();
        NativeToolTransport transport=new NativeToolTransport(NativeToolTransport.Protocol.RESPONSES,"test-model",body->{sent.add(body);return response();});
        ModelTransport.Turn turn=transport.complete("task",new JSONArray(),new JSONArray());assertEquals("call1",turn.calls.getJSONObject(0).getString("id"));
        JSONArray history=new JSONArray().put(new JSONObject().put("nativeResponse",turn.nativeResponse)
            .put("results",new JSONArray().put(new JSONObject().put("id","call1").put("data","source"))));
        transport.complete("task",new JSONArray(),history);
        JSONArray input=sent.get(1).getJSONArray("input");assertEquals("function_call_output",input.getJSONObject(input.length()-1).getString("type"));
        assertFalse(sent.get(1).getBoolean("store"));assertEquals("call1",input.getJSONObject(input.length()-1).getString("call_id"));
    }
    @Test public void chatCompletionsRetainsToolMessages() throws Exception {
        java.util.List<JSONObject> sent=new java.util.ArrayList<>();JSONObject response=new JSONObject("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"id1\",\"type\":\"function\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{}\"}}]}}]}");
        NativeToolTransport t=new NativeToolTransport(NativeToolTransport.Protocol.CHAT_COMPLETIONS,"model",body->{sent.add(body);return response;});
        ModelTransport.Turn turn=t.complete("task",new JSONArray(),new JSONArray());
        t.complete("task",new JSONArray(),new JSONArray().put(new JSONObject().put("nativeResponse",turn.nativeResponse).put("results",new JSONArray().put(new JSONObject().put("id","id1").put("data","ok")))));
        JSONArray messages=sent.get(1).getJSONArray("messages");assertEquals("tool",messages.getJSONObject(messages.length()-1).getString("role"));assertEquals("id1",messages.getJSONObject(messages.length()-1).getString("tool_call_id"));
    }
    @Test public void capabilityNeverLeaksToOtherModelsEndpointsOrProtocols() {
        TransportCapabilities c=new TransportCapabilities();c.confirmed("https://example/v1","model",NativeToolTransport.Protocol.RESPONSES,true);
        assertTrue(c.supports("https://example/v1","model",NativeToolTransport.Protocol.RESPONSES));
        assertFalse(c.supports("https://other/v1","model",NativeToolTransport.Protocol.RESPONSES));assertFalse(c.supports("https://example/v1","other",NativeToolTransport.Protocol.RESPONSES));
        assertFalse(c.supports("https://example/v1","model",NativeToolTransport.Protocol.CHAT_COMPLETIONS));
    }
    @Test public void fallbackOccursOnlyWhenToolsWereExplicitlyRejected() throws Exception {
        String error="{\"error\":{\"param\":\"tools\",\"code\":\"unsupported_parameter\"}}";
        assertTrue(ToolTransportFallback.unsupported(400,error));assertFalse(ToolTransportFallback.unsupported(401,error));
        assertFalse(ToolTransportFallback.unsupported(429,error));assertFalse(ToolTransportFallback.unsupported(500,error));
        assertFalse(ToolTransportFallback.unsupported(400,"{\"error\":{\"param\":\"model\",\"code\":\"unsupported_parameter\"}}"));
        java.util.concurrent.atomic.AtomicInteger nativeCalls=new java.util.concurrent.atomic.AtomicInteger();
        ModelTransport transport=new ToolTransportFallback((prompt,tools,history)->{nativeCalls.incrementAndGet();throw new ToolTransportFallback.Unsupported();},new EnvelopeTransport(prompt->"compatible"));
        assertEquals("compatible",transport.complete("task",new JSONArray(),new JSONArray()).answer);
        transport.complete("task",new JSONArray(),new JSONArray());assertEquals(1,nativeCalls.get());
    }
    @Test public void largeNativeArgumentsKeepCallCorrelationAndOutput() throws Exception {
        JSONObject response=response();response.getJSONArray("output").getJSONObject(0)
            .put("arguments",new JSONObject().put("path","a.java").put("content","x".repeat(200000)).toString());
        JSONArray history=new JSONArray().put(new JSONObject().put("nativeResponse",response)
            .put("results",new JSONArray().put(new JSONObject().put("id","call1").put("name","read_file").put("data","verified-result"))));
        String compact=AgentContextBuilder.compactNative(history,90000);
        assertTrue(compact.contains("call1"));assertTrue(compact.contains("verified-result"));assertTrue(compact.length()<90000);
    }
}
