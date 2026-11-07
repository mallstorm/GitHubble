package app.githubble.mobile.privacy;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import app.githubble.mobile.backup.PortableSettingsManager;
import app.githubble.mobile.auth.GitHubAccountStore;
import app.githubble.mobile.cache.EntityCache;
import app.githubble.mobile.offline.OfflineActionQueue;
import app.githubble.mobile.security.SecureStore;
import app.githubble.mobile.tracking.TrackingDatabase;
import app.githubble.mobile.translation.TranslationCache;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;

/** User-controlled local data export and deletion. Authentication secrets are never exported. */
public final class UserDataManager {
    private final Context context;
    private final SharedPreferences prefs;
    private final SecureStore secureStore;
    private final EntityCache entityCache;
    private final TranslationCache translationCache;
    private final TrackingDatabase trackingDatabase;
    private final OfflineActionQueue offlineQueue;
    private final PortableSettingsManager portableSettings;

    public UserDataManager(Context context, SharedPreferences prefs, SecureStore secureStore,
                           EntityCache entityCache, TranslationCache translationCache,
                           TrackingDatabase trackingDatabase, OfflineActionQueue offlineQueue,
                           PortableSettingsManager portableSettings) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        this.secureStore = secureStore;
        this.entityCache = entityCache;
        this.translationCache = translationCache;
        this.trackingDatabase = trackingDatabase;
        this.offlineQueue = offlineQueue;
        this.portableSettings = portableSettings;
    }

    public JSONObject exportUserData() {
        JSONObject root;
        try { root = portableSettings.exportData(); } catch (Exception e) { root = new JSONObject(); }
        try {
            root.put("format", "githubble-user-data-v1");
            root.put("generated_at", java.time.Instant.now().toString());
            root.put("authentication_exported", false);
            root.put("translation_api_key_exported", false);
            root.put("entity_cache_count", entityCache.count());
            root.put("translation_cache_count", translationCache.count());
            root.put("offline_queue", queueSummary());
            root.put("tracking_statistics", trackingDatabase.statistics());
            root.put("device_scoped_id", Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID));
        } catch (Exception ignored) { }
        return root;
    }

    private JSONArray queueSummary() {
        JSONArray array = new JSONArray();
        for (OfflineActionQueue.Action action : offlineQueue.listAll()) {
            JSONObject item = new JSONObject();
            try {
                item.put("summary", action.summary);
                item.put("status", action.status);
                item.put("created_at", action.createdAt);
                item.put("attempts", action.attempts);
                item.put("body_exported", false);
            } catch (Exception ignored) { }
            array.put(item);
        }
        return array;
    }

    /** Android clears the entire sandbox and stops this process and its jobs atomically. */
    public void deleteAllLocalData() {
        android.app.ActivityManager manager=context.getSystemService(android.app.ActivityManager.class);
        if(manager==null || !manager.clearApplicationUserData())
            throw new IllegalStateException("Android не разрешил очистку данных. Используйте настройки приложения → Хранилище → Очистить данные.");
    }
}
