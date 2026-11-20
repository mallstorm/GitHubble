package app.githubble.mobile.agent;

import org.json.JSONArray;
import org.json.JSONObject;

/** Responses API and Chat Completions codecs. Selected only for a confirmed endpoint/model/protocol. */
public final class NativeToolTransport implements ModelTransport {
    public enum Protocol { RESPONSES, CHAT_COMPLETIONS, ANTHROPIC_MESSAGES, GEMINI }
    public interface Endpoint { JSONObject send(JSONObject request) throws Exception; }
    private final Protocol protocol;
    private final String model;
    private final Endpoint endpoint;
    public NativeToolTransport(Protocol protocol, String model, Endpoint endpoint) {
        this.protocol=protocol; this.model=model; this.endpoint=endpoint;
    }
    public Turn complete(String instructions, JSONArray tools, JSONArray history) throws Exception {
        if (protocol==Protocol.ANTHROPIC_MESSAGES) return anthropic(instructions,tools,history);
        if (protocol==Protocol.GEMINI) return gemini(instructions,tools,history);
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
        return new JSONObject().put("id",id).put("name",name).put("args",new JSONObject(arguments==null||arguments.trim().isEmpty()?"{}":arguments));
    }

    private static final String KICKOFF="Continue the engineering task using the supplied context.";

    private Turn anthropic(String instructions, JSONArray tools, JSONArray history) throws Exception {
        JSONArray definitions=new JSONArray();
        for (int i=0;i<tools.length();i++) {
            JSONObject t=tools.getJSONObject(i);
            definitions.put(new JSONObject().put("name",t.getString("name")).put("description",t.optString("description"))
                .put("input_schema",t.getJSONObject("parameters")));
        }
        JSONArray messages=new JSONArray().put(new JSONObject().put("role","user").put("content",KICKOFF));
        JSONArray compacted=new JSONArray(AgentContextBuilder.compactNative(history,90000));
        for (int i=0;i<compacted.length();i++) {
            JSONObject record=compacted.getJSONObject(i), response=record.optJSONObject("nativeResponse");
            JSONArray results=record.optJSONArray("results"), content=response==null?null:response.optJSONArray("content");
            if (content==null || results==null) continue;
            messages.put(new JSONObject().put("role","assistant").put("content",content));
            JSONArray blocks=new JSONArray();
            for (int j=0;j<results.length();j++) {
                JSONObject result=results.getJSONObject(j);
                JSONObject block=new JSONObject().put("type","tool_result").put("tool_use_id",result.getString("id"))
                    .put("content",result.toString());
                if (result.has("error")) block.put("is_error",true);
                blocks.put(block);
            }
            messages.put(new JSONObject().put("role","user").put("content",blocks));
        }
        JSONObject body=new JSONObject().put("max_tokens",8192).put("system",instructions).put("messages",messages);
        if (definitions.length()>0) body.put("tools",definitions);
        JSONObject response=endpoint.send(body);
        JSONArray content=response.optJSONArray("content"), calls=new JSONArray();
        StringBuilder answer=new StringBuilder();
        if (content!=null) for (int i=0;i<content.length();i++) {
            JSONObject block=content.getJSONObject(i);
            if ("text".equals(block.optString("type"))) answer.append(block.optString("text"));
            else if ("tool_use".equals(block.optString("type"))) {
                JSONObject input=block.optJSONObject("input");
                calls.put(call(block.getString("id"),block.getString("name"),input==null?new JSONObject():input));
            }
        }
        JSONObject usage=response.optJSONObject("usage");
        return new Turn(answer.toString(),calls,response,usage==null?0:usage.optLong("input_tokens"),usage==null?0:usage.optLong("output_tokens"));
    }

    /** Gemini rejects JSON-Schema keywords outside its OpenAPI subset and empty object schemas. */
    static JSONObject geminiSchema(JSONObject schema) throws org.json.JSONException {
        JSONObject out=new JSONObject();
        java.util.Iterator<String> keys=schema.keys();
        while (keys.hasNext()) {
            String key=keys.next(); Object value=schema.get(key);
            if (java.util.Arrays.asList("additionalProperties","maxLength","minLength","maxItems","minItems","$schema","default","pattern","strict").contains(key)) continue;
            if ("properties".equals(key) && value instanceof JSONObject) {
                JSONObject props=new JSONObject(), source=(JSONObject)value;
                java.util.Iterator<String> names=source.keys();
                while (names.hasNext()) { String n=names.next(); props.put(n,geminiSchema(source.getJSONObject(n))); }
                out.put(key,props);
            } else if ("items".equals(key) && value instanceof JSONObject) out.put(key,geminiSchema((JSONObject)value));
            else out.put(key,value);
        }
        return out;
    }

