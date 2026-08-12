package app.githubble.mobile.database;

import org.json.JSONArray;
import org.json.JSONObject;

/** Declares database versions included in the 1.0 release candidate. */
public final class SchemaMigrationRegistry {
    public static final int TRACKING_SCHEMA = 2;
    public static final int OFFLINE_QUEUE_SCHEMA = 2;
    private SchemaMigrationRegistry() { }

    public static JSONObject matrix() {
        JSONObject root = new JSONObject();
        try {
            root.put("tracking_schema", TRACKING_SCHEMA);
            root.put("offline_queue_schema", OFFLINE_QUEUE_SCHEMA);
            root.put("supported_upgrade_paths", new JSONArray()
                    .put("tracking:1->2:additive")
                    .put("offline_queue:1->2:additive"));
            root.put("destructive_migrations", false);
        } catch (Exception ignored) { }
        return root;
    }
}
