package app.githubble.mobile;

import android.content.Context;
import app.githubble.mobile.network.GitHubApiClient;
import app.githubble.mobile.network.GitHubSession;
import app.githubble.mobile.offline.OfflineActionQueue;
import app.githubble.mobile.security.SecureStore;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.json.JSONObject;

/**
 * Real SQLite + HTTP integration: replay cancellation, lock responsiveness and unknown outcomes.
 */
final class StabilizationChecks {
  static final class Sessions implements GitHubSession.Source {
    volatile GitHubSession current = new GitHubSession("fixture-A", "fixture-A", 1);

    public GitHubSession capture() {
      return current;
    }

    public boolean isCurrent(GitHubSession s) {
      return current.generation == s.generation;
    }

    public void reauthorization(GitHubSession s, boolean required) {}

    void switchAccount() {
      current = new GitHubSession("fixture-B", "fixture-B", 2);
    }
  }

  static void run(Context context) throws Exception {
    migrationDiscardsUntrustedApiCacheOnly(context);
    migrationPreservesPendingActions(context);
    queueDoesNotBlockReadersOrReplayWithAnotherAccount(context);
    uncertainMutationRemainsForReview(context);
    cancelledAuthenticationCannotReplaceSession(context);
    refreshedAuthenticationCannotReplaceAnotherAccount(context);
    allSecretDomainsCanBeCleared(context);
  }

  private static void migrationDiscardsUntrustedApiCacheOnly(Context context) throws Exception {
    final String name = "rc427-migration-githubble_entities.db";
    context.deleteDatabase(name);
    Context isolated =
        new android.content.ContextWrapper(context) {
          @Override
          public Context getApplicationContext() {
            return this;
          }

          @Override
          public java.io.File getDatabasePath(String ignored) {
            return context.getDatabasePath(name);
          }

          @Override
          public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(
              String ignored,
              int mode,
              android.database.sqlite.SQLiteDatabase.CursorFactory factory) {
            return context.openOrCreateDatabase(name, mode, factory);
          }

          @Override
          public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(
              String ignored,
              int mode,
              android.database.sqlite.SQLiteDatabase.CursorFactory factory,
              android.database.DatabaseErrorHandler handler) {
            return context.openOrCreateDatabase(name, mode, factory, handler);
          }
        };
    try {
      android.database.sqlite.SQLiteDatabase old = context.openOrCreateDatabase(name, 0, null);
      old.execSQL(
          "CREATE TABLE entities(cache_key TEXT PRIMARY KEY, entity_type TEXT NOT NULL,payload TEXT"
              + " NOT NULL,etag TEXT,fetched_at INTEGER NOT NULL,expires_at INTEGER NOT NULL"
              + " DEFAULT 0,last_access INTEGER NOT NULL)");
      old.execSQL(
          "INSERT INTO entities"
              + " VALUES('acct:bob|fixture','profile','private-alice-response','old-etag',1,0,1)");
      old.execSQL("CREATE TABLE durable_marker(value TEXT NOT NULL)");
      old.execSQL("INSERT INTO durable_marker VALUES('keep-user-data')");
      old.setVersion(2);
      old.close();
      try (app.githubble.mobile.cache.EntityCache migrated =
          new app.githubble.mobile.cache.EntityCache(isolated)) {
        if (migrated.get("acct:bob|fixture", true) != null || migrated.count() != 0)
          throw new AssertionError("pre-fix cache survived upgrade");
        try (android.database.Cursor c =
            migrated.getReadableDatabase().rawQuery("SELECT value FROM durable_marker", null)) {
          if (!c.moveToFirst() || !"keep-user-data".equals(c.getString(0)))
            throw new AssertionError("cache migration erased unrelated data");
        }
      }
    } finally {
      context.deleteDatabase(name);
    }
  }

