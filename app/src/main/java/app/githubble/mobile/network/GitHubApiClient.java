package app.githubble.mobile.network;

import app.githubble.mobile.security.SecureStore;
import android.util.Base64;
import android.util.Base64OutputStream;
import app.githubble.mobile.cache.EntityCache;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Centralized GitHub HTTP client with typed errors, rate-limit metadata and ETag cache. */
public final class GitHubApiClient {
    public static final class ApiException extends IOException {
        public final int statusCode;
        public final long rateLimitResetEpochSeconds;
        public ApiException(int statusCode, String message, long reset) {
            super(message); this.statusCode = statusCode; this.rateLimitResetEpochSeconds = reset;
        }
    }

    private final SecureStore secureStore;
    private final EntityCache entityCache;
    private final Map<String,String> etags = new ConcurrentHashMap<>();
    private final Map<String,String> cachedBodies = new ConcurrentHashMap<>();
    private volatile int remaining = -1;
    private volatile long resetEpochSeconds = 0;

    public GitHubApiClient(SecureStore secureStore) { this(secureStore, null); }
    public GitHubApiClient(SecureStore secureStore, EntityCache entityCache) { this.secureStore = secureStore; this.entityCache = entityCache; }
    public int getRemaining() { return remaining; }
    public long getResetEpochSeconds() { return resetEpochSeconds; }
    public void invalidate(String url) { if(url==null)return; etags.remove(url); cachedBodies.remove(url); if(entityCache!=null)entityCache.remove(url); }
    public void invalidatePrefix(String prefix) { if(prefix==null)return; for(String key:new java.util.ArrayList<>(cachedBodies.keySet()))if(key.startsWith(prefix)){etags.remove(key);cachedBodies.remove(key);} if(entityCache!=null)entityCache.removePrefix(prefix); }

    public JSONObject getJson(String url) throws Exception { return new JSONObject(getWithFallback(url, true)); }
    public JSONArray getArray(String url) throws Exception { return new JSONArray(getWithFallback(url, true)); }
    public String getText(String url) throws Exception { return getWithFallback(url, true); }

    private String getWithFallback(String url, boolean authenticated) throws Exception {
        try { return request("GET", url, null, authenticated); }
        catch (ApiException error) {
            if ((error.statusCode >= 500 || error.statusCode == 429) && entityCache != null) { EntityCache.Entry entry = entityCache.get(url, true); if(entry != null) return entry.payload; }
            throw error;
        } catch (IOException error) {
            if (entityCache != null) { EntityCache.Entry entry = entityCache.get(url, true); if(entry != null) return entry.payload; }
            throw error;
        }
    }

    public JSONObject postJson(String url, JSONObject body, boolean authenticated) throws Exception {
        return new JSONObject(request("POST", url, body == null ? null : body.toString(), authenticated));
    }

