package app.githubble.mobile.agent;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Account- and thread-scoped SQLite archive of messages evicted from the fast 80-turn transcript.
 * The archive is local to the app; it is never sent wholesale to an AI provider. Duplicate
 * evictions are idempotent across interrupted writes.
 */
public final class AgentConversationArchive extends SQLiteOpenHelper {
  private static final String DB_NAME = "git-agent-chat-archive.db";

  public AgentConversationArchive(Context context) {
    super(context.getApplicationContext(), DB_NAME, null, 4);
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE archived_messages(id TEXT PRIMARY KEY, owner TEXT NOT NULL, thread TEXT NOT"
            + " NULL, at INTEGER NOT NULL, role TEXT NOT NULL, body TEXT NOT NULL, operations TEXT"
            + " NOT NULL DEFAULT '[]', attachments TEXT NOT NULL DEFAULT '[]', run TEXT NOT NULL"
            + " DEFAULT '{}')");
    db.execSQL("CREATE INDEX idx_archive_thread ON archived_messages(owner,thread,at)");
    db.execSQL("CREATE INDEX idx_archive_owner ON archived_messages(owner,at)");
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    if (oldVersion < 4)
      db.execSQL("ALTER TABLE archived_messages ADD COLUMN run TEXT NOT NULL DEFAULT '{}'");
    if (oldVersion < 3)
      db.execSQL("ALTER TABLE archived_messages ADD COLUMN attachments TEXT NOT NULL DEFAULT '[]'");
    if (oldVersion < 2)
      db.execSQL("ALTER TABLE archived_messages ADD COLUMN operations TEXT NOT NULL DEFAULT '[]'");
  }

  private static String key(String owner, String thread, JSONObject message) throws Exception {
    byte[] data =
        (owner
                + "\u0000"
                + thread
                + "\u0000"
                + message.optLong("at")
                + "\u0000"
                + message.optString("role")
                + "\u0000"
                + message.optString("body"))
            .getBytes(StandardCharsets.UTF_8);
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
    StringBuilder s = new StringBuilder();
    for (byte b : digest) s.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
    return s.toString();
  }

  public synchronized void archive(String owner, String thread, JSONObject message)
      throws Exception {
    if (owner == null || owner.isEmpty() || thread == null || thread.isEmpty() || message == null)
      return;
    ContentValues values = new ContentValues();
    values.put("id", key(owner, thread, message));
    values.put("owner", owner);
    values.put("thread", thread);
    values.put("at", message.optLong("at"));
    values.put("role", message.optString("role"));
    values.put("body", message.optString("body"));
    values.put(
        "operations",
        message.optJSONArray("operations") == null
            ? "[]"
            : message.optJSONArray("operations").toString());
    values.put(
        "attachments",
        message.optJSONArray("attachments") == null
            ? "[]"
            : message.optJSONArray("attachments").toString());
    values.put(
        "run",
        message.optJSONObject("run") == null ? "{}" : message.optJSONObject("run").toString());
    getWritableDatabase()
        .insertWithOnConflict("archived_messages", null, values, SQLiteDatabase.CONFLICT_IGNORE);
  }

  public synchronized JSONArray read(String owner, String thread, int limit) throws Exception {
    JSONArray descending = new JSONArray(), chronological = new JSONArray();
    String[] args = {owner, thread};
    String sql =
        "SELECT role,body,at,operations,attachments,run FROM archived_messages WHERE owner=? AND"
            + " thread=? ORDER BY at DESC,id DESC LIMIT "
            + Math.max(1, Math.min(10000, limit));
    try (Cursor c = getReadableDatabase().rawQuery(sql, args)) {
      while (c.moveToNext())
        descending.put(
            new JSONObject()
                .put("role", c.getString(0))
                .put("body", c.getString(1))
                .put("at", c.getLong(2))
                .put("operations", new JSONArray(c.getString(3)))
                .put("attachments", new JSONArray(c.getString(4)))
                .put("run", new JSONObject(c.getString(5))));
    }
    for (int i = descending.length() - 1; i >= 0; i--) chronological.put(descending.opt(i));
    return chronological;
  }

  public synchronized JSONArray search(String owner, String query, int limit) throws Exception {
    JSONArray result = new JSONArray();
    if (query == null || query.trim().length() < 2) return result;
    String needle =
        "%" + query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    String sql =
        "SELECT thread,role,body,at,operations,attachments,run FROM archived_messages WHERE owner=?"
            + " AND body LIKE ? ESCAPE '\\' ORDER BY at DESC LIMIT "
            + Math.max(1, Math.min(100, limit));
    try (Cursor c = getReadableDatabase().rawQuery(sql, new String[] {owner, needle})) {
      while (c.moveToNext())
        result.put(
            new JSONObject()
                .put("thread", c.getString(0))
                .put("role", c.getString(1))
                .put("body", c.getString(2))
                .put("at", c.getLong(3))
                .put("operations", new JSONArray(c.getString(4)))
                .put("attachments", new JSONArray(c.getString(5)))
                .put("run", new JSONObject(c.getString(6))));
    }
    return result;
  }

  public synchronized long count(String owner) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT COUNT(*) FROM archived_messages WHERE owner=?", new String[] {owner})) {
      return c.moveToFirst() ? c.getLong(0) : 0L;
    }
  }

  public synchronized void delete(String owner, String thread) {
    getWritableDatabase()
        .delete("archived_messages", "owner=? AND thread=?", new String[] {owner, thread});
  }
}
