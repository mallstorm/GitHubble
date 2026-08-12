package app.githubble.mobile.search;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Small local history of explicit search submissions. No token or repository private data is stored here. */
public final class SmartSearchHistory {
    private static final String KEY = "smart_search_history_v1";
    private static final int MAX = 8;
    private final SharedPreferences prefs;

    public SmartSearchHistory(SharedPreferences prefs) { this.prefs = prefs; }

    public static final class Entry {
        public final String query;
        public final boolean smart;
        public final long at;
        Entry(String query, boolean smart, long at) { this.query = query; this.smart = smart; this.at = at; }
    }

    public void remember(String query, boolean smart) {
        String value = query == null ? "" : query.trim();
        if (value.isEmpty()) return;
        ArrayList<Entry> next = new ArrayList<>();
        next.add(new Entry(value, smart, System.currentTimeMillis()));
        for (Entry e : recent()) {
            if (!e.query.equalsIgnoreCase(value) && next.size() < MAX) next.add(e);
        }
        JSONArray array = new JSONArray();
        try {
            for (Entry e : next) array.put(new JSONObject().put("q", e.query).put("smart", e.smart).put("at", e.at));
            prefs.edit().putString(KEY, array.toString()).apply();
        } catch (Exception ignored) {}
    }

    public List<Entry> recent() {
        ArrayList<Entry> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < array.length() && out.size() < MAX; i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) continue;
                String q = o.optString("q", "").trim();
                if (!q.isEmpty()) out.add(new Entry(q, o.optBoolean("smart", false), o.optLong("at", 0)));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public void clear() { prefs.edit().remove(KEY).apply(); }
}
