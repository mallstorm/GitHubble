package app.githubble.mobile.agent;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Finds a {"tools":[...]} request inside a model reply that mixes prose and JSON
 * (or wraps the JSON in a code fence). Without this the loop treated such a reply
 * as the final answer, showed raw JSON to the user and stopped before running the tools.
 */
final class ToolCallExtractor {
    private ToolCallExtractor() { }

    /** Returns the first object that has a non-empty "tools" array, or null. */
    static JSONObject find(String raw) {
        if (raw == null) return null;
        int from = 0;
        while (true) {
            int start = raw.indexOf('{', from);
            if (start < 0) return null;
            int end = matching(raw, start);
            if (end > start) {
                try {
                    JSONObject candidate = new JSONObject(raw.substring(start, end + 1));
                    JSONArray tools = candidate.optJSONArray("tools");
                    if (tools != null && tools.length() > 0) return candidate;
                } catch (Exception ignored) { }
            }
            from = start + 1;
        }
    }

    /** Index of the brace closing the one at start, honouring JSON strings; -1 when unbalanced. */
    private static int matching(String s, int start) {
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
            } else if (c == '"') inString = true;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }
}
