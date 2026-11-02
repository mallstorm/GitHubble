package app.githubble.mobile.agent;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import app.githubble.mobile.security.SecureStore;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Multi-account AI connection vault for API-key transports. GitHub credentials never leave
 * the GitHub client. Separate provider credentials are held in Android Keystore-backed SecureStore.
 * OAuth/Subscription sessions have a separate migration path and are NOT keys.
 */
public final class AiConnectionStore {
    private static final Set<String> SUPPORTED = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
        "openai", "openrouter", "deepseek", "groq", "xai", "mistral",
        "together", "fireworks", "cerebras", "sambanova", "perplexity",
        "alibaba", "kimi", "minimax", "zai", "siliconflow", "nvidia"
    )));
    private final SharedPreferences preferences;
    private final SecureStore vault;

    public AiConnectionStore(Context context, SecureStore secureStore) {
        this.preferences = context.getApplicationContext().getSharedPreferences(
                "githubble_omnichat_connections_v1", Context.MODE_PRIVATE);
        this.vault = secureStore;
    }

    public static Set<String> supportedIds() { return SUPPORTED; }
    public static boolean supported(String provider) { return SUPPORTED.contains(provider); }

    private static String segment(String value) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("Нет идентификатора аккаунта");
        return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static void validateProvider(String provider) {
        if (!supported(provider)) throw new IllegalArgumentException(
                "Этот способ подключения пока не поддерживается: " + provider);
    }

    private String meta(String account) { return "active_" + segment(account); }
    private String modelKey(String account, String provider) {
        validateProvider(provider);
        return "model_" + segment(account) + "_" + provider;
    }
    private String secretKey(String account, String provider) {
        validateProvider(provider);
        return "ai_api_" + segment(account) + "_" + provider;
    }

    public synchronized String selected(String account) {
        String value = preferences.getString(meta(account), "openai");
        return supported(value) ? value : "openai";
    }

    public synchronized void select(String account, String provider) {
        validateProvider(provider);
        preferences.edit().putString(meta(account), provider).apply();
    }

    public synchronized String model(String account, String provider) {
        return preferences.getString(modelKey(account, provider), "");
    }

    public synchronized boolean connected(String account, String provider) {
        return !vault.get(secretKey(account, provider)).isEmpty();
    }

    public synchronized void configure(String account, String provider, String model, String suppliedKey) {
        validateProvider(provider);
        String normalizedModel = model == null ? "" : model.trim();
        if (normalizedModel.isEmpty() || normalizedModel.length() > 180 || !normalizedModel.matches("[A-Za-z0-9._:/+\\-]{1,180}"))
            throw new IllegalArgumentException("Укажите действительный ID модели из каталога провайдера");
        String key = suppliedKey == null ? "" : suppliedKey.trim();
        if (!key.isEmpty()) {
            if (key.length() > 8192 || key.contains("\n") || key.contains("\r"))
                throw new IllegalArgumentException("Недопустимый формат API-ключа");
            vault.put(secretKey(account, provider), key);
        } else if (!connected(account, provider)) {
            throw new IllegalArgumentException("Для нового подключения требуется API-ключ");
        }
        preferences.edit().putString(modelKey(account, provider), normalizedModel)
                .putString(meta(account), provider).apply();
    }

    public synchronized void disconnect(String account, String provider) {
        validateProvider(provider);
        vault.remove(secretKey(account, provider));
    }

    /** Caller must keep this credential in a short-lived network operation, never in UI/logs. */
    public synchronized Credentials snapshot(String account) {
        String provider = selected(account);
        String model = model(account, provider);
        String key = vault.get(secretKey(account, provider));
        if (key.isEmpty() || model.isEmpty())
            throw new IllegalStateException("Настройте подключение " + provider + " в разделе AI-подключений");
        return new Credentials(provider, model, key);
    }

    public static final class Credentials {
        public final String provider;
        public final String model;
        public final String apiKey;
        public Credentials(String provider, String model, String apiKey) {
            this.provider = provider;
            this.model = model;
            this.apiKey = apiKey;
        }
    }
}
