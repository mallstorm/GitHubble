package app.githubble.mobile.offline;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import app.githubble.mobile.network.GitHubApiClient;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Durable queue for replay-safe GitHub mutations. Tokens are never stored in this database. */
public final class OfflineActionQueue extends SQLiteOpenHelper {
    private static final String DB_NAME = "githubble_offline_actions.db";
    private static final int DB_VERSION = app.githubble.mobile.database.SchemaMigrationRegistry.OFFLINE_QUEUE_SCHEMA;
    public static final String PENDING = "PENDING";
    public static final String CONFLICT = "CONFLICT";
    public static final String FAILED = "FAILED";

    public static final class Action {
        public long id;
        public String method = "PATCH";
        public String url = "";
        public String body = "{}";
        public String entityKey = "";
        public String summary = "";
        public String conflictUrl = "";
        public String versionField = "updated_at";
        public String baseVersion = "";
        public String serverSnapshot = "";
        public String status = PENDING;
        public String lastError = "";
        public long createdAt;
        public long nextAttemptAt;
        public int attempts;
        public boolean force;
    }

    public static final class ProcessResult {
        public int completed;
        public int conflicts;
        public int failed;
        public int deferred;
    }

    public OfflineActionQueue(Context context) { super(context.getApplicationContext(), DB_NAME, null, DB_VERSION); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE offline_actions ("+
                "id INTEGER PRIMARY KEY AUTOINCREMENT,"+
                "method TEXT NOT NULL,"+
                "url TEXT NOT NULL,"+
                "body TEXT NOT NULL DEFAULT '{}',"+
                "entity_key TEXT NOT NULL DEFAULT '',"+
                "summary TEXT NOT NULL DEFAULT '',"+
                "conflict_url TEXT NOT NULL DEFAULT '',"+
                "version_field TEXT NOT NULL DEFAULT 'updated_at',"+
                "base_version TEXT NOT NULL DEFAULT '',"+
                "server_snapshot TEXT NOT NULL DEFAULT '',"+
                "status TEXT NOT NULL DEFAULT 'PENDING',"+
                "last_error TEXT NOT NULL DEFAULT '',"+
                "created_at INTEGER NOT NULL,"+
                "next_attempt_at INTEGER NOT NULL DEFAULT 0,"+
                "attempts INTEGER NOT NULL DEFAULT 0,"+
                "force_send INTEGER NOT NULL DEFAULT 0,"+
                "last_http_status INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_offline_ready ON offline_actions(status,next_attempt_at,created_at)");
        db.execSQL("CREATE UNIQUE INDEX idx_offline_entity_pending ON offline_actions(entity_key) WHERE status='PENDING' AND entity_key<>''");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE offline_actions ADD COLUMN last_http_status INTEGER NOT NULL DEFAULT 0");
    }

    /** Adds or replaces a replay-safe mutation for the same entity key. */
    public synchronized long enqueue(Action a) {
        long now = System.currentTimeMillis();
        if (a.entityKey != null && !a.entityKey.isEmpty()) {
            getWritableDatabase().delete("offline_actions", "entity_key=? AND status=?", new String[]{a.entityKey, PENDING});
        }
        ContentValues v = values(a);
        v.put("created_at", a.createdAt > 0 ? a.createdAt : now);
        v.put("next_attempt_at", a.nextAttemptAt > 0 ? a.nextAttemptAt : now);
        return getWritableDatabase().insertOrThrow("offline_actions", null, v);
    }

