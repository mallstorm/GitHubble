package app.githubble.mobile.agent;

import org.json.JSONArray;
import org.json.JSONObject;

/** Responses API and Chat Completions codecs. Selected only for a confirmed endpoint/model/protocol. */
public final class NativeToolTransport implements ModelTransport {
    public enum Protocol { RESPONSES, CHAT_COMPLETIONS }
    public interface Endpoint { JSONObject send(JSONObject request) throws Exception; }
    private final Protocol protocol;
    private final String model;
    private final Endpoint endpoint;
    public NativeToolTransport(Protocol protocol, String model, Endpoint endpoint) {
        this.protocol=protocol; this.model=model; this.endpoint=endpoint;
    }
    public Turn complete(String instructions, JSONArray tools, JSONArray history) throws Exception {
        JSONArray definitions=new JSONArray();
        for (int i=0;i<tools.length();i++) {
            JSONObject t=tools.getJSONObject(i);
            JSONObject fn=new JSONObject().put("name",t.getString("name"))
                .put("description",t.optString("description")).put("parameters",t.getJSONObject("parameters"));
            definitions.put(protocol==Protocol.RESPONSES ? fn.put("type","function")
                : new JSONObject().put("type","function").put("function",fn));
        }
        JSONObject body=new JSONObject().put("model",model).put("tools",definitions).put("stream",false);
        JSONArray messages=new JSONArray();
        if (protocol==Protocol.RESPONSES) {
            body.put("instructions",instructions).put("store",false).put("max_output_tokens",8192)
                .put("include",new JSONArray().put("reasoning.encrypted_content"));
            messages.put(new JSONObject().put("role","user").put("content","Continue the engineering task using the supplied context."));
        } else messages.put(new JSONObject().put("role","system").put("content",instructions))
            .put(new JSONObject().put("role","user").put("content","Continue the engineering task using the supplied context."));
        // Compact whole protocol exchanges so every retained function call keeps its output.
        JSONArray compacted=new JSONArray(AgentContextBuilder.compactNative(history,90000));
        for (int i=0;i<compacted.length();i++) {
            JSONObject record=compacted.getJSONObject(i), response=record.optJSONObject("nativeResponse");
            JSONArray results=record.optJSONArray("results");
            if (response==null || results==null) continue;
            if (protocol==Protocol.RESPONSES) {
                JSONArray output=response.optJSONArray("output");
                if (output!=null) for (int j=0;j<output.length();j++) messages.put(output.getJSONObject(j));
                for (int j=0;j<results.length();j++) {
                    JSONObject result=results.getJSONObject(j);
                    messages.put(new JSONObject().put("type","function_call_output").put("call_id",result.getString("id"))
                        .put("output",result.toString()));
                }
            } else {
                messages.put(response.getJSONArray("choices").getJSONObject(0).getJSONObject("message"));
                for (int j=0;j<results.length();j++) {
                    JSONObject result=results.getJSONObject(j);
                    messages.put(new JSONObject().put("role","tool").put("tool_call_id",result.getString("id"))
                        .put("content",result.toString()));
                }
            }
        }
        body.put(protocol==Protocol.RESPONSES ? "input" : "messages",messages);
        JSONObject response=endpoint.send(body);
        JSONArray calls=new JSONArray(); String answer="";
        if (protocol==Protocol.RESPONSES) {
            JSONArray output=response.optJSONArray("output");
            if (output!=null) for (int i=0;i<output.length();i++) {
                JSONObject item=output.getJSONObject(i);
                if ("function_call".equals(item.optString("type"))) calls.put(call(item.getString("call_id"),
                    item.getString("name"),item.getString("arguments")));
            }
            answer=OmniChatChatClient.responseOutput(response);
        } else {
            JSONObject message=response.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
            JSONArray nativeCalls=message.optJSONArray("tool_calls");
            if (nativeCalls!=null) for (int i=0;i<nativeCalls.length();i++) {
                JSONObject item=nativeCalls.getJSONObject(i), fn=item.getJSONObject("function");
                if (!"function".equals(item.optString("type"))) throw new SecurityException("Неизвестный тип tool call");
                calls.put(call(item.getString("id"),fn.getString("name"),fn.getString("arguments")));
            }
            answer=OmniChatChatClient.chatOutput(response);
        }
        JSONObject usage=response.optJSONObject("usage");
        return new Turn(answer,calls,response,usage==null?0:usage.optLong("input_tokens",usage.optLong("prompt_tokens")),
            usage==null?0:usage.optLong("output_tokens",usage.optLong("completion_tokens")));
    }
    private static JSONObject call(String id,String name,String arguments) throws org.json.JSONException {
        if (id.isEmpty() || name.isEmpty()) throw new IllegalArgumentException("Пустой tool call");
        return new JSONObject().put("id",id).put("name",name).put("args",new JSONObject(arguments));
    }
}
