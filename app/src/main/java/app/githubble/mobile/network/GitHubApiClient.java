package app.githubble.mobile.network;

import android.util.Base64;
import android.util.Base64OutputStream;
import app.githubble.mobile.cache.EntityCache;
import app.githubble.mobile.security.SecureStore;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Session-bound HTTP, explicit fresh reads and conservative mutation retry semantics. */
public final class GitHubApiClient {
  public static class ApiException extends IOException {
    public final int statusCode;
    public final long rateLimitResetEpochSeconds;
    public final boolean rateLimited;
    public final long retryAfterMillis;

    public ApiException(int code, String message, long reset) {
      this(code, message, reset, false, 0);
    }

    ApiException(int code, String message, long reset, boolean rateLimited, long retryAfter) {
      super(message);
      statusCode = code;
      rateLimitResetEpochSeconds = reset;
      this.rateLimited = rateLimited;
      retryAfterMillis = retryAfter;
    }
  }

  public static final class SessionChangedException extends InterruptedIOException {
    public SessionChangedException() {
      super("Аккаунт изменился: операция отменена");
    }
  }

  /** A write may have reached GitHub. Never blindly replay it. */
  public static final class MutationOutcomeUnknownException extends ApiException {
    MutationOutcomeUnknownException(int code, String message, long reset) {
      super(
          code,
          "Результат записи неизвестен. Проверьте состояние на GitHub перед повтором. " + message,
          reset);
    }
  }

  public interface Cache {
    final class Entry {
      public final String payload, etag;

      public Entry(String payload, String etag) {
        this.payload = payload;
        this.etag = etag;
      }
    }

    Entry get(String key);

    void put(String key, String type, String payload, String etag, long ttl);

    void remove(String key);

    void removePrefix(String prefix);
  }

  private static Cache adapt(EntityCache db) {
    if (db == null) return null;
    return new Cache() {
      public Entry get(String key) {
        EntityCache.Entry e = db.get(key, true);
        return e == null ? null : new Entry(e.payload, e.etag);
      }

      public void put(String key, String type, String payload, String etag, long ttl) {
        db.put(key, type, payload, etag, ttl);
      }

      public void remove(String key) {
        db.remove(key);
      }

      public void removePrefix(String prefix) {
        db.removePrefix(prefix);
      }
    };
  }

