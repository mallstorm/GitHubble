package app.githubble.mobile.tracking;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Persistent local history for watched GitHub developers and repository changes. */
public final class TrackingDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "githubble_tracking.db";
    private static final int DB_VERSION = app.githubble.mobile.database.SchemaMigrationRegistry.TRACKING_SCHEMA;

    public static final class Developer {
        public String login = "";
        public String displayName = "";
        public String avatarUrl = "";
        public String htmlUrl = "";
        public long addedAt;
        public long lastCheckedAt;
        public String lastError = "";
        public boolean enabled = true;
        public boolean baselineComplete;
        public int repositoryCount;
        public long totalStars;
        public long totalForks;
    }

    public static final class RepoSnapshot {
        public long repoId;
        public String developerLogin = "";
        public String fullName = "";
        public String name = "";
        public String description = "";
        public String htmlUrl = "";
        public String createdAt = "";
        public String updatedAt = "";
        public String pushedAt = "";
        public int stars;
        public int forks;
        public int openIssues;
        public boolean archived;
        public String visibility = "public";
        public String latestReleaseTag = "";
        public String latestReleasePublishedAt = "";
        public long seenAt;
    }

    public static final class Event {
        public long id;
        public String fingerprint = "";
        public String developerLogin = "";
        public long repoId;
        public String eventType = "";
        public String title = "";
        public String summary = "";
        public String htmlUrl = "";
        public String eventTime = "";
        public long createdAt;
        public boolean read;
        public String payload = "{}";
    }

    public TrackingDatabase(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE tracked_developers (" +
                "login TEXT PRIMARY KEY COLLATE NOCASE," +
                "display_name TEXT NOT NULL DEFAULT ''," +
                "avatar_url TEXT NOT NULL DEFAULT ''," +
                "html_url TEXT NOT NULL DEFAULT ''," +
                "added_at INTEGER NOT NULL," +
                "last_checked_at INTEGER NOT NULL DEFAULT 0," +
                "last_error TEXT NOT NULL DEFAULT ''," +
                "enabled INTEGER NOT NULL DEFAULT 1," +
                "baseline_complete INTEGER NOT NULL DEFAULT 0," +
                "repository_count INTEGER NOT NULL DEFAULT 0," +
                "total_stars INTEGER NOT NULL DEFAULT 0," +
                "total_forks INTEGER NOT NULL DEFAULT 0," +
                "last_repo_cursor TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE TABLE repository_snapshots (" +
                "repo_id INTEGER PRIMARY KEY," +
                "developer_login TEXT NOT NULL COLLATE NOCASE," +
                "full_name TEXT NOT NULL," +
                "name TEXT NOT NULL DEFAULT ''," +
                "description TEXT NOT NULL DEFAULT ''," +
                "html_url TEXT NOT NULL DEFAULT ''," +
                "created_at TEXT NOT NULL DEFAULT ''," +
                "updated_at TEXT NOT NULL DEFAULT ''," +
                "pushed_at TEXT NOT NULL DEFAULT ''," +
                "stars INTEGER NOT NULL DEFAULT 0," +
                "forks INTEGER NOT NULL DEFAULT 0," +
                "open_issues INTEGER NOT NULL DEFAULT 0," +
                "archived INTEGER NOT NULL DEFAULT 0," +
                "visibility TEXT NOT NULL DEFAULT 'public'," +
                "latest_release_tag TEXT NOT NULL DEFAULT ''," +
                "latest_release_published_at TEXT NOT NULL DEFAULT ''," +
                "seen_at INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_repo_developer ON repository_snapshots(developer_login)");
        db.execSQL("CREATE TABLE tracking_events (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "fingerprint TEXT NOT NULL UNIQUE," +
                "developer_login TEXT NOT NULL COLLATE NOCASE," +
                "repo_id INTEGER NOT NULL DEFAULT 0," +
                "event_type TEXT NOT NULL," +
                "title TEXT NOT NULL," +
                "summary TEXT NOT NULL DEFAULT ''," +
                "html_url TEXT NOT NULL DEFAULT ''," +
                "event_time TEXT NOT NULL DEFAULT ''," +
                "created_at INTEGER NOT NULL," +
                "is_read INTEGER NOT NULL DEFAULT 0," +
                "payload TEXT NOT NULL DEFAULT '{}')");
        db.execSQL("CREATE INDEX idx_events_created ON tracking_events(created_at DESC)");
        db.execSQL("CREATE INDEX idx_events_unread ON tracking_events(is_read, created_at DESC)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE tracked_developers ADD COLUMN last_repo_cursor TEXT NOT NULL DEFAULT ''");
        }
    }

    public synchronized void upsertDeveloper(Developer d) {
        ContentValues v = new ContentValues();
        v.put("login", d.login);
        v.put("display_name", safe(d.displayName));
        v.put("avatar_url", safe(d.avatarUrl));
        v.put("html_url", safe(d.htmlUrl));
        v.put("added_at", d.addedAt <= 0 ? System.currentTimeMillis() : d.addedAt);
        v.put("last_checked_at", d.lastCheckedAt);
        v.put("last_error", safe(d.lastError));
        v.put("enabled", d.enabled ? 1 : 0);
        v.put("baseline_complete", d.baselineComplete ? 1 : 0);
        v.put("repository_count", d.repositoryCount);
        v.put("total_stars", d.totalStars);
        v.put("total_forks", d.totalForks);
        getWritableDatabase().insertWithOnConflict("tracked_developers", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized Developer getDeveloper(String login) {
        try (Cursor c = getReadableDatabase().query("tracked_developers", null, "login=?", new String[]{login}, null, null, null, "1")) {
            return c.moveToFirst() ? developerFrom(c) : null;
        }
    }

    public synchronized boolean isTracked(String login) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM tracked_developers WHERE login=? AND enabled=1 LIMIT 1", new String[]{login})) {
            return c.moveToFirst();
        }
    }

    public synchronized List<Developer> getDevelopers(boolean enabledOnly) {
        List<Developer> out = new ArrayList<>();
        String selection = enabledOnly ? "enabled=1" : null;
        try (Cursor c = getReadableDatabase().query("tracked_developers", null, selection, null, null, null, "added_at DESC")) {
            while (c.moveToNext()) out.add(developerFrom(c));
        }
        return out;
    }

    public synchronized void removeDeveloper(String login) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("tracking_events", "developer_login=?", new String[]{login});
            db.delete("repository_snapshots", "developer_login=?", new String[]{login});
            db.delete("tracked_developers", "login=?", new String[]{login});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized RepoSnapshot getRepository(long repoId) {
        try (Cursor c = getReadableDatabase().query("repository_snapshots", null, "repo_id=?", new String[]{String.valueOf(repoId)}, null, null, null, "1")) {
            return c.moveToFirst() ? repoFrom(c) : null;
        }
    }

    public synchronized List<RepoSnapshot> getRepositories(String login, int limit) {
        List<RepoSnapshot> out = new ArrayList<>();
        String lim = limit <= 0 ? null : String.valueOf(limit);
        try (Cursor c = getReadableDatabase().query("repository_snapshots", null, "developer_login=?", new String[]{login}, null, null, "pushed_at DESC, created_at DESC", lim)) {
            while (c.moveToNext()) out.add(repoFrom(c));
        }
        return out;
    }

    public synchronized void upsertRepository(RepoSnapshot r) {
        ContentValues v = new ContentValues();
        v.put("repo_id", r.repoId);
        v.put("developer_login", safe(r.developerLogin));
        v.put("full_name", safe(r.fullName));
        v.put("name", safe(r.name));
        v.put("description", safe(r.description));
        v.put("html_url", safe(r.htmlUrl));
        v.put("created_at", safe(r.createdAt));
        v.put("updated_at", safe(r.updatedAt));
        v.put("pushed_at", safe(r.pushedAt));
        v.put("stars", r.stars);
        v.put("forks", r.forks);
        v.put("open_issues", r.openIssues);
        v.put("archived", r.archived ? 1 : 0);
        v.put("visibility", safe(r.visibility));
        v.put("latest_release_tag", safe(r.latestReleaseTag));
        v.put("latest_release_published_at", safe(r.latestReleasePublishedAt));
        v.put("seen_at", r.seenAt);
        getWritableDatabase().insertWithOnConflict("repository_snapshots", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized boolean insertEvent(Event e) {
        ContentValues v = new ContentValues();
        v.put("fingerprint", e.fingerprint);
        v.put("developer_login", safe(e.developerLogin));
        v.put("repo_id", e.repoId);
        v.put("event_type", safe(e.eventType));
        v.put("title", safe(e.title));
        v.put("summary", safe(e.summary));
        v.put("html_url", safe(e.htmlUrl));
        v.put("event_time", safe(e.eventTime));
        v.put("created_at", e.createdAt <= 0 ? System.currentTimeMillis() : e.createdAt);
        v.put("is_read", e.read ? 1 : 0);
        v.put("payload", safe(e.payload));
        return getWritableDatabase().insertWithOnConflict("tracking_events", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    public synchronized List<Event> getEvents(String type, int limit) {
        List<Event> out = new ArrayList<>();
        String selection = type == null || type.isEmpty() || "ALL".equals(type) ? null : "event_type=?";
        String[] args = selection == null ? null : new String[]{type};
        try (Cursor c = getReadableDatabase().query("tracking_events", null, selection, args, null, null, "created_at DESC", String.valueOf(Math.max(1, limit)))) {
            while (c.moveToNext()) out.add(eventFrom(c));
        }
        return out;
    }

    public synchronized int unreadCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM tracking_events WHERE is_read=0", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public synchronized void markAllRead() {
        ContentValues v = new ContentValues(); v.put("is_read", 1);
        getWritableDatabase().update("tracking_events", v, "is_read=0", null);
    }

    public synchronized void clearEvents() {
        getWritableDatabase().delete("tracking_events", null, null);
    }

    public synchronized void clearAll() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("tracking_events", null, null);
            db.delete("repository_snapshots", null, null);
            db.delete("tracked_developers", null, null);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized JSONObject statistics() {
        JSONObject o = new JSONObject();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT COUNT(*), COALESCE(SUM(repository_count),0), COALESCE(SUM(total_stars),0), COALESCE(SUM(total_forks),0) FROM tracked_developers WHERE enabled=1", null)) {
            if (c.moveToFirst()) {
                o.put("developers", c.getInt(0));
                o.put("repositories", c.getInt(1));
                o.put("stars", c.getLong(2));
                o.put("forks", c.getLong(3));
            }
        } catch (Exception ignored) {}
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM tracking_events WHERE is_read=0", null)) {
            if (c.moveToFirst()) o.put("unread", c.getInt(0));
        } catch (Exception ignored) {}
        return o;
    }

    private static Developer developerFrom(Cursor c) {
        Developer d = new Developer();
        d.login = value(c, "login");
        d.displayName = value(c, "display_name");
        d.avatarUrl = value(c, "avatar_url");
        d.htmlUrl = value(c, "html_url");
        d.addedAt = longValue(c, "added_at");
        d.lastCheckedAt = longValue(c, "last_checked_at");
        d.lastError = value(c, "last_error");
        d.enabled = intValue(c, "enabled") != 0;
        d.baselineComplete = intValue(c, "baseline_complete") != 0;
        d.repositoryCount = intValue(c, "repository_count");
        d.totalStars = longValue(c, "total_stars");
        d.totalForks = longValue(c, "total_forks");
        return d;
    }

    private static RepoSnapshot repoFrom(Cursor c) {
        RepoSnapshot r = new RepoSnapshot();
        r.repoId = longValue(c, "repo_id");
        r.developerLogin = value(c, "developer_login");
        r.fullName = value(c, "full_name");
        r.name = value(c, "name");
        r.description = value(c, "description");
        r.htmlUrl = value(c, "html_url");
        r.createdAt = value(c, "created_at");
        r.updatedAt = value(c, "updated_at");
        r.pushedAt = value(c, "pushed_at");
        r.stars = intValue(c, "stars");
        r.forks = intValue(c, "forks");
        r.openIssues = intValue(c, "open_issues");
        r.archived = intValue(c, "archived") != 0;
        r.visibility = value(c, "visibility");
        r.latestReleaseTag = value(c, "latest_release_tag");
        r.latestReleasePublishedAt = value(c, "latest_release_published_at");
        r.seenAt = longValue(c, "seen_at");
        return r;
    }

    private static Event eventFrom(Cursor c) {
        Event e = new Event();
        e.id = longValue(c, "id");
        e.fingerprint = value(c, "fingerprint");
        e.developerLogin = value(c, "developer_login");
        e.repoId = longValue(c, "repo_id");
        e.eventType = value(c, "event_type");
        e.title = value(c, "title");
        e.summary = value(c, "summary");
        e.htmlUrl = value(c, "html_url");
        e.eventTime = value(c, "event_time");
        e.createdAt = longValue(c, "created_at");
        e.read = intValue(c, "is_read") != 0;
        e.payload = value(c, "payload");
        return e;
    }

    private static int index(Cursor c, String name) { return c.getColumnIndexOrThrow(name); }
    private static String value(Cursor c, String name) { String s = c.getString(index(c, name)); return s == null ? "" : s; }
    private static int intValue(Cursor c, String name) { return c.getInt(index(c, name)); }
    private static long longValue(Cursor c, String name) { return c.getLong(index(c, name)); }
    private static String safe(String s) { return s == null ? "" : s; }
}
