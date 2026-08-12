package app.githubble.mobile.translation;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Persistent translation cache keyed by content SHA, update marker, provider and target language. */
public final class TranslationCache extends SQLiteOpenHelper {
    private static final String DB = "githubble_translation_cache.db";
    private static final int VERSION = 1;

    public static final class Entry {
        public final String translated;
        public final String detectedLanguage;
        public final long createdAt;
        Entry(String translated, String detectedLanguage, long createdAt) {
            this.translated = translated; this.detectedLanguage = detectedLanguage; this.createdAt = createdAt;
        }
    }

    public TranslationCache(Context context) { super(context, DB, null, VERSION); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE translations(cache_key TEXT PRIMARY KEY, source_sha TEXT NOT NULL, update_marker TEXT NOT NULL, target TEXT NOT NULL, provider TEXT NOT NULL, translated TEXT NOT NULL, detected_language TEXT, created_at INTEGER NOT NULL, last_access INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_translation_access ON translations(last_access)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS translations"); onCreate(db);
    }

    public Entry get(String source, String updateMarker, String target, String provider) {
        String key = key(source, updateMarker, target, provider);
        SQLiteDatabase db = getWritableDatabase();
        try (Cursor c = db.query("translations", new String[]{"translated","detected_language","created_at"}, "cache_key=?", new String[]{key}, null, null, null)) {
            if (!c.moveToFirst()) return null;
            ContentValues touch = new ContentValues(); touch.put("last_access", System.currentTimeMillis());
            db.update("translations", touch, "cache_key=?", new String[]{key});
            return new Entry(c.getString(0), c.getString(1), c.getLong(2));
        }
    }

    public void put(String source, String updateMarker, String target, String provider, String translated, String detectedLanguage) {
        long now = System.currentTimeMillis();
        ContentValues v = new ContentValues();
        v.put("cache_key", key(source, updateMarker, target, provider));
        v.put("source_sha", sha256(source));
        v.put("update_marker", safe(updateMarker));
        v.put("target", safe(target));
        v.put("provider", safe(provider));
        v.put("translated", safe(translated));
        v.put("detected_language", safe(detectedLanguage));
        v.put("created_at", now); v.put("last_access", now);
        getWritableDatabase().insertWithOnConflict("translations", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        trim(250);
    }

    public long sizeBytes() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COALESCE(SUM(LENGTH(translated)),0) FROM translations", null)) {
            return c.moveToFirst() ? c.getLong(0) : 0L;
        }
    }

    public int count() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM translations", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public void clear() { getWritableDatabase().delete("translations", null, null); }

    private void trim(int maxEntries) {
        getWritableDatabase().execSQL("DELETE FROM translations WHERE cache_key IN (SELECT cache_key FROM translations ORDER BY last_access DESC LIMIT -1 OFFSET " + Math.max(20, maxEntries) + ")");
    }

    public static String key(String source, String updateMarker, String target, String provider) {
        return sha256(safe(source)) + ":" + sha256(safe(updateMarker) + "|" + safe(target) + "|" + safe(provider));
    }

    public static String sha256(String value) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] bytes = d.digest(safe(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) { return Integer.toHexString(safe(value).hashCode()); }
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
