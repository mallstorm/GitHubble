package app.githubble.mobile.auth;

import android.content.Context;

import app.githubble.mobile.security.SecureStore;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * GitHub OAuth Device Flow for installed clients.
 *
 * The user authenticates only on the official github.com page, where GitHub can
 * offer password, Google, Apple, passkey, 2FA, security key, or SSO according
 * to the account configuration. No GitHub password is ever entered in GitHubble.
 */
public final class OAuthManager {
    private static final String DEVICE_CODE_URL = "https://github.com/login/device/code";
    private static final String ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final String DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";

    public static final class DeviceSession {
        public final String deviceCode;
        public final String userCode;
        public final String verificationUri;
        public final int expiresInSeconds;
        public final int intervalSeconds;
        public final long startedAtMillis;

        DeviceSession(String deviceCode, String userCode, String verificationUri,
                      int expiresInSeconds, int intervalSeconds) {
            this.deviceCode = deviceCode;
            this.userCode = userCode;
            this.verificationUri = verificationUri;
            this.expiresInSeconds = expiresInSeconds;
            this.intervalSeconds = Math.max(5, intervalSeconds);
            this.startedAtMillis = System.currentTimeMillis();
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - startedAtMillis >= expiresInSeconds * 1000L;
        }
    }

    public enum PollState {
        AUTHORIZED,
        PENDING,
        SLOW_DOWN,
        DENIED,
        EXPIRED,
        ERROR
    }

    public static final class PollResult {
        public final PollState state;
        public final String message;
        public final int nextIntervalSeconds;

        PollResult(PollState state, String message, int nextIntervalSeconds) {
            this.state = state;
            this.message = message == null ? "" : message;
            this.nextIntervalSeconds = nextIntervalSeconds;
        }
    }

    private final SecureStore secureStore;

    public OAuthManager(Context context, SecureStore secureStore) {
        this.secureStore = secureStore;
    }

    public DeviceSession beginDeviceFlow(String clientId, String scopes) throws Exception {
        if (clientId == null || clientId.trim().isEmpty()) {
            throw new IllegalStateException("GitHub OAuth Client ID не настроен в этой сборке");
        }
        String body = "client_id=" + enc(clientId.trim()) + "&scope=" + enc(scopes == null ? "" : scopes);
        JSONObject response = postForm(DEVICE_CODE_URL, body);
        String deviceCode = response.optString("device_code", "");
        String userCode = response.optString("user_code", "");
        String verificationUri = response.optString("verification_uri", "https://github.com/login/device");
        if (deviceCode.isEmpty() || userCode.isEmpty()) {
            throw new IllegalStateException("GitHub не вернул код авторизации");
        }
        return new DeviceSession(
            deviceCode,
            userCode,
            verificationUri,
            response.optInt("expires_in", 900),
            response.optInt("interval", 5)
        );
    }

    public PollResult pollDeviceFlow(String clientId, DeviceSession session, int currentIntervalSeconds) throws Exception {
        if (session == null || session.isExpired()) {
            return new PollResult(PollState.EXPIRED, "Срок действия кода истёк", currentIntervalSeconds);
        }
        String body = "client_id=" + enc(clientId.trim())
            + "&device_code=" + enc(session.deviceCode)
            + "&grant_type=" + enc(DEVICE_GRANT);
        JSONObject response = postForm(ACCESS_TOKEN_URL, body);
        String accessToken = response.optString("access_token", "");
        if (!accessToken.isEmpty()) {
            secureStore.put("github_token", accessToken);
            String scope = response.optString("scope", "");
            String tokenType = response.optString("token_type", "bearer");
            secureStore.put("github_token_scope", scope);
            secureStore.put("github_token_type", tokenType);
            long nowSeconds = System.currentTimeMillis() / 1000L;
            long expiresIn = response.optLong("expires_in", 0L);
            long refreshExpiresIn = response.optLong("refresh_token_expires_in", 0L);
            secureStore.put("github_token_expires_at", expiresIn > 0L ? String.valueOf(nowSeconds + expiresIn) : "");
            secureStore.put("github_refresh_token", response.optString("refresh_token", ""));
            secureStore.put("github_refresh_token_expires_at", refreshExpiresIn > 0L ? String.valueOf(nowSeconds + refreshExpiresIn) : "");
            secureStore.remove("github_reauth_required");
            return new PollResult(PollState.AUTHORIZED, "Готово", currentIntervalSeconds);
        }

        String error = response.optString("error", "");
        String description = response.optString("error_description", "");
        switch (error) {
            case "authorization_pending":
                return new PollResult(PollState.PENDING, "Ожидаем подтверждение на GitHub", currentIntervalSeconds);
            case "slow_down":
                return new PollResult(PollState.SLOW_DOWN, "GitHub просит опрашивать реже", currentIntervalSeconds + 5);
            case "access_denied":
                return new PollResult(PollState.DENIED, "Пользователь отменил вход", currentIntervalSeconds);
            case "expired_token":
                return new PollResult(PollState.EXPIRED, "Срок действия кода истёк", currentIntervalSeconds);
            default:
                return new PollResult(PollState.ERROR,
                    description.isEmpty() ? (error.isEmpty() ? "Неизвестная ошибка OAuth" : error) : description,
                    currentIntervalSeconds);
        }
    }

