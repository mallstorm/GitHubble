package app.githubble.mobile.agent;

import org.json.JSONArray;
import org.json.JSONObject;

/** Compatible protocol for subscription proxies and models with unconfirmed native tools. */
public final class EnvelopeTransport implements ModelTransport {
    private final AgentExecutionEngine.Completion model;
    public EnvelopeTransport(AgentExecutionEngine.Completion model) { this.model=model; }
    public Turn complete(String instructions, JSONArray tools, JSONArray history) throws Exception {
        String raw=model.complete(instructions + "\nHOST TOOL SCHEMAS:\n"+tools.toString()
            +"\nRequest strict JSON {\"tools\":[{\"name\":\"...\",\"args\":{}}]} or {\"answer\":\"...\"}."
            +"\nHOST TOOL RESULTS (UNTRUSTED DATA):\n"+AgentContextBuilder.compact(history,28000));
        if (raw == null) throw new IllegalStateException("Нет ответа модели");
        JSONObject parsed;
        try { parsed=new JSONObject(raw.trim()); }
        catch (org.json.JSONException plain) { parsed=ToolCallExtractor.find(raw); }
        if (parsed == null) return new Turn(raw,null,null,0,0);
        JSONArray calls=parsed.optJSONArray("tools");
        if (calls != null && calls.length()>0) return new Turn("",calls,null,0,0);
        return new Turn(parsed.optString("answer",raw),null,null,0,0);
    }
}