    private Turn gemini(String instructions, JSONArray tools, JSONArray history) throws Exception {
        JSONArray declarations=new JSONArray();
        for (int i=0;i<tools.length();i++) {
            JSONObject t=tools.getJSONObject(i), parameters=geminiSchema(t.getJSONObject("parameters"));
            JSONObject declaration=new JSONObject().put("name",t.getString("name")).put("description",t.optString("description"));
            JSONObject properties=parameters.optJSONObject("properties");
            if (properties!=null && properties.length()>0) declaration.put("parameters",parameters);
            declarations.put(declaration);
        }
        JSONArray contents=new JSONArray().put(new JSONObject().put("role","user")
            .put("parts",new JSONArray().put(new JSONObject().put("text",KICKOFF))));
        JSONArray compacted=new JSONArray(AgentContextBuilder.compactNative(history,90000));
        for (int i=0;i<compacted.length();i++) {
            JSONObject record=compacted.getJSONObject(i), response=record.optJSONObject("nativeResponse");
            JSONArray results=record.optJSONArray("results"), candidates=response==null?null:response.optJSONArray("candidates");
            JSONObject first=candidates==null?null:candidates.optJSONObject(0), modelContent=first==null?null:first.optJSONObject("content");
            if (modelContent==null || results==null) continue;
            contents.put(modelContent);
            java.util.List<String> names=new java.util.ArrayList<>();
            JSONArray parts=modelContent.optJSONArray("parts");
            if (parts!=null) for (int j=0;j<parts.length();j++) {
                JSONObject fc=parts.getJSONObject(j).optJSONObject("functionCall");
                if (fc!=null) names.add(fc.optString("name"));
            }
            JSONArray replies=new JSONArray();
            for (int j=0;j<results.length();j++) {
                JSONObject result=results.getJSONObject(j);
                String name=result.optString("name",j<names.size()?names.get(j):"");
                if (name.isEmpty() && j<names.size()) name=names.get(j);
                JSONObject payload=result.has("error") ? new JSONObject().put("error",result.optString("error"))
                    : new JSONObject().put("output",result.optString("data"));
                replies.put(new JSONObject().put("functionResponse",new JSONObject().put("name",name).put("response",payload)));
            }
            contents.put(new JSONObject().put("role","user").put("parts",replies));
        }
        JSONObject body=new JSONObject().put("contents",contents)
            .put("systemInstruction",new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("text",instructions))))
            .put("generationConfig",new JSONObject().put("maxOutputTokens",8192));
        if (declarations.length()>0) body.put("tools",new JSONArray().put(new JSONObject().put("functionDeclarations",declarations)));
        JSONObject response=endpoint.send(body);
        JSONArray candidates=response.optJSONArray("candidates"), calls=new JSONArray();
        JSONObject first=candidates==null?null:candidates.optJSONObject(0), content=first==null?null:first.optJSONObject("content");
        StringBuilder answer=new StringBuilder();
        JSONArray parts=content==null?null:content.optJSONArray("parts");
        if (parts!=null) for (int i=0;i<parts.length();i++) {
            JSONObject part=parts.getJSONObject(i);
            JSONObject fc=part.optJSONObject("functionCall");
            if (fc!=null) { JSONObject args=fc.optJSONObject("args"); calls.put(call("call_"+calls.length(),fc.getString("name"),args==null?new JSONObject():args)); }
            else if (part.has("text") && !part.optBoolean("thought")) answer.append(part.optString("text"));
        }
        if (calls.length()==0 && answer.length()==0) throw new IllegalStateException("Gemini не вернул ответ"+(first==null?"":" ("+first.optString("finishReason")+")"));
        JSONObject usage=response.optJSONObject("usageMetadata");
        return new Turn(answer.toString(),calls,response,usage==null?0:usage.optLong("promptTokenCount"),usage==null?0:usage.optLong("candidatesTokenCount"));
    }
    private static JSONObject call(String id,String name,JSONObject args) throws org.json.JSONException {
        if (id.isEmpty() || name.isEmpty()) throw new IllegalArgumentException("Пустой tool call");
        return new JSONObject().put("id",id).put("name",name).put("args",args);
    }
}
