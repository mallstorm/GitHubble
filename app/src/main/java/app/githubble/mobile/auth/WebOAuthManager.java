package app.githubble.mobile.auth;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;

import app.githubble.mobile.security.SecureStore;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

public final class WebOAuthManager {
    private static final String PREFS = "githubble_web_oauth";
    private static final String AUTHORIZE_URL = "https://github.com/login/oauth/authorize";
    private final SharedPreferences prefs;
    private final SecureStore secureStore;

    public WebOAuthManager(Context context, SecureStore secureStore) {
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.secureStore = secureStore;
    }

    public static final class Request {
        public final Uri authorizationUri;
        public final String redirectUri;
        Request(Uri authorizationUri, String redirectUri) {
            this.authorizationUri = authorizationUri;
            this.redirectUri = redirectUri;
        }
    }

    public static final class Callback {
        public final String code;
        public final String verifier;
        public final String redirectUri;
        public final String error;
        Callback(String code, String verifier, String redirectUri, String error) {
            this.code = code == null ? "" : code;
            this.verifier = verifier == null ? "" : verifier;
            this.redirectUri = redirectUri == null ? "" : redirectUri;
            this.error = error == null ? "" : error;
        }
        public boolean ok() { return error.isEmpty() && !code.isEmpty() && !verifier.isEmpty(); }
    }

    public Request begin(String clientId, String redirectUri) throws Exception {
        if (clientId == null || clientId.trim().isEmpty()) throw new IllegalStateException("GitHub Client ID не настроен");
        if (redirectUri == null || redirectUri.trim().isEmpty()) throw new IllegalStateException("OAuth callback не настроен");

        String state = randomUrlSafe(32);
        String verifier = randomUrlSafe(64);
        String challenge = Base64.encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.UTF_8)),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);

        prefs.edit()
                .putString("state", state)
                .putString("verifier", verifier)
                .putString("redirect_uri", redirectUri.trim())
                .putLong("started_at", System.currentTimeMillis())
                .apply();

        Uri uri = Uri.parse(AUTHORIZE_URL).buildUpon()
                .appendQueryParameter("client_id", clientId.trim())
                .appendQueryParameter("redirect_uri", redirectUri.trim())
                .appendQueryParameter("state", state)
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("prompt", "select_account")
                .build();
        return new Request(uri, redirectUri.trim());
    }

    public Callback consumeCallback(Uri uri) {
        if (uri == null) return new Callback("", "", "", "GitHub не вернул callback");
        String returnedState = uri.getQueryParameter("state");
        String error = uri.getQueryParameter("error_description");
        if (error == null || error.isEmpty()) error = uri.getQueryParameter("error");
        String code = uri.getQueryParameter("code");

        String expectedState = prefs.getString("state", "");
        String verifier = prefs.getString("verifier", "");
        String redirectUri = prefs.getString("redirect_uri", "");
        long startedAt = prefs.getLong("started_at", 0L);
        prefs.edit().clear().apply();

        if (error != null && !error.isEmpty()) return new Callback("", "", redirectUri, error);
        if (startedAt <= 0L || System.currentTimeMillis() - startedAt > 15 * 60 * 1000L) {
            return new Callback("", "", redirectUri, "Сеанс входа истёк. Начните вход заново.");
        }
        if (returnedState == null || expectedState.isEmpty() || !expectedState.equals(returnedState)) {
            return new Callback("", "", redirectUri, "Проверка state не прошла. Вход отменён.");
        }
        if (code == null || code.isEmpty() || verifier.isEmpty()) {
            return new Callback("", "", redirectUri, "GitHub не вернул authorization code");
        }
        return new Callback(code, verifier, redirectUri, "");
    }

    public void exchangeWithBackend(String backendBase, Callback callback) throws Exception {
        if (callback == null || !callback.ok()) throw new IllegalStateException(callback == null ? "Пустой OAuth callback" : callback.error);
        String base = backendBase == null ? "" : backendBase.trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.isEmpty()) throw new IllegalStateException("OAuth backend не настроен");
        if (!base.startsWith("https://")) throw new IllegalStateException("OAuth backend должен использовать HTTPS");

        HttpURLConnection connection = (HttpURLConnection) new URL(base + "/oauth/github/exchange").openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("User-Agent", "GitHubble-Android");

        JSONObject body = new JSONObject();
        body.put("code", callback.code);
        body.put("code_verifier", callback.verifier);
        body.put("redirect_uri", callback.redirectUri);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int status = connection.getResponseCode();
        String raw = readAll(status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream());
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("OAuth backend HTTP " + status + ": " + limit(raw, 300));
        }

        JSONObject response = new JSONObject(raw);
        String token = response.optString("access_token", "");
        if (token.isEmpty()) throw new IllegalStateException("OAuth backend не вернул access_token");

        secureStore.put("github_token", token);
        secureStore.put("github_token_type", response.optString("token_type", "bearer"));
        secureStore.put("github_token_scope", response.optString("scope", ""));
        secureStore.put("github_refresh_token", response.optString("refresh_token", ""));
        if (response.has("expires_in")) {
            secureStore.put("github_token_expires_at",
                    String.valueOf(System.currentTimeMillis() + response.optLong("expires_in", 0L) * 1000L));
        }
    }

    public void clearPending() { prefs.edit().clear().apply(); }

    private static String randomUrlSafe(int bytes) {
        byte[] data = new byte[bytes];
        new SecureRandom().nextBytes(data);
        return Base64.encodeToString(data, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
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

    private static String limit(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
