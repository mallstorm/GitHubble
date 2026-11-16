package app.githubble.mobile.agent;

import org.json.JSONArray;
import org.json.JSONObject;

/** Downgrade only after an explicit rejection of tools; never retry auth, quota, timeout or server failures. */
public final class ToolTransportFallback implements ModelTransport {
    static final class Unsupported extends Exception {}
    private final ModelTransport nativeTools,compatible;
    private boolean disabled;
    public ToolTransportFallback(ModelTransport nativeTools,ModelTransport compatible) {this.nativeTools=nativeTools;this.compatible=compatible;}
    public Turn complete(String instructions,JSONArray tools,JSONArray history)throws Exception {
        if(disabled)return compatible.complete(instructions,tools,history);
        try{return nativeTools.complete(instructions,tools,history);}
        catch(Unsupported unsupported){disabled=true;return compatible.complete(instructions,tools,history);}
    }
    static boolean unsupported(int status,String raw) {
        if(status!=400&&status!=422)return false;
        try{
            JSONObject error=new JSONObject(raw).optJSONObject("error");if(error==null)return false;
            String param=error.optString("param"),code=error.optString("code"),message=error.optString("message").toLowerCase(java.util.Locale.ROOT);
            return ("tools".equals(param)||"tool_choice".equals(param))
                && ("unsupported_parameter".equals(code)||"unknown_parameter".equals(code)
                    ||message.contains("not supported")||message.contains("unsupported"));
        }catch(org.json.JSONException malformed){return false;}
    }
}
