package app.githubble.mobile.agent;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Native tool calling when the exact endpoint/model/protocol is proven to support it, JSON envelope otherwise.
 * A tiny one-time probe (the model must call a ping tool) decides; the verdict is cached by the caller.
 * Only an explicit rejection of tools downgrades; auth, quota, network and server errors never change the mode.
 */
public final class AdaptiveToolTransport implements ModelTransport {
    public interface Cache {
        /** true = proven, false = rejected recently, null = unknown. */
        Boolean get(String key);
        void put(String key, boolean supported);
    }

    private final ModelTransport nativeTools, fallback;
    private final Cache cache;
    private final String key;
    private boolean decided, useNative;

    public AdaptiveToolTransport(ModelTransport nativeTools, ModelTransport fallback, Cache cache, String key) {
        this.nativeTools = nativeTools; this.fallback = fallback; this.cache = cache; this.key = key;
    }

    public boolean usingNative() { return decided && useNative; }

    @Override
    public Turn complete(String instructions, JSONArray tools, JSONArray history) throws Exception {
        if (!decided) {
            Boolean known = cache.get(key);
            if (known == null) {
                known = probe();
                if (known != null) cache.put(key, known);
            }
            useNative = known != null && known;
            decided = true;
        }
        if (!useNative) return fallback.complete(instructions, tools, history);
        try {
            return nativeTools.complete(instructions, tools, history);
        } catch (Exception failure) {
            if (!rejected(failure)) throw failure;
            cache.put(key, false);
            useNative = false;
            return fallback.complete(instructions, tools, history);
        }
    }

    /** @return true when the model called the ping tool, false when tools are rejected/ignored, null when unknown. */
    private Boolean probe() {
        try {
            JSONArray tools = new JSONArray().put(new JSONObject().put("name", "probe_ping")
                .put("description", "Connectivity probe. Call it once.")
                .put("parameters", new JSONObject().put("type", "object")
                    .put("properties", new JSONObject().put("value", new JSONObject().put("type", "string")))
                    .put("required", new JSONArray().put("value"))));
            Turn turn = nativeTools.complete("Call the tool probe_ping exactly once with value \"ok\". Do not answer in text.", tools, new JSONArray());
            for (int i = 0; i < turn.calls.length(); i++)
                if ("probe_ping".equals(turn.calls.getJSONObject(i).optString("name"))) return Boolean.TRUE;
            return Boolean.FALSE;
        } catch (Exception failure) {
            return rejected(failure) ? Boolean.FALSE : null;
        }
    }

    /** Explicit "tools/format not supported" answers only. */
    static boolean rejected(Throwable failure) {
        if (failure instanceof ToolTransportFallback.Unsupported) return true;
        if (!(failure instanceof app.githubble.mobile.network.ApiFailure)) return false;
        int status = ((app.githubble.mobile.network.ApiFailure) failure).getStatusCode();
        String message = String.valueOf(failure.getMessage()).toLowerCase(java.util.Locale.ROOT);
        for (String other : new String[] {"api key", "unauthorized", "quota", "rate limit", "insufficient", "balance",
                "billing", "model not found", "does not exist", "no such model", "unknown model", "invalid model", "context length"})
            if (message.contains(other)) return false;
        return status == 400 || status == 404 || status == 405 || status == 415 || status == 422 || status == 501;
    }
}
