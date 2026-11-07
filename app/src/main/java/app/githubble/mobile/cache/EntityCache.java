package app.githubble.mobile.cache;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONObject;

/** Persistent unified cache for GitHub REST/GraphQL entities and raw documents. */
public final class EntityCache extends SQLiteOpenHelper {
    private static final String DB_NAME = "githubble_entities.db";
    private static final int DB_VERSION = 3;
    private static final int MAX_ENTRIES = 1200;

    public static final class Entry {
        public final String key;
        public final String type;
        public final String payload;
        public final String etag;
        public final long fetchedAt;
        public final long expiresAt;
        public Entry(String key, String type, String payload, String etag, long fetchedAt, long expiresAt) {
            this.key=key; this.type=type; this.payload=payload; this.etag=etag; this.fetchedAt=fetchedAt; this.expiresAt=expiresAt;
        }
        public boolean isFresh(long now) { return expiresAt <= 0L || expiresAt > now; }
    }

    public EntityCache(Context context) { super(context.getApplicationContext(), DB_NAME, null, DB_VERSION); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE entities (cache_key TEXT PRIMARY KEY, entity_type TEXT NOT NULL, payload TEXT NOT NULL, etag TEXT, fetched_at INTEGER NOT NULL, expires_at INTEGER NOT NULL DEFAULT 0, last_access INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_entities_type ON entities(entity_type)");
        db.execSQL("CREATE INDEX idx_entities_access ON entities(last_access)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v2 scopes keys by account; v3 invalidates responses from the old racy client.
        // Never destroy unrelated durable user data during cache upgrades.
        if (oldVersion < 2) db.execSQL("UPDATE entities SET expires_at=1 WHERE cache_key NOT LIKE 'acct:%'");
        // Old clients could assign an in-flight response to a newly selected account.
        // No row from that cache has a trustworthy owner. Only regenerable API data is removed.
        if (oldVersion < 3) db.delete("entities", null, null);
    }

    public synchronized void put(String key, String type, String payload, String etag, long ttlMillis) {
        if (key == null || key.isEmpty() || payload == null) return;
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("cache_key", key); values.put("entity_type", type == null ? "generic" : type);
        values.put("payload", payload); values.put("etag", etag == null ? "" : etag);
        values.put("fetched_at", now); values.put("expires_at", ttlMillis <= 0 ? 0 : now + ttlMillis); values.put("last_access", now);
        getWritableDatabase().insertWithOnConflict("entities", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        trim();
    }

    public synchronized Entry get(String key, boolean allowExpired) {
        if (key == null || key.isEmpty()) return null;
        try (Cursor c = getReadableDatabase().query("entities", new String[]{"entity_type","payload","etag","fetched_at","expires_at"}, "cache_key=?", new String[]{key}, null, null, null)) {
            if (!c.moveToFirst()) return null;
            Entry entry = new Entry(key, c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4));
            if (!allowExpired && !entry.isFresh(System.currentTimeMillis())) return null;
            ContentValues touch = new ContentValues(); touch.put("last_access", System.currentTimeMillis());
            getWritableDatabase().update("entities", touch, "cache_key=?", new String[]{key});
            return entry;
        }
    }

    public synchronized void remove(String key) { getWritableDatabase().delete("entities", "cache_key=?", new String[]{key}); }
    public synchronized void removePrefix(String prefix) { if(prefix!=null)getWritableDatabase().delete("entities", "cache_key LIKE ?", new String[]{prefix+"%"}); }
    public synchronized void clear() { getWritableDatabase().delete("entities", null, null); }
    public synchronized int count() { try (Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM entities",null)){return c.moveToFirst()?c.getInt(0):0;} }
    public synchronized long sizeBytes() { try (Cursor c=getReadableDatabase().rawQuery("SELECT COALESCE(SUM(LENGTH(payload)),0) FROM entities",null)){return c.moveToFirst()?c.getLong(0):0L;} }
    public synchronized JSONObject stats() {
        JSONObject result = new JSONObject();
        try {
            result.put("entries", count()); result.put("payload_bytes", sizeBytes());
            try (Cursor c=getReadableDatabase().rawQuery("SELECT entity_type,COUNT(*) FROM entities GROUP BY entity_type ORDER BY COUNT(*) DESC",null)) {
                JSONObject types=new JSONObject(); while(c.moveToNext()) types.put(c.getString(0),c.getInt(1)); result.put("types",types);
            }
        } catch (Exception ignored) {}
        return result;
    }

    private void trim() {
        int extra = count() - MAX_ENTRIES;
        if (extra <= 0) return;
        getWritableDatabase().execSQL("DELETE FROM entities WHERE cache_key IN (SELECT cache_key FROM entities ORDER BY last_access ASC LIMIT " + extra + ")");
    }
}
