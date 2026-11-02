package app.githubble.mobile.agent;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import app.githubble.mobile.security.SecureStore;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase-2 native API-key connection store. Credentials are AES/GCM encrypted by Android Keystore,
 * scoped to the GitHub account, and are never stored in chat history or ordinary preferences.
 * OAuth/Subscription connections are intentionally not fabricated here.
 */
public final class OmniChatConnectionStore {
    private final SharedPreferences prefs;
    private final SecureStore vault;
    private static final LinkedHashMap<String, String> NAMES = new LinkedHashMap<>();
    static {
        NAMES.put("openai", "OpenAI API");
        NAMES.put("gemini", "Google Gemini API");
        NAMES.put("openrouter", "OpenRouter API");
        NAMES.put("groq", "Groq API");
        NAMES.put("deepseek", "DeepSeek API");
        NAMES.put("xai", "xAI API");
        NAMES.put("mistral", "Mistral API");
        NAMES.put("together", "Together AI API");
        NAMES.put("cerebras", "Cerebras API");
        NAMES.put("nvidia", "NVIDIA NIM API");
    }

    public OmniChatConnectionStore(Context context, SecureStore vault) {
        this.prefs = context.getApplicationContext().getSharedPreferences(
                "githubble_omnichat_connections_v2", Context.MODE_PRIVATE);
        this.vault = vault;
    }

    public static Map<String, String> supported() {
        return Collections.unmodifiableMap(NAMES);
    }

    private static void checkProvider(String provider) {
        if (!NAMES.containsKey(provider)) throw new IllegalArgumentException("Провайдер не поддерживается: " + provider);
    }

    private static String namespace(String account) {
        if (account == null || account.isEmpty() || "guest".equals(account))
            throw new IllegalStateException("Не выбран GitHub-аккаунт");
        return Base64.encodeToString(account.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String key(String account, String provider) {
        checkProvider(provider);
        return "omnichat_key_v2_" + namespace(account) + "_" + provider;
    }

    public synchronized void save(String account, String provider, String model, String apiKey) {
        checkProvider(provider);
        String name = model == null ? "" : model.trim();
        if (name.isEmpty() || name.length() > 160 || name.contains("\n") || name.contains("\r"))
            throw new IllegalArgumentException("Укажите действительную модель");
        String credential = apiKey == null ? "" : apiKey.trim();
        if (!credential.isEmpty()) {
            if (credential.length() > 8192 || credential.contains("\n") || credential.contains("\r"))
                throw new IllegalArgumentException("Некорректный API-ключ");
            vault.put(key(account, provider), credential);
        } else if (!hasKey(account, provider)) {
            throw new IllegalArgumentException("Введите API-ключ");
        }
        String scope = namespace(account);
        prefs.edit()
                .putString(scope + "_provider", provider)
                .putString(scope + "_model_" + provider, name)
                .apply();
    }

    public synchronized void select(String account, String provider) {
        checkProvider(provider);
        if (!isConfigured(account, provider))
            throw new IllegalStateException("Сначала настройте подключение " + NAMES.get(provider));
        prefs.edit().putString(namespace(account) + "_provider", provider).apply();
    }

    public synchronized String selectedProvider(String account) {
        String selected = prefs.getString(namespace(account) + "_provider", "");
        return NAMES.containsKey(selected) ? selected : "";
    }

    public synchronized String model(String account, String provider) {
        checkProvider(provider);
        return prefs.getString(namespace(account) + "_model_" + provider, "");
    }

    public synchronized boolean hasKey(String account, String provider) {
        return !vault.get(key(account, provider)).isEmpty();
    }

    public synchronized boolean isConfigured(String account, String provider) {
        return hasKey(account, provider) && !model(account, provider).isEmpty();
    }

    public synchronized String credential(String account, String provider) {
        return vault.get(key(account, provider));
    }

    public synchronized void disconnect(String account, String provider) {
        checkProvider(provider);
        vault.remove(key(account, provider));
        String scope = namespace(account);
        SharedPreferences.Editor change = prefs.edit().remove(scope + "_model_" + provider)
                .remove(scope + "_models_" + provider);
        if (provider.equals(selectedProvider(account))) change.remove(scope + "_provider");
        change.apply();
    }

    /** Last server catalog is retained across restarts; it never implies model entitlement. */
    public synchronized void cacheModels(String account, String provider, java.util.List<String> models) {
        checkProvider(provider);
        org.json.JSONArray values = new org.json.JSONArray();
        for (String model : models) if (model != null && !model.isBlank() && model.length() <= 160)
            values.put(model);
        prefs.edit().putString(namespace(account) + "_models_" + provider, values.toString()).apply();
    }

    public synchronized java.util.List<String> models(String account, String provider) {
        checkProvider(provider);
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        String selected = model(account, provider);
        if (!selected.isEmpty()) result.add(selected);
        try {
            org.json.JSONArray values = new org.json.JSONArray(prefs.getString(
                    namespace(account) + "_models_" + provider, "[]"));
            for (int i=0;i<values.length();i++) {
                String value=values.optString(i);
                if(!value.isEmpty())result.add(value);
            }
        } catch (Exception ignored) { /* An invalid cache must not break the current connection. */ }
        return new java.util.ArrayList<>(result);
    }

    public String displayName(String provider) {
        return NAMES.containsKey(provider) ? NAMES.get(provider) : "Не подключено";
    }
}