    public void signOut() {
        secureStore.remove("github_token");
        secureStore.remove("github_token_scope");
        secureStore.remove("github_token_type");
        secureStore.remove("github_refresh_token");
        secureStore.remove("github_token_expires_at");
        secureStore.remove("github_refresh_token_expires_at");
        secureStore.remove("github_reauth_required");
        secureStore.remove("github_login");
        secureStore.remove("github_name");
    }

    public boolean hasUsableToken() {
        if (secureStore.get("github_token").isEmpty()) return false;
        if ("true".equals(secureStore.get("github_reauth_required"))) return false;
        String expires = secureStore.get("github_token_expires_at");
        if (expires.isEmpty()) return true;
        try { return Long.parseLong(expires) > System.currentTimeMillis()/1000L + 30L; }
        catch (Exception e) { return false; }
    }

    public boolean canRefresh() {
        String refresh = secureStore.get("github_refresh_token");
        if (refresh.isEmpty()) return false;
        String expires = secureStore.get("github_refresh_token_expires_at");
        if (expires.isEmpty()) return true;
        try { return Long.parseLong(expires) > System.currentTimeMillis()/1000L + 60L; }
        catch (Exception e) { return false; }
    }

    /** Atomically rotates expiring GitHub App user tokens when refresh data is available. */
    public synchronized boolean refreshIfNeeded(String clientId) throws Exception {
        if (hasUsableToken()) return true;
        if (!canRefresh() || clientId == null || clientId.trim().isEmpty()) return false;
        String body = "client_id=" + enc(clientId.trim())
                + "&grant_type=refresh_token&refresh_token=" + enc(secureStore.get("github_refresh_token"));
        JSONObject response = postForm(ACCESS_TOKEN_URL, body);
        String token = response.optString("access_token", "");
        if (token.isEmpty()) return false;
        long now = System.currentTimeMillis()/1000L;
        secureStore.put("github_token", token);
        secureStore.put("github_token_type", response.optString("token_type", "bearer"));
        secureStore.put("github_token_scope", response.optString("scope", secureStore.get("github_token_scope")));
        secureStore.put("github_token_expires_at", response.optLong("expires_in", 0L)>0L?String.valueOf(now+response.optLong("expires_in")):"");
        String rotated = response.optString("refresh_token", "");
        if (!rotated.isEmpty()) secureStore.put("github_refresh_token", rotated);
        long refreshTtl = response.optLong("refresh_token_expires_in", 0L);
        if (refreshTtl > 0L) secureStore.put("github_refresh_token_expires_at", String.valueOf(now+refreshTtl));
        secureStore.remove("github_reauth_required");
        return true;
    }

    private static JSONObject postForm(String endpoint, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
        connection.setRequestProperty("User-Agent", "GitHubble-Android");
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = connection.getResponseCode();
        String response = readAll(code >= 200 && code < 400
            ? connection.getInputStream() : connection.getErrorStream());
        if (code < 200 || code >= 400) {
            throw new IllegalStateException("GitHub OAuth HTTP " + code + ": " + response);
        }
        return new JSONObject(response);
    }

    private static String enc(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    private static String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