  private static void migrationPreservesPendingActions(Context context) throws Exception {
    final String name = "rc427-migration-githubble_offline_actions.db";
    context.deleteDatabase(name);
    Context isolated =
        new android.content.ContextWrapper(context) {
          @Override
          public Context getApplicationContext() {
            return this;
          }

          @Override
          public java.io.File getDatabasePath(String ignored) {
            return context.getDatabasePath(name);
          }

          @Override
          public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(
              String ignored,
              int mode,
              android.database.sqlite.SQLiteDatabase.CursorFactory factory) {
            return context.openOrCreateDatabase(name, mode, factory);
          }

          @Override
          public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(
              String ignored,
              int mode,
              android.database.sqlite.SQLiteDatabase.CursorFactory factory,
              android.database.DatabaseErrorHandler handler) {
            return context.openOrCreateDatabase(name, mode, factory, handler);
          }
        };
    try {
      android.database.sqlite.SQLiteDatabase old = context.openOrCreateDatabase(name, 0, null);
      old.execSQL(
          "CREATE TABLE offline_actions(id INTEGER PRIMARY KEY AUTOINCREMENT,method TEXT NOT"
              + " NULL,url TEXT NOT NULL,body TEXT NOT NULL DEFAULT '{}',entity_key TEXT NOT NULL"
              + " DEFAULT '',summary TEXT NOT NULL DEFAULT '',conflict_url TEXT NOT NULL DEFAULT"
              + " '',version_field TEXT NOT NULL DEFAULT 'updated_at',base_version TEXT NOT NULL"
              + " DEFAULT '',server_snapshot TEXT NOT NULL DEFAULT '',status TEXT NOT NULL DEFAULT"
              + " 'PENDING',last_error TEXT NOT NULL DEFAULT '',created_at INTEGER NOT"
              + " NULL,next_attempt_at INTEGER NOT NULL DEFAULT 0,attempts INTEGER NOT NULL DEFAULT"
              + " 0,force_send INTEGER NOT NULL DEFAULT 0,last_http_status INTEGER NOT NULL DEFAULT"
              + " 0,account_id TEXT NOT NULL DEFAULT '',repository TEXT NOT NULL DEFAULT"
              + " '',action_type TEXT NOT NULL DEFAULT 'mutation',idempotency_key TEXT NOT NULL"
              + " DEFAULT '',expires_at INTEGER NOT NULL DEFAULT 0)");
      old.execSQL(
          "INSERT INTO offline_actions(method,url,body,created_at,account_id)"
              + " VALUES('PATCH','https://api.github.com/repos/a/b/issues/1','{\"title\":\"preserve"
              + " me\"}',1,'original-owner')");
      old.setVersion(3);
      old.close();
      try (OfflineActionQueue migrated = new OfflineActionQueue(isolated)) {
        OfflineActionQueue.Action action = migrated.listAll().get(0);
        if (!"original-owner".equals(action.accountId)
            || !action.body.contains("preserve me")
            || !OfflineActionQueue.PENDING.equals(action.status))
          throw new AssertionError("migration lost pending user data");
        if (migrated.getReadableDatabase().getVersion() != 4)
          throw new AssertionError("schema did not advance");
      }
    } finally {
      context.deleteDatabase(name);
    }
  }

  private static void queueDoesNotBlockReadersOrReplayWithAnotherAccount(Context context)
      throws Exception {
    Sessions sessions = new Sessions();
    OfflineActionQueue queue = new OfflineActionQueue(context);
    queue.clearAll();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    try (ServerSocket server =
        new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
      server.setSoTimeout(5000);
      Future<?> response =
          pool.submit(
              () -> {
                try (Socket socket = server.accept()) {
                  java.io.BufferedReader reader =
                      new java.io.BufferedReader(
                          new java.io.InputStreamReader(
                              socket.getInputStream(), StandardCharsets.UTF_8));
                  for (String line; (line = reader.readLine()) != null && !line.isEmpty(); ) {}
                  entered.countDown();
                  if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("no release");
                  byte[] body = "{\"updated_at\":\"v1\"}".getBytes(StandardCharsets.UTF_8);
                  socket
                      .getOutputStream()
                      .write(
                          ("HTTP/1.1 200 OK\r\nContent-Length: "
                                  + body.length
                                  + "\r\nConnection: close\r\n\r\n")
                              .getBytes(StandardCharsets.UTF_8));
                  socket.getOutputStream().write(body);
                } catch (Exception error) {
                  throw new RuntimeException(error);
                }
              });
      OfflineActionQueue.Action action = new OfflineActionQueue.Action();
      action.accountId = sessions.capture().scope;
      action.url = "http://127.0.0.1:" + server.getLocalPort() + "/issue";
      action.conflictUrl = action.url;
      action.baseVersion = "v1";
      action.body = "{\"title\":\"changed\"}";
      action.entityKey = "fixture-issue";
      queue.enqueue(action);
      Future<?> replay = pool.submit(() -> queue.processReady(new GitHubApiClient(sessions, null)));
      if (!entered.await(5, TimeUnit.SECONDS))
        throw new AssertionError("replay did not reach server");
      // Timeout proves the SQLite reader does not wait for the blocked HTTP worker.
      Future<Integer> read =
          CompletableFuture.supplyAsync(() -> queue.listAccount(action.accountId).size());
      if (read.get(1, TimeUnit.SECONDS) != 1) throw new AssertionError("queue disappeared");
      sessions.switchAccount();
      release.countDown();
      replay.get(5, TimeUnit.SECONDS);
      response.get(5, TimeUnit.SECONDS);
      OfflineActionQueue.Action saved = queue.listAccount(action.accountId).get(0);
      if (!OfflineActionQueue.PENDING.equals(saved.status))
        throw new AssertionError("unstarted mutation was lost");
      if (!queue.listAccount(sessions.capture().scope).isEmpty())
        throw new AssertionError("queue leaked to B");
    } finally {
      release.countDown();
      pool.shutdownNow();
      queue.clearAll();
      queue.close();
    }
  }

