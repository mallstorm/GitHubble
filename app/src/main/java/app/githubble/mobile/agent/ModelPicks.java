package app.githubble.mobile.agent;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;

/** Favourite and recently used models as "provider|model" keys (pure helpers, no Android). */
public final class ModelPicks {
    private ModelPicks() {}

    public static String key(String provider, String model) { return provider + "|" + model; }

    public static String providerOf(String key) {
        int cut = key.indexOf('|');
        return cut < 0 ? "" : key.substring(0, cut);
    }

    public static String modelOf(String key) {
        int cut = key.indexOf('|');
        return cut < 0 ? key : key.substring(cut + 1);
    }

    public static List<String> parse(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return result;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i);
                if (value.indexOf('|') > 0 && value.length() <= 400 && !result.contains(value)) result.add(value);
            }
        } catch (org.json.JSONException damaged) {
            result.clear();
        }
        return result;
    }

    public static String serialize(List<String> keys) {
        JSONArray array = new JSONArray();
        for (String key : keys) array.put(key);
        return array.toString();
    }

    /** Adds the key when missing, removes it when present. */
    public static List<String> toggle(List<String> keys, String key) {
        List<String> result = new ArrayList<>(keys);
        if (!result.remove(key)) result.add(0, key);
        return result;
    }

    /** Moves the key to the front and keeps at most {@code max} entries. */
    public static List<String> push(List<String> keys, String key, int max) {
        List<String> result = new ArrayList<>(keys);
        result.remove(key);
        result.add(0, key);
        while (result.size() > Math.max(1, max)) result.remove(result.size() - 1);
        return result;
    }
}