    public synchronized List<Action> listAll() {
        List<Action> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("offline_actions", null, null, null, null, null, "created_at DESC")) {
            while (c.moveToNext()) out.add(from(c));
        }
        return out;
    }

    public synchronized int count(String status) {
        String selection = status == null ? null : "status=?";
        String[] args = status == null ? null : new String[]{status};
        try (Cursor c = getReadableDatabase().query("offline_actions", new String[]{"COUNT(*)"}, selection, args, null, null, null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public synchronized void discard(long id) { getWritableDatabase().delete("offline_actions", "id=?", new String[]{String.valueOf(id)}); }

    public synchronized void clearAll() { getWritableDatabase().delete("offline_actions", null, null); }

    public synchronized void retry(long id, boolean force) {
        ContentValues v = new ContentValues();
        v.put("status", PENDING); v.put("next_attempt_at", System.currentTimeMillis()); v.put("last_error", ""); v.put("force_send", force ? 1 : 0);
        getWritableDatabase().update("offline_actions", v, "id=?", new String[]{String.valueOf(id)});
    }

    public synchronized ProcessResult processReady(GitHubApiClient api) {
        ProcessResult result = new ProcessResult();
        long now = System.currentTimeMillis();
        List<Action> ready = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("offline_actions", null, "status=? AND next_attempt_at<=?", new String[]{PENDING,String.valueOf(now)}, null, null, "created_at ASC", "30")) {
            while (c.moveToNext()) ready.add(from(c));
        }
        for (Action action : ready) {
            try {
                if (!action.force && !action.conflictUrl.isEmpty() && !action.baseVersion.isEmpty()) {
                    JSONObject server = api.getJson(action.conflictUrl);
                    String current = server.optString(action.versionField, "");
                    if (!current.isEmpty() && !current.equals(action.baseVersion)) {
                        markConflict(action.id, "Объект изменился на GitHub после постановки в очередь", server.toString());
                        result.conflicts++; continue;
                    }
                }
                api.executeJson(action.method, action.url, new JSONObject(action.body), true);
                discard(action.id);
                result.completed++;
            } catch (GitHubApiClient.ApiException e) {
                if (e.statusCode == 409 || e.statusCode == 412 || e.statusCode == 422) {
                    markConflict(action.id, e.getMessage(), action.serverSnapshot); result.conflicts++;
                } else if (e.statusCode == 401 || e.statusCode == 403 || e.statusCode == 404) {
                    markFailed(action.id, e.getMessage(), true); result.failed++;
                } else {
                    scheduleRetry(action, e.getMessage()); result.deferred++;
                }
            } catch (Exception e) {
                scheduleRetry(action, e.getMessage()); result.deferred++;
            }
        }
        return result;
    }

    private synchronized void scheduleRetry(Action a, String error) {
        int attempts = a.attempts + 1;
        if (attempts >= 6) { markFailed(a.id, error, true); return; }
        long delay = Math.min(6L * 60L * 60L * 1000L, (1L << Math.min(attempts, 8)) * 30_000L);
        ContentValues v = new ContentValues();
        v.put("attempts", attempts); v.put("last_error", safe(error)); v.put("next_attempt_at", System.currentTimeMillis() + delay);
        getWritableDatabase().update("offline_actions", v, "id=?", new String[]{String.valueOf(a.id)});
    }

    private synchronized void markConflict(long id, String error, String snapshot) {
        ContentValues v = new ContentValues(); v.put("status", CONFLICT); v.put("last_error", safe(error)); v.put("server_snapshot", safe(snapshot));
        getWritableDatabase().update("offline_actions", v, "id=?", new String[]{String.valueOf(id)});
    }

    private synchronized void markFailed(long id, String error, boolean terminal) {
        ContentValues v = new ContentValues(); v.put("status", terminal ? FAILED : PENDING); v.put("last_error", safe(error));
        getWritableDatabase().update("offline_actions", v, "id=?", new String[]{String.valueOf(id)});
    }

    private static ContentValues values(Action a) {
        ContentValues v = new ContentValues();
        v.put("method", safe(a.method)); v.put("url", safe(a.url)); v.put("body", safe(a.body)); v.put("entity_key", safe(a.entityKey));
        v.put("summary", safe(a.summary)); v.put("conflict_url", safe(a.conflictUrl)); v.put("version_field", safe(a.versionField));
        v.put("base_version", safe(a.baseVersion)); v.put("server_snapshot", safe(a.serverSnapshot)); v.put("status", safe(a.status));
        v.put("last_error", safe(a.lastError)); v.put("next_attempt_at", a.nextAttemptAt); v.put("attempts", a.attempts); v.put("force_send", a.force ? 1 : 0);
        return v;
    }

    private static Action from(Cursor c) {
        Action a = new Action();
        a.id = l(c,"id"); a.method=s(c,"method"); a.url=s(c,"url"); a.body=s(c,"body"); a.entityKey=s(c,"entity_key"); a.summary=s(c,"summary");
        a.conflictUrl=s(c,"conflict_url"); a.versionField=s(c,"version_field"); a.baseVersion=s(c,"base_version"); a.serverSnapshot=s(c,"server_snapshot");
        a.status=s(c,"status"); a.lastError=s(c,"last_error"); a.createdAt=l(c,"created_at"); a.nextAttemptAt=l(c,"next_attempt_at"); a.attempts=i(c,"attempts"); a.force=i(c,"force_send")!=0;
        return a;
    }
    private static String s(Cursor c,String n){String v=c.getString(c.getColumnIndexOrThrow(n));return v==null?"":v;}
    private static long l(Cursor c,String n){return c.getLong(c.getColumnIndexOrThrow(n));}
    private static int i(Cursor c,String n){return c.getInt(c.getColumnIndexOrThrow(n));}
    private static String safe(String value){return value==null?"":value;}
}