  private static void uncertainMutationRemainsForReview(Context context) throws Exception {
    Sessions sessions = new Sessions();
    OfflineActionQueue queue = new OfflineActionQueue(context);
    queue.clearAll();
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try (ServerSocket server =
        new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
      server.setSoTimeout(5000);
      Future<?> response =
          pool.submit(
              () -> {
                try (Socket socket = server.accept()) {
                  java.io.BufferedReader reader =
                      new java.io.BufferedReader(
                          new java.io.InputStreamReader(
                              socket.getInputStream(), StandardCharsets.UTF_8));
                  for (String line; (line = reader.readLine()) != null && !line.isEmpty(); ) {}
                  socket
                      .getOutputStream()
                      .write(
                          "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}"
                              .getBytes(StandardCharsets.UTF_8));
                } catch (Exception error) {
                  throw new RuntimeException(error);
                }
              });
      OfflineActionQueue.Action action = new OfflineActionQueue.Action();
      action.accountId = sessions.capture().scope;
      action.url = "http://127.0.0.1:" + server.getLocalPort() + "/issue";
      action.body = "{}";
      queue.enqueue(action);
      queue.processReady(new GitHubApiClient(sessions, null));
      response.get(5, TimeUnit.SECONDS);
      if (queue.count(OfflineActionQueue.UNKNOWN) != 1)
        throw new AssertionError("ambiguous mutation scheduled for replay");
      if (queue.processReady(new GitHubApiClient(sessions, null)).completed != 0)
        throw new AssertionError("unknown mutation replayed");
    } finally {
      pool.shutdownNow();
      queue.clearAll();
      queue.close();
    }
  }

  private static void cancelledAuthenticationCannotReplaceSession(Context context)
      throws Exception {
    SecureStore vault = new SecureStore(context);
    vault.put("github_token", "fixture-before-login");
    vault.put("github_login", "alice");
    java.util.concurrent.atomic.AtomicReference<
            app.githubble.mobile.auth.OAuthManager.DeviceSession>
        pending = new java.util.concurrent.atomic.AtomicReference<>();
    app.githubble.mobile.auth.OAuthManager auth =
        new app.githubble.mobile.auth.OAuthManager(
            context,
            vault,
            (url, body) -> {
              if (url.endsWith("/device/code"))
                return new JSONObject()
                    .put("device_code", "fixture-device")
                    .put("user_code", "AB12-CD34");
              pending.get().cancel();
              return new JSONObject().put("access_token", "must-not-replace-session");
            });
    app.githubble.mobile.auth.OAuthManager.DeviceSession session =
        auth.beginDeviceFlow("fixture-client", "");
    pending.set(session);
    if (auth.pollDeviceFlow("fixture-client", session, 5).state
            != app.githubble.mobile.auth.OAuthManager.PollState.DENIED
        || !"fixture-before-login".equals(vault.get("github_token")))
      throw new AssertionError("cancelled login overwrote session");
    new app.githubble.mobile.auth.GitHubAccountStore(context, vault).clearAll();
  }

  private static void refreshedAuthenticationCannotReplaceAnotherAccount(Context context)
      throws Exception {
    SecureStore vault = new SecureStore(context);
    vault.put("github_token", "fixture-expired-A");
    vault.put("github_login", "alice");
    vault.put("github_token_expires_at", "1");
    vault.put("github_refresh_token", "fixture-refresh-A");
    app.githubble.mobile.auth.OAuthManager auth =
        new app.githubble.mobile.auth.OAuthManager(
            context,
            vault,
            (url, body) -> {
              vault.put("github_token", "fixture-active-B");
              vault.put("github_login", "bob");
              return new JSONObject().put("access_token", "refreshed-A").put("expires_in", 3600);
            });
    if (auth.refreshIfNeeded("fixture-client")
        || !"fixture-active-B".equals(vault.get("github_token")))
      throw new AssertionError("refresh from A overwrote B");
    new app.githubble.mobile.auth.GitHubAccountStore(context, vault).clearAll();
  }

  private static void allSecretDomainsCanBeCleared(Context context) {
    SecureStore vault = new SecureStore(context);
    vault.put("omnichat_key_v2_fixture_openai", "fixture-key");
    vault.put("plugin_secret_fixture", "fixture-plugin");
    vault.put("github_token", "fixture-github");
    GitHubSession before = vault.capture();
    vault.clearAll();
    if (!vault.get("omnichat_key_v2_fixture_openai").isEmpty()
        || !vault.get("plugin_secret_fixture").isEmpty()
        || !vault.get("github_token").isEmpty()
        || vault.isCurrent(before)) throw new AssertionError("secret reset incomplete");
  }
}
