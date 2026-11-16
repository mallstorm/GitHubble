package app.githubble.mobile.agent;

import org.json.JSONArray;
import org.json.JSONObject;

/** Protocol-neutral turn. History retains tool IDs for native function-call correlation. */
public interface ModelTransport {
    final class Turn {
        public final String answer;
        public final JSONArray calls;
        public final JSONObject nativeResponse;
        public final long inputTokens, outputTokens;
        public Turn(String answer, JSONArray calls, JSONObject response, long input, long output) {
            this.answer=answer == null ? "" : answer; this.calls=calls == null ? new JSONArray() : calls;
            nativeResponse=response; inputTokens=Math.max(0,input); outputTokens=Math.max(0,output);
        }
    }
    Turn complete(String instructions, JSONArray tools, JSONArray history) throws Exception;
}
