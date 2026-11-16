package app.githubble.mobile.agent;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

/** Atomic metadata snapshots. Workspace files and conversation archives keep their original paths. */
public final class UnifiedAgentStore {
    static final Object LOCK = new Object();
    public interface Persistence {
        String read(String key);
        void write(String key, String value);
    }
    private final Persistence storage;
    public UnifiedAgentStore(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
            .getSharedPreferences("githubble_unified_agent_v2", Context.MODE_PRIVATE);
        storage = new Persistence() {
            public String read(String key) { return prefs.getString(key, ""); }
            public void write(String key, String value) {
                if (!prefs.edit().putString(key, value).commit())
                    throw new IllegalStateException("Не удалось сохранить задачу");
            }
        };
    }
    public UnifiedAgentStore(Persistence storage) { this.storage = java.util.Objects.requireNonNull(storage); }
    static String key(String account) {
        if (account == null || account.isBlank()) throw new IllegalArgumentException("Нет аккаунта");
        return "account_" + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(account.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    JSONObject read(String account) {
        try {
            String raw = storage.read(key(account));
            if (raw == null || raw.isEmpty()) return new JSONObject().put("schema", 2)
                .put("projects", new JSONObject()).put("tasks", new JSONObject());
            JSONObject value = new JSONObject(raw);
            if (value.getInt("schema") != 2) throw new IllegalStateException("Неизвестная схема задач");
            value.getJSONObject("projects"); value.getJSONObject("tasks");
            return value;
        } catch (org.json.JSONException bad) { throw new IllegalStateException("Повреждены метаданные задач", bad); }
    }
    void save(String account, JSONObject state) { storage.write(key(account), state.toString()); }
    /** Must complete before any legacy store performs its own normalization. Never overwrite a backup. */
    public void backup(String account, java.util.Map<String, ?> metadata) {
        synchronized (LOCK) {
            String key = key(account) + "_legacy_backup";
            if (storage.read(key) != null && !storage.read(key).isEmpty()) return;
            String prefix = key(account) + "_";
            JSONObject copy = new JSONObject();
            try {
                for (java.util.Map.Entry<String, ?> e : metadata.entrySet())
                    if (e.getKey().startsWith(prefix)) copy.put(e.getKey(), e.getValue());
                storage.write(key, copy.toString());
            } catch (org.json.JSONException bad) { throw new IllegalStateException(bad); }
        }
    }
    static java.util.Set<String> keys(JSONObject object) {
        java.util.Set<String> result=new java.util.TreeSet<>();
        java.util.Iterator<String> iterator=object.keys();
        while(iterator.hasNext())result.add(iterator.next());
        return result;
    }
    static JSONObject copy(JSONObject value) {
        try { return new JSONObject(value.toString()); }
        catch (org.json.JSONException bad) { throw new IllegalStateException(bad); }
    }
}
