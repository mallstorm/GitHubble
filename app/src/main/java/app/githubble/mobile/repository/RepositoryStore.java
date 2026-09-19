package app.githubble.mobile.repository;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Single account-scoped source of truth for repository snapshots.
 * A failed/empty refresh never destroys a previously successful snapshot.
 */
public final class RepositoryStore {
    public enum Status { NEVER_LOADED, LOADED, EMPTY, STALE, ERROR }

    public static final class Snapshot {
        public final JSONArray repositories;
        public final Status status;
        public final long lastSyncedAt;
        public final String error;
        public final int fetched;
        public final int invalid;

        Snapshot(JSONArray repositories, Status status, long lastSyncedAt, String error,
                 int fetched, int invalid) {
            this.repositories = repositories == null ? new JSONArray() : repositories;
            this.status = status;
            this.lastSyncedAt = lastSyncedAt;
            this.error = error == null ? "" : error;
            this.fetched = fetched;
            this.invalid = invalid;
        }

        public boolean hasData() { return repositories.length() > 0; }
    }

    private final SharedPreferences prefs;

    public RepositoryStore(SharedPreferences prefs) { this.prefs = prefs; }

    public synchronized Snapshot read(String accountId) {
        String prefix = prefix(accountId);
        String payload = prefs.getString(prefix + ".json", "");
        long syncedAt = prefs.getLong(prefix + ".synced_at", 0L);
        String error = prefs.getString(prefix + ".error", "");
        int fetched = prefs.getInt(prefix + ".fetched", 0);
        int invalid = prefs.getInt(prefix + ".invalid", 0);
        if (payload.isEmpty()) {
            Status status = error.isEmpty() ? Status.NEVER_LOADED : Status.ERROR;
            return new Snapshot(new JSONArray(), status, syncedAt, error, fetched, invalid);
        }
        try {
            JSONArray repos = new JSONArray(payload);
            Status status = error.isEmpty() ? (repos.length() == 0 ? Status.EMPTY : Status.LOADED) : Status.STALE;
            return new Snapshot(repos, status, syncedAt, error, fetched, invalid);
        } catch (Exception e) {
            return new Snapshot(new JSONArray(), Status.ERROR, syncedAt,
                    "Повреждён локальный список репозиториев", fetched, invalid);
        }
    }

    public synchronized Snapshot saveSuccess(String accountId, JSONArray input) {
        JSONArray valid = new JSONArray();
        int fetched = input == null ? 0 : input.length();
        int invalid = 0;
        for (int i = 0; i < fetched; i++) {
            JSONObject repository = input.optJSONObject(i);
            if (repository == null || repository.optString("full_name", "").trim().isEmpty()) {
                invalid++;
                continue;
            }
            valid.put(repository);
        }
        String prefix = prefix(accountId);
        long now = System.currentTimeMillis();
        // SharedPreferences applies a single editor transaction atomically.
        prefs.edit()
                .putString(prefix + ".json", valid.toString())
                .putLong(prefix + ".synced_at", now)
                .putInt(prefix + ".fetched", fetched)
                .putInt(prefix + ".invalid", invalid)
                .remove(prefix + ".error")
                .apply();
        return new Snapshot(valid, valid.length() == 0 ? Status.EMPTY : Status.LOADED,
                now, "", fetched, invalid);
    }

    public synchronized Snapshot saveError(String accountId, Throwable error) {
        String message = error == null || error.getMessage() == null
                ? "Неизвестная ошибка синхронизации" : error.getMessage();
        prefs.edit().putString(prefix(accountId) + ".error", message).apply();
        return read(accountId);
    }

    public synchronized void clear(String accountId) {
        String prefix = prefix(accountId);
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) if (key.startsWith(prefix + ".")) editor.remove(key);
        editor.apply();
    }

    /** Imports the RC3.9 global snapshot once, only into the currently authenticated account. */
    public synchronized void migrateLegacy(String accountId) {
        if (accountId == null || accountId.isEmpty()) return;
        String legacy = prefs.getString("cache_my_repositories", "");
        if (legacy.isEmpty() || prefs.contains(prefix(accountId) + ".json")) return;
        try { saveSuccess(accountId, new JSONArray(legacy)); }
        catch (Exception ignored) { }
        prefs.edit().remove("cache_my_repositories").apply();
    }

    private static String prefix(String accountId) {
        return "repo_store." + hash(accountId == null ? "anonymous" : accountId);
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 12; i++) out.append(String.format(java.util.Locale.ROOT, "%02x", digest[i]));
            return out.toString();
        } catch (Exception e) { return Integer.toHexString(value.hashCode()); }
    }
}