    /**
     * Streams a binary Git blob as base64 JSON without materializing the whole file in RAM.
     * This is used by Android folder/ZIP imports where individual source artifacts may be tens of MiB.
     */
    public JSONObject postBase64Blob(String url, InputStream input, long maxBytes) throws Exception {
        if (input == null) throw new IOException("Не удалось открыть выбранный файл");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(120000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setChunkedStreamingMode(64 * 1024);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("X-GitHub-Api-Version", "2026-03-10");
        c.setRequestProperty("User-Agent", "GitHubble-Android/1.0-rc3.2");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        String token = secureStore.get("github_token");
        if (!token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
        long copied = 0;
        try (OutputStream raw = c.getOutputStream()) {
            raw.write("{\"content\":\"".getBytes(StandardCharsets.UTF_8));
            OutputStream nonClosing = new FilterOutputStream(raw) { @Override public void close() throws IOException { flush(); } };
            Base64OutputStream encoded = new Base64OutputStream(nonClosing, Base64.NO_WRAP);
            byte[] buffer = new byte[64 * 1024]; int n;
            while ((n = input.read(buffer)) != -1) {
                copied += n;
                if (maxBytes > 0 && copied > maxBytes) throw new IOException("Файл превышает допустимый размер GitHub");
                encoded.write(buffer, 0, n);
            }
            encoded.close();
            raw.write("\",\"encoding\":\"base64\"}".getBytes(StandardCharsets.UTF_8));
            raw.flush();
        }
        int code = c.getResponseCode();
        updateRateLimit(c);
        String response = readAll(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
        if (code >= 200 && code < 300) return new JSONObject(response == null || response.trim().isEmpty() ? "{}" : response);
        throw new ApiException(code, humanMessage(code, response), resetEpochSeconds);
    }

    public JSONObject patchJson(String url, JSONObject body) throws Exception {
        return new JSONObject(request("PATCH", url, body == null ? null : body.toString(), true));
    }

    public JSONObject putJson(String url, JSONObject body) throws Exception {
        return new JSONObject(request("PUT", url, body == null ? "{}" : body.toString(), true));
    }

    /** Generic JSON mutation used by the durable offline queue and feature controllers. */
    public JSONObject executeJson(String method, String url, JSONObject body, boolean authenticated) throws Exception {
        String payload = body == null ? null : body.toString();
        String response = request(method, url, payload, authenticated);
        return response == null || response.trim().isEmpty() ? new JSONObject() : new JSONObject(response);
    }

    public String executeText(String method, String url, String body, boolean authenticated) throws Exception {
        return request(method, url, body, authenticated);
    }

    public void patch(String url, JSONObject body) throws Exception {
        request("PATCH", url, body == null ? null : body.toString(), true);
    }

    public void put(String url, JSONObject body) throws Exception {
        request("PUT", url, body == null ? "{}" : body.toString(), true);
    }

    /** Sends a zero-length authenticated PUT required by GitHub star/follow endpoints. */
    public void putEmpty(String url) throws Exception {
        request("PUT", url, "", true);
    }

    public void delete(String url) throws Exception {
        request("DELETE", url, null, true);
    }

    private String request(String method, String url, String body, boolean authenticated) throws Exception {
        int attempts = 0;
        while (true) {
            attempts++;
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(35000);
            c.setRequestMethod(method);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("X-GitHub-Api-Version", "2026-03-10");
            c.setRequestProperty("User-Agent", "GitHubble-Android/1.0-rc3.2");
            String token = secureStore.get("github_token");
            if (authenticated && !token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
            if ("GET".equals(method)) {
                String tag = etags.get(url);
                if ((tag == null || tag.isEmpty()) && entityCache != null) {
                    EntityCache.Entry entry = entityCache.get(url, true);
                    if (entry != null) { tag = entry.etag; cachedBodies.put(url, entry.payload); if(tag != null) etags.put(url, tag); }
                }
                if (tag != null && !tag.isEmpty()) c.setRequestProperty("If-None-Match", tag);
            }
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(bytes.length);
                if (bytes.length > 0) c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream out = c.getOutputStream()) {
                    if (bytes.length > 0) out.write(bytes);
                }
            }
            int code = c.getResponseCode();
            updateRateLimit(c);
            if (code == 304) {
                if (cachedBodies.containsKey(url)) return cachedBodies.get(url);
                if (entityCache != null) { EntityCache.Entry entry = entityCache.get(url, true); if(entry != null) return entry.payload; }
            }
            String response = readAll(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
            if (code >= 200 && code < 300) {
                String etag = c.getHeaderField("ETag");
                String normalized = response == null || response.trim().isEmpty() ? "{}" : response;
                if ("GET".equals(method)) {
                    if (etag != null) etags.put(url, etag);
                    cachedBodies.put(url, normalized);
                    if (entityCache != null) entityCache.put(url, inferType(url), normalized, etag, ttlFor(url));
                }
                return normalized;
            }
            if ((code == 502 || code == 503 || code == 504) && attempts < 3) {
                Thread.sleep(350L * attempts); continue;
            }
            throw new ApiException(code, humanMessage(code, response), resetEpochSeconds);
        }
    }

    private void updateRateLimit(HttpURLConnection c) {
        try { remaining = Integer.parseInt(value(c.getHeaderField("X-RateLimit-Remaining"), "-1")); } catch (Exception ignored) {}
        try { resetEpochSeconds = Long.parseLong(value(c.getHeaderField("X-RateLimit-Reset"), "0")); } catch (Exception ignored) {}
    }

    private static String humanMessage(int code, String body) {
        String detail = body == null ? "" : body.replace('\n',' ');
        if (detail.length() > 180) detail = detail.substring(0,180) + "…";
        switch (code) {
            case 401: return "Сессия GitHub недействительна. Выполните вход заново.";
            case 403: return "GitHub отклонил запрос: недостаточно прав или исчерпан лимит. " + detail;
            case 404: return "Объект GitHub не найден или недоступен.";
            case 422: return "GitHub не принял параметры запроса. " + detail;
            case 429: return "Слишком много запросов. Повторите позже.";
            default: return "GitHub API HTTP " + code + ". " + detail;
        }
    }

    private static long ttlFor(String url) {
        if (url.contains("/contents/") || url.endsWith("/readme")) return 24L*60L*60L*1000L;
        if (url.contains("/releases")) return 30L*60L*1000L;
        if (url.contains("/issues") || url.contains("/pulls")) return 10L*60L*1000L;
        if (url.contains("/users/") || url.endsWith("/user")) return 60L*60L*1000L;
        return 15L*60L*1000L;
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

    private static String value(String value, String fallback) { return value == null ? fallback : value; }
    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = input.read(buffer)) != -1) out.write(buffer,0,n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
