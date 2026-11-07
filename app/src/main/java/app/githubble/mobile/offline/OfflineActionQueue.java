package app.githubble.mobile.offline;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import app.githubble.mobile.network.GitHubApiClient;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Durable queue for replay-safe GitHub mutations. Tokens are never stored in this database. */
public final class OfflineActionQueue extends SQLiteOpenHelper {
  private static final String DB_NAME = "githubble_offline_actions.db";
  private static final int DB_VERSION =
      app.githubble.mobile.database.SchemaMigrationRegistry.OFFLINE_QUEUE_SCHEMA;
  public static final String PENDING = "PENDING";
  public static final String CONFLICT = "CONFLICT";
  public static final String FAILED = "FAILED";
  public static final String IN_FLIGHT = "IN_FLIGHT";
  public static final String UNKNOWN = "UNKNOWN";
  private static final java.util.concurrent.atomic.AtomicBoolean PROCESSING =
      new java.util.concurrent.atomic.AtomicBoolean();
  private static final long LEASE_MS = 10L * 60L * 1000L;

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
    public String accountId = "";
    public String repository = "";
    public String actionType = "mutation";
    public String idempotencyKey = "";
    public long expiresAt;
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

  public OfflineActionQueue(Context context) {
    super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE offline_actions ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "method TEXT NOT NULL,"
            + "url TEXT NOT NULL,"
            + "body TEXT NOT NULL DEFAULT '{}',"
            + "entity_key TEXT NOT NULL DEFAULT '',"
            + "summary TEXT NOT NULL DEFAULT '',"
            + "conflict_url TEXT NOT NULL DEFAULT '',"
            + "version_field TEXT NOT NULL DEFAULT 'updated_at',"
            + "base_version TEXT NOT NULL DEFAULT '',"
            + "server_snapshot TEXT NOT NULL DEFAULT '',"
            + "status TEXT NOT NULL DEFAULT 'PENDING',"
            + "last_error TEXT NOT NULL DEFAULT '',"
            + "created_at INTEGER NOT NULL,"
            + "next_attempt_at INTEGER NOT NULL DEFAULT 0,"
            + "attempts INTEGER NOT NULL DEFAULT 0,"
            + "force_send INTEGER NOT NULL DEFAULT 0,"
            + "last_http_status INTEGER NOT NULL DEFAULT 0,"
            + "account_id TEXT NOT NULL DEFAULT '',"
            + "repository TEXT NOT NULL DEFAULT '',"
            + "action_type TEXT NOT NULL DEFAULT 'mutation',"
            + "idempotency_key TEXT NOT NULL DEFAULT '',"
            + "expires_at INTEGER NOT NULL DEFAULT 0,"
            + "claim_id TEXT NOT NULL DEFAULT '',claim_at INTEGER NOT NULL DEFAULT 0)");
    db.execSQL(
        "CREATE INDEX idx_offline_ready ON"
            + " offline_actions(account_id,status,next_attempt_at,created_at)");
    db.execSQL(
        "CREATE UNIQUE INDEX idx_offline_entity_pending ON offline_actions(account_id,entity_key)"
            + " WHERE status IN ('PENDING','IN_FLIGHT') AND entity_key<>''");
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    if (oldVersion < 2)
      db.execSQL(
          "ALTER TABLE offline_actions ADD COLUMN last_http_status INTEGER NOT NULL DEFAULT 0");
    if (oldVersion < 3) {
      db.execSQL("ALTER TABLE offline_actions ADD COLUMN account_id TEXT NOT NULL DEFAULT ''");
      db.execSQL("ALTER TABLE offline_actions ADD COLUMN repository TEXT NOT NULL DEFAULT ''");
      db.execSQL(
          "ALTER TABLE offline_actions ADD COLUMN action_type TEXT NOT NULL DEFAULT 'mutation'");
      db.execSQL("ALTER TABLE offline_actions ADD COLUMN idempotency_key TEXT NOT NULL DEFAULT ''");
      db.execSQL("ALTER TABLE offline_actions ADD COLUMN expires_at INTEGER NOT NULL DEFAULT 0");
      db.execSQL("DROP INDEX IF EXISTS idx_offline_ready");
      db.execSQL("DROP INDEX IF EXISTS idx_offline_entity_pending");
      db.execSQL(
          "CREATE INDEX idx_offline_ready ON"
              + " offline_actions(account_id,status,next_attempt_at,created_at)");
      db.execSQL(
          "CREATE UNIQUE INDEX idx_offline_entity_pending ON offline_actions(account_id,entity_key)"
              + " WHERE status IN ('PENDING','IN_FLIGHT') AND entity_key<>''");
      // Legacy entries have no trustworthy owner and must never replay under a new token.
      ContentValues legacy = new ContentValues();
      legacy.put("status", FAILED);
      legacy.put(
          "last_error",
          "Требуется повторить действие после обновления: владелец старой записи неизвестен");
      db.update("offline_actions", legacy, "account_id=''", null);
    }
    if (oldVersion < 4) {
      db.execSQL("ALTER TABLE offline_actions ADD COLUMN claim_id TEXT NOT NULL DEFAULT ''");
      db.execSQL("ALTER TABLE offline_actions ADD COLUMN claim_at INTEGER NOT NULL DEFAULT 0");
      db.execSQL("DROP INDEX IF EXISTS idx_offline_entity_pending");
      db.execSQL(
          "CREATE UNIQUE INDEX idx_offline_entity_pending ON offline_actions(account_id,entity_key)"
              + " WHERE status IN ('PENDING','IN_FLIGHT') AND entity_key<>''");
    }
  }

  /** Adds or replaces a replay-safe mutation for the same entity key. */
  public synchronized long enqueue(Action a) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      long now = System.currentTimeMillis();
      if (a.entityKey != null && !a.entityKey.isEmpty()) {
        getWritableDatabase()
            .delete(
                "offline_actions",
                "account_id=? AND entity_key=? AND status=?",
                new String[] {safe(a.accountId), a.entityKey, PENDING});
      }
      ContentValues v = values(a);
      v.put("created_at", a.createdAt > 0 ? a.createdAt : now);
      v.put("next_attempt_at", a.nextAttemptAt > 0 ? a.nextAttemptAt : now);
      long id = db.insertOrThrow("offline_actions", null, v);
      db.setTransactionSuccessful();
      return id;
    } finally {
      db.endTransaction();
    }
  }

  public synchronized List<Action> listAll() {
    return listAccount(null);
  }

  public synchronized List<Action> listAccount(String account) {
    List<Action> out = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .query(
                "offline_actions",
                null,
                account == null ? null : "account_id=?",
                account == null ? null : new String[] {account},
                null,
                null,
                "created_at DESC")) {
      while (c.moveToNext()) out.add(from(c));
    }
    return out;
  }

  public synchronized int count(String status) {
    String selection = status == null ? null : "status=?";
    String[] args = status == null ? null : new String[] {status};
    try (Cursor c =
        getReadableDatabase()
            .query(
                "offline_actions", new String[] {"COUNT(*)"}, selection, args, null, null, null)) {
      return c.moveToFirst() ? c.getInt(0) : 0;
    }
  }

  public synchronized void discard(long id) {
    getWritableDatabase().delete("offline_actions", "id=?", new String[] {String.valueOf(id)});
  }

  public synchronized void clearAll() {
    getWritableDatabase().delete("offline_actions", null, null);
  }

  public synchronized void clearAccount(String accountId) {
    getWritableDatabase().delete("offline_actions", "account_id=?", new String[] {safe(accountId)});
  }

  public synchronized void retry(long id, boolean force) {
    ContentValues v = new ContentValues();
    v.put("attempts", 0);
    v.put("status", PENDING);
    v.put("next_attempt_at", System.currentTimeMillis());
    v.put("last_error", "");
    v.put("force_send", force ? 1 : 0);
    try {
      getWritableDatabase()
          .update(
              "offline_actions",
              v,
              "id=? AND status<>?",
              new String[] {String.valueOf(id), IN_FLIGHT});
    } catch (android.database.sqlite.SQLiteConstraintException newerPending) {
      markFailed(
          id,
          "Для этого объекта уже есть новое ожидающее действие. Удалите одно из действий перед"
              + " повтором.",
          true);
    }
  }

  /** Claim in short DB transactions; never hold the queue monitor during network IO. */
  public ProcessResult processReady(GitHubApiClient original) {
    ProcessResult result = new ProcessResult();
    if (!PROCESSING.compareAndSet(false, true)) return result;
    try {
      GitHubApiClient api = original.bindCurrentSession();
      String account = api.accountScope();
      String claim = java.util.UUID.randomUUID().toString();
      List<Action> ready = claimReady(account, claim);
      for (Action action : ready) {
        try {
          api.requireCurrentSession();
          if (action.expiresAt > 0 && action.expiresAt <= System.currentTimeMillis()) {
            markFailed(action.id, "Срок хранения отложенного действия истёк", true);
            result.failed++;
            continue;
          }
          if (!action.force && !action.conflictUrl.isEmpty() && !action.baseVersion.isEmpty()) {
            JSONObject server = api.getJsonFresh(action.conflictUrl);
            String current = server.optString(action.versionField, "");
            if (current.isEmpty() || !current.equals(action.baseVersion)) {
              markConflict(
                  action.id, "Объект изменился или его версию нельзя проверить", server.toString());
              result.conflicts++;
              continue;
            }
          }
          api.requireCurrentSession();
          api.executeJson(action.method, action.url, new JSONObject(action.body), true);
          discard(action.id);
          result.completed++;
        } catch (GitHubApiClient.SessionChangedException cancelled) {
          releaseClaim(claim);
          result.deferred++;
          break;
        } catch (GitHubApiClient.MutationOutcomeUnknownException uncertain) {
          markUnknown(action.id, uncertain.getMessage());
          result.failed++;
        } catch (GitHubApiClient.ApiException error) {
          if (error.statusCode == 409 || error.statusCode == 412 || error.statusCode == 422) {
            markConflict(action.id, error.getMessage(), action.serverSnapshot);
            result.conflicts++;
          } else if ((error.statusCode == 401 || error.statusCode == 403 || error.statusCode == 404)
              && !error.rateLimited) {
            markFailed(action.id, error.getMessage(), true);
            result.failed++;
          } else {
            scheduleRetry(action, error.getMessage(), error.retryAfterMillis);
            result.deferred++;
          }
        } catch (Exception error) {
          scheduleRetry(action, error.getMessage(), 0);
          result.deferred++;
        }
      }
    } catch (GitHubApiClient.SessionChangedException cancelled) {
      result.deferred++;
    } finally {
      PROCESSING.set(false);
    }
    return result;
  }

  private synchronized List<Action> claimReady(String account, String claim) {
    SQLiteDatabase db = getWritableDatabase();
    long now = System.currentTimeMillis();
    List<Action> ready = new ArrayList<>();
    db.beginTransaction();
    try {
      ContentValues lost = new ContentValues();
      lost.put("status", UNKNOWN);
      lost.put("last_error", "Выполнение прервано. Проверьте результат на GitHub перед повтором.");
      db.update(
          "offline_actions",
          lost,
          "status=? AND claim_at<?",
          new String[] {IN_FLIGHT, String.valueOf(now - LEASE_MS)});
      try (Cursor c =
          db.query(
              "offline_actions",
              null,
              "account_id=? AND status=? AND next_attempt_at<=?",
              new String[] {account, PENDING, String.valueOf(now)},
              null,
              null,
              "created_at ASC",
              "30")) {
        while (c.moveToNext()) ready.add(from(c));
      }
      for (Action a : ready) {
        ContentValues v = new ContentValues();
        v.put("status", IN_FLIGHT);
        v.put("claim_id", claim);
        v.put("claim_at", now);
        db.update(
            "offline_actions",
            v,
            "id=? AND status=?",
            new String[] {String.valueOf(a.id), PENDING});
      }
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
    return ready;
  }

  private synchronized void releaseClaim(String claim) {
    ContentValues v = new ContentValues();
    v.put("status", PENDING);
    v.put("claim_id", "");
    v.put("claim_at", 0);
    getWritableDatabase()
        .update("offline_actions", v, "claim_id=? AND status=?", new String[] {claim, IN_FLIGHT});
  }

  private synchronized void markUnknown(long id, String error) {
    ContentValues v = new ContentValues();
    v.put("status", UNKNOWN);
    v.put("last_error", safe(error));
    getWritableDatabase().update("offline_actions", v, "id=?", new String[] {String.valueOf(id)});
  }

  private synchronized void scheduleRetry(Action a, String error, long retryAfter) {
    int attempts = a.attempts + 1;
    if (attempts >= 6) {
      markFailed(a.id, error, true);
      return;
    }
    long delay = Math.min(6L * 60L * 60L * 1000L, (1L << Math.min(attempts, 8)) * 30_000L);
    ContentValues v = new ContentValues();
    v.put("status", PENDING);
    v.put("claim_id", "");
    v.put("claim_at", 0);
    v.put("attempts", attempts);
    v.put("last_error", safe(error));
    v.put("next_attempt_at", System.currentTimeMillis() + Math.max(delay, retryAfter));
    getWritableDatabase().update("offline_actions", v, "id=?", new String[] {String.valueOf(a.id)});
  }

  private synchronized void markConflict(long id, String error, String snapshot) {
    ContentValues v = new ContentValues();
    v.put("status", CONFLICT);
    v.put("last_error", safe(error));
    v.put("server_snapshot", safe(snapshot));
    getWritableDatabase().update("offline_actions", v, "id=?", new String[] {String.valueOf(id)});
  }

  private synchronized void markFailed(long id, String error, boolean terminal) {
    ContentValues v = new ContentValues();
    v.put("status", terminal ? FAILED : PENDING);
    v.put("last_error", safe(error));
    getWritableDatabase().update("offline_actions", v, "id=?", new String[] {String.valueOf(id)});
  }

  private static ContentValues values(Action a) {
    ContentValues v = new ContentValues();
    v.put("method", safe(a.method));
    v.put("url", safe(a.url));
    v.put("body", safe(a.body));
    v.put("entity_key", safe(a.entityKey));
    v.put("summary", safe(a.summary));
    v.put("conflict_url", safe(a.conflictUrl));
    v.put("version_field", safe(a.versionField));
    v.put("base_version", safe(a.baseVersion));
    v.put("server_snapshot", safe(a.serverSnapshot));
    v.put("status", safe(a.status));
    v.put("last_error", safe(a.lastError));
    v.put("next_attempt_at", a.nextAttemptAt);
    v.put("attempts", a.attempts);
    v.put("force_send", a.force ? 1 : 0);
    v.put("account_id", safe(a.accountId));
    v.put("repository", safe(a.repository));
    v.put("action_type", safe(a.actionType));
    v.put("idempotency_key", safe(a.idempotencyKey));
    v.put("expires_at", a.expiresAt);
    return v;
  }

  private static Action from(Cursor c) {
    Action a = new Action();
    a.id = l(c, "id");
    a.method = s(c, "method");
    a.url = s(c, "url");
    a.body = s(c, "body");
    a.entityKey = s(c, "entity_key");
    a.summary = s(c, "summary");
    a.conflictUrl = s(c, "conflict_url");
    a.versionField = s(c, "version_field");
    a.baseVersion = s(c, "base_version");
    a.serverSnapshot = s(c, "server_snapshot");
    a.status = s(c, "status");
    a.lastError = s(c, "last_error");
    a.createdAt = l(c, "created_at");
    a.nextAttemptAt = l(c, "next_attempt_at");
    a.attempts = i(c, "attempts");
    a.force = i(c, "force_send") != 0;
    a.accountId = s(c, "account_id");
    a.repository = s(c, "repository");
    a.actionType = s(c, "action_type");
    a.idempotencyKey = s(c, "idempotency_key");
    a.expiresAt = l(c, "expires_at");
    return a;
  }

  private static String s(Cursor c, String n) {
    String v = c.getString(c.getColumnIndexOrThrow(n));
    return v == null ? "" : v;
  }

  private static long l(Cursor c, String n) {
    return c.getLong(c.getColumnIndexOrThrow(n));
  }

  private static int i(Cursor c, String n) {
    return c.getInt(c.getColumnIndexOrThrow(n));
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }
}