  private final GitHubSession.Source sessions;
  private final Cache cache;
  private final GitHubSession bound;
  private final boolean checkBound;
  private static final int MAX_CACHE_BODY = 512 * 1024, MAX_RESPONSE_BYTES = 32 * 1024 * 1024;
  private final Map<String, Cache.Entry> memory =
      new LinkedHashMap<String, Cache.Entry>(32, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cache.Entry> e) {
          return size() > 32;
        }
      };
  private volatile int remaining = -1;
  private volatile long resetEpochSeconds;

  public GitHubApiClient(SecureStore store) {
    this(store, (EntityCache) null);
  }

  public GitHubApiClient(SecureStore store, EntityCache cache) {
    this(store, adapt(cache), null, true);
  }

  public GitHubApiClient(SecureStore store, EntityCache cache, String token) {
    this(
        store,
        adapt(cache),
        token == null ? null : new GitHubSession(token, "", -1),
        token == null);
  }

  public GitHubApiClient(GitHubSession.Source sessions, Cache cache) {
    this(sessions, cache, null, true);
  }

  private GitHubApiClient(
      GitHubSession.Source sessions, Cache cache, GitHubSession bound, boolean checkBound) {
    this.sessions = sessions;
    this.cache = cache;
    this.bound = bound;
    this.checkBound = checkBound;
  }

  private GitHubSession capture() {
    return bound == null ? sessions.capture() : bound;
  }

  private void requireCurrent(GitHubSession s) throws SessionChangedException {
    if (Thread.currentThread().isInterrupted() || (checkBound && !sessions.isCurrent(s)))
      throw new SessionChangedException();
  }

  public void requireCurrentSession() throws SessionChangedException {
    requireCurrent(capture());
  }

  /** Queue/compound operations keep their original token and owner for their entire lifetime. */
  public GitHubApiClient bindCurrentSession() throws SessionChangedException {
    GitHubSession s = capture();
    requireCurrent(s);
    return new GitHubApiClient(sessions, cache, s, checkBound);
  }

  public String accountScope() {
    return capture().scope;
  }

  public int getRemaining() {
    return remaining;
  }

  public long getResetEpochSeconds() {
    return resetEpochSeconds;
  }

  private static String key(GitHubSession s, String url) {
    return "acct:" + s.scope + "|" + url;
  }

  private Cache.Entry cached(String key) {
    synchronized (memory) {
      Cache.Entry e = memory.get(key);
      if (e != null) return e;
    }
    return cache == null ? null : cache.get(key);
  }

  public void invalidate(String url) {
    if (url == null) return;
    String key = key(capture(), url);
    synchronized (memory) {
      memory.remove(key);
    }
    if (cache != null) cache.remove(key);
  }

  public void invalidatePrefix(String prefix) {
    if (prefix == null) return;
    String scoped = key(capture(), prefix);
    synchronized (memory) {
      memory.keySet().removeIf(k -> k.startsWith(scoped));
    }
    if (cache != null) cache.removePrefix(scoped);
  }

  public void clearSessionCaches() {
    resetInMemorySessionState();
    if (cache != null) cache.removePrefix("acct:" + accountScope() + "|");
  }

  public void resetInMemorySessionState() {
    synchronized (memory) {
      memory.clear();
    }
    remaining = -1;
    resetEpochSeconds = 0;
  }

  public JSONObject getJson(String url) throws Exception {
    return new JSONObject(get(url, true));
  }

  public JSONArray getArray(String url) throws Exception {
    return new JSONArray(get(url, true));
  }

  public String getText(String url) throws Exception {
    return get(url, true);
  }

  /** Conflict checks and authentication must never succeed from an expired/offline snapshot. */
  public JSONObject getJsonFresh(String url) throws Exception {
    return new JSONObject(get(url, false));
  }

  private String get(String url, boolean fallback) throws Exception {
    GitHubSession s = capture();
    requireCurrent(s);
    try {
      return request(s, "GET", url, null, true, fallback);
    } catch (SessionChangedException cancelled) {
      throw cancelled;
    } catch (ApiException error) {
      requireCurrent(s);
      if (fallback && (error.statusCode >= 500 || error.rateLimited)) {
        Cache.Entry e = cached(key(s, url));
        if (e != null) return e.payload;
      }
      throw error;
    } catch (IOException error) {
      requireCurrent(s);
      if (fallback) {
        Cache.Entry e = cached(key(s, url));
        if (e != null) return e.payload;
      }
      throw error;
    }
  }

  private String request(String method, String url, String body, boolean auth) throws Exception {
    GitHubSession s = capture();
    return request(s, method, url, body, auth, true);
  }

  public JSONObject postJson(String url, JSONObject body, boolean auth) throws Exception {
    return json(request("POST", url, body == null ? null : body.toString(), auth));
  }

  public JSONObject patchJson(String url, JSONObject body) throws Exception {
    return json(request("PATCH", url, body == null ? null : body.toString(), true));
  }

  public JSONObject putJson(String url, JSONObject body) throws Exception {
    return json(request("PUT", url, body == null ? "{}" : body.toString(), true));
  }

  public JSONObject executeJson(String method, String url, JSONObject body, boolean auth)
      throws Exception {
    return json(request(method, url, body == null ? null : body.toString(), auth));
  }

  public String executeText(String method, String url, String body, boolean auth) throws Exception {
    return request(method, url, body, auth);
  }

  public void patch(String url, JSONObject body) throws Exception {
    request("PATCH", url, body == null ? null : body.toString(), true);
  }

  public void put(String url, JSONObject body) throws Exception {
    request("PUT", url, body == null ? "{}" : body.toString(), true);
  }

  public void putEmpty(String url) throws Exception {
    request("PUT", url, "", true);
  }

  public void delete(String url) throws Exception {
    request("DELETE", url, null, true);
  }

  private static JSONObject json(String s) throws Exception {
    try {
      return new JSONObject(s == null || s.trim().isEmpty() ? "{}" : s);
    } catch (org.json.JSONException invalid) {
      throw new MutationOutcomeUnknownException(200, "Ответ записи не удалось прочитать", 0);
    }
  }

  private HttpURLConnection open(String method, String url, GitHubSession s, boolean auth)
      throws Exception {
    requireCurrent(s);
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
    c.setInstanceFollowRedirects(false);
    c.setConnectTimeout(15000);
    c.setReadTimeout(35000);
    c.setRequestMethod(method);
    c.setRequestProperty("Accept", "application/vnd.github+json");
    c.setRequestProperty("X-GitHub-Api-Version", "2026-03-10");
    c.setRequestProperty(
        "User-Agent", "GitHubble-Android/" + app.githubble.mobile.BuildConfig.VERSION_NAME);
    if (auth && !s.token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + s.token);
    return c;
  }

  private String request(
      GitHubSession s, String method, String url, String body, boolean auth, boolean conditional)
      throws Exception {
    final String cacheKey = key(s, url);
    final boolean read = "GET".equals(method) || "HEAD".equals(method);
    String target = url;
    int redirects = 0;
    for (int attempt = 1; ; attempt++) {
      requireCurrent(s);
      HttpURLConnection c = open(method, target, s, auth);
      boolean mayHaveSent = false;
      try {
        Cache.Entry previous = "GET".equals(method) && conditional ? cached(cacheKey) : null;
        if (previous != null && previous.etag != null && !previous.etag.isEmpty())
          c.setRequestProperty("If-None-Match", previous.etag);
        if (body != null) {
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          c.setDoOutput(true);
          c.setFixedLengthStreamingMode(bytes.length);
          if (bytes.length > 0)
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
          requireCurrent(s);
          mayHaveSent = true;
          try (OutputStream out = c.getOutputStream()) {
            out.write(bytes);
          }
        }
        requireCurrent(s);
        mayHaveSent = true;
        int code = c.getResponseCode();
        requireCurrent(s);
        updateRateLimit(c);
        if (read && (code == 301 || code == 302 || code == 307 || code == 308) && redirects < 3) {
          String location = c.getHeaderField("Location");
          if (location != null) {
            URL next = new URL(new URL(target), location);
            URL origin = new URL(url);
            int originPort = origin.getPort() < 0 ? origin.getDefaultPort() : origin.getPort();
            int nextPort = next.getPort() < 0 ? next.getDefaultPort() : next.getPort();
            if (origin.getProtocol().equals(next.getProtocol())
                && origin.getHost().equalsIgnoreCase(next.getHost())
                && originPort == nextPort
                && next.getUserInfo() == null) {
              target = next.toString();
              redirects++;
              continue;
            }
          }
        }
        if (code == 304 && previous != null) return previous.payload;
        String response =
            readAll(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
        requireCurrent(s);
        if (code >= 200 && code < 300) {
          if (auth && isIdentityUrl(url)) sessions.reauthorization(s, false);
          String normalized = response.trim().isEmpty() ? "{}" : response;
          if ("GET".equals(method)
              && normalized.length() <= MAX_CACHE_BODY
              && normalized.getBytes(StandardCharsets.UTF_8).length <= MAX_CACHE_BODY) {
            Cache.Entry entry = new Cache.Entry(normalized, c.getHeaderField("ETag"));
            Runnable save =
                () -> {
                  synchronized (memory) {
                    memory.put(cacheKey, entry);
                  }
                  if (cache != null)
                    cache.put(cacheKey, inferType(url), normalized, entry.etag, ttlFor(url));
                };
            if (checkBound) {
              if (!sessions.commitIfCurrent(s, save)) throw new SessionChangedException();
            } else save.run();
          }
          requireCurrent(s);
          return normalized;
        }
        long retryAfter = retryAfter(c);
        boolean limited = code == 429 || (code == 403 && (remaining == 0 || retryAfter > 0));
        if (read
            && (code == 502 || code == 503 || code == 504 || limited)
            && attempt < 3
            && retryAfter <= 30000) {
          c.disconnect();
          Thread.sleep(retryAfter > 0 ? retryAfter : 350L * attempt);
          continue;
        }
        if (code == 401 && auth && isIdentityUrl(url)) sessions.reauthorization(s, true);
        if (!read && (code >= 500 || code == 408))
          throw new MutationOutcomeUnknownException(
              code, humanMessage(code, response), resetEpochSeconds);
        throw new ApiException(
            code, humanMessage(code, response), resetEpochSeconds, limited, retryAfter);
      } catch (SessionChangedException cancelled) {
        if (!read && mayHaveSent)
          throw new MutationOutcomeUnknownException(0, cancelled.getMessage(), resetEpochSeconds);
        throw cancelled;
      } catch (ApiException error) {
        throw error;
      } catch (IOException error) {
        if (!read && mayHaveSent)
          throw new MutationOutcomeUnknownException(0, error.getMessage(), resetEpochSeconds);
        throw error;
      } finally {
        c.disconnect();
      }
    }
  }

  public JSONObject postBase64Blob(String url, InputStream input, long maxBytes) throws Exception {
    if (input == null) throw new IOException("Не удалось открыть выбранный файл");
    GitHubSession s = capture();
    HttpURLConnection c = open("POST", url, s, true);
    c.setReadTimeout(120000);
    c.setDoOutput(true);
    c.setChunkedStreamingMode(64 * 1024);
    c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
    try {
      try (OutputStream raw = c.getOutputStream()) {
        raw.write("{\"content\":\"".getBytes(StandardCharsets.UTF_8));
        OutputStream nonClosing =
            new FilterOutputStream(raw) {
              @Override
              public void close() throws IOException {
                flush();
              }
            };
        Base64OutputStream encoded = new Base64OutputStream(nonClosing, Base64.NO_WRAP);
        byte[] buffer = new byte[64 * 1024];
        int n;
        long copied = 0;
        while ((n = input.read(buffer)) != -1) {
          requireCurrent(s);
          copied += n;
          if (maxBytes > 0 && copied > maxBytes) {
            throw new IOException("Файл превышает допустимый размер GitHub");
          }
          encoded.write(buffer, 0, n);
        }
        encoded.close();
        raw.write("\",\"encoding\":\"base64\"}".getBytes(StandardCharsets.UTF_8));
      }
      int code = c.getResponseCode();
      requireCurrent(s);
      updateRateLimit(c);
      String response =
          readAll(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
      requireCurrent(s);
      if (code >= 200 && code < 300) return json(response);
      throw new ApiException(code, humanMessage(code, response), resetEpochSeconds);
    } finally {
      c.disconnect();
    }
  }

  private static boolean isIdentityUrl(String url) {
    return "https://api.github.com/user".equals(url)
        || url.startsWith("https://api.github.com/user?");
  }

  private static long retryAfter(HttpURLConnection c) {
    String value = c.getHeaderField("Retry-After");
    if (value == null) return 0;
    try {
      return Math.max(0, Long.parseLong(value) * 1000L);
    } catch (Exception ignored) {
      try {
        return Math.max(
            0,
            java.time.ZonedDateTime.parse(
                        value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()
                    .toEpochMilli()
                - System.currentTimeMillis());
      } catch (Exception invalid) {
        return 0;
      }
    }
  }

  private void updateRateLimit(HttpURLConnection c) {
    try {
      remaining = Integer.parseInt(c.getHeaderField("X-RateLimit-Remaining"));
    } catch (Exception ignored) {
      remaining = -1;
    }
    try {
      resetEpochSeconds = Long.parseLong(c.getHeaderField("X-RateLimit-Reset"));
    } catch (Exception ignored) {
      resetEpochSeconds = 0;
    }
  }

  private static String humanMessage(int code, String body) {
    String detail = body == null ? "" : body.replace('\n', ' ');
    if (detail.length() > 180) detail = detail.substring(0, 180) + "…";
    switch (code) {
      case 401:
        return "GitHub отклонил авторизацию для этого запроса (HTTP 401). Доступ к другим функциям"
                   + " аккаунта может сохраняться.";
      case 403:
        return "GitHub отклонил запрос: недостаточно прав или исчерпан лимит. " + detail;
      case 404:
        return "Объект GitHub не найден или недоступен.";
      case 422:
        return "GitHub не принял параметры запроса. " + detail;
      case 429:
        return "Слишком много запросов. Повторите позже.";
      default:
        return "GitHub API HTTP " + code + ". " + detail;
    }
  }

  private static long ttlFor(String url) {
    if (url.contains("/contents/") || url.endsWith("/readme")) return 24L * 60L * 60L * 1000L;
    if (url.contains("/releases")) return 30L * 60L * 1000L;
    if (url.contains("/issues") || url.contains("/pulls")) return 10L * 60L * 1000L;
    if (url.contains("/users/") || url.endsWith("/user")) return 60L * 60L * 1000L;
    return 15L * 60L * 1000L;
  }

  private static String inferType(String url) {
    if (url.contains("/pulls")) return "pull_request";
    if (url.contains("/issues")) return "issue";
    if (url.contains("/releases")) return "release";
    if (url.contains("/contents/") || url.endsWith("/readme")) return "content";
    if (url.contains("/users/") || url.endsWith("/user")) return "profile";
    if (url.contains("/repos/")) return "repository";
    return "generic";
  }

  private static String readAll(InputStream in) throws IOException {
    if (in == null) return "";
    try (InputStream input = in;
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int n;
      while ((n = input.read(buffer)) != -1) {
        if (out.size() + n > MAX_RESPONSE_BYTES)
          throw new IOException("Ответ GitHub превышает 32 MiB");
        out.write(buffer, 0, n);
      }
      return out.toString(StandardCharsets.UTF_8.name());
    }
  }
}
