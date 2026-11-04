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
 * scoped to the local guest or signed-in app profile, and are never stored in chat history or ordinary preferences.
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
        NAMES.put(OAuthSubscriptions.OPENAI,"OpenAI · подписка ChatGPT/Codex");
        NAMES.put(OAuthSubscriptions.CLINE,"Cline Account / Cline Pass");
        NAMES.put(OAuthSubscriptions.KIMI,"Kimi Code · OAuth");
        NAMES.put(OAuthSubscriptions.GROK,"xAI Grok · OAuth");
        NAMES.put("claude-subscription","Claude Code · OAuth");
        NAMES.put("gemini-antigravity","Gemini Antigravity · OAuth");
        NAMES.put("chatgpt-plan","ChatGPT · OAuth без Codex CLI");
        NAMES.put("proxy-chatgpt","ChatGPT · Subscription Proxy");
        NAMES.put("proxy-gemini","Gemini / Cloud Code · Subscription Proxy");
        NAMES.put("proxy-claude","Claude · Subscription Proxy");
        NAMES.put("proxy-grok","Grok · Subscription Proxy");
    }

    public OmniChatConnectionStore(Context context, SecureStore vault) {
        this.prefs = context.getApplicationContext().getSharedPreferences(
                "githubble_omnichat_connections_v2", Context.MODE_PRIVATE);
        this.vault = vault;
        app.githubble.mobile.network.NativeProviderCatalog.initialize(context);
        NAMES.putAll(app.githubble.mobile.network.NativeProviderCatalog.names());
    }

    public static Map<String, String> supported() {
        return Collections.unmodifiableMap(NAMES);
    }

    private static void checkProvider(String provider) {
        if (!NAMES.containsKey(provider)) throw new IllegalArgumentException("Провайдер не поддерживается: " + provider);
    }

    private static String namespace(String account) {
        // Device-local AI profile is independent of GitHub OAuth.
        // Its encrypted credentials remain isolated from signed-in profiles.
        if(account==null||account.isBlank())
            throw new IllegalArgumentException("Не выбран профиль AI");
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
        if(credential.isEmpty()&&!hasKey(account,provider)&&
            ("ollama".equals(provider)||"custom".equals(provider)||"proxy-external".equals(provider)))credential="__local_no_auth__";
        if (!credential.isEmpty()) {
            if(OAuthSubscriptions.supports(provider))throw new IllegalArgumentException("Для подписки используйте OAuth-вход");
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

    public static Map<String,String> apiSupported(){
        LinkedHashMap<String,String> result=new LinkedHashMap<>();
        for(Map.Entry<String,String> e:NAMES.entrySet())if(!OAuthSubscriptions.supports(e.getKey())&&!e.getKey().startsWith("proxy-"))result.put(e.getKey(),e.getValue());
        return Collections.unmodifiableMap(result);
    }

    public synchronized void cacheModelGroups(String account,String provider,org.json.JSONObject groups){
        prefs.edit().putString(namespace(account)+"_groups_"+provider,groups.toString()).apply();
    }
    public synchronized String modelGroup(String account,String provider,String model){
        try{org.json.JSONObject groups=new org.json.JSONObject(prefs.getString(namespace(account)+"_groups_"+provider,"{}"));
            for(String group:new String[]{"Free","Pass","Billing"}){
                org.json.JSONArray a=groups.optJSONArray(group);if(a==null)continue;
                for(int i=0;i<a.length();i++)if(model.equals(a.optString(i)))return group;
            }
        }catch(Exception ignored){}
        if(ClineModelGroups.isFree(model))return "Free";
        if(model.startsWith("cline-pass/"))return "Pass";
        return "";
    }

    public synchronized void saveSubscription(String account,String provider,org.json.JSONObject session,java.util.List<String> models){
        if(!OAuthSubscriptions.supports(provider))throw new IllegalArgumentException("Не OAuth-провайдер");
        if(session.optString("access_token").isEmpty()||!OAuthSubscriptions.GROK.equals(app.githubble.mobile.network.NativeSubscriptionRuntime.base(provider))&&session.optString("refresh_token").isEmpty())throw new IllegalArgumentException("Нет подтверждённой OAuth-сессии");
        try{session=new org.json.JSONObject(session.toString()).put("owner",account).put("provider",provider);
            vault.put(key(account,provider),session.toString());
            String scope=namespace(account);
            prefs.edit().putString(scope+"_provider",provider).putString(scope+"_model_"+provider,models.isEmpty()?"":models.get(0)).apply();
            cacheModels(account,provider,models);
        }catch(org.json.JSONException e){throw new IllegalStateException(e);}
    }

    public synchronized void replaceSubscription(String account,String provider,String expected,org.json.JSONObject session){
        if(!OAuthSubscriptions.supports(provider)||!credential(account,provider).equals(expected))
            throw new IllegalStateException("OAuth-сессия отключена или изменена");
        try{vault.put(key(account,provider),new org.json.JSONObject(session.toString()).put("owner",account).put("provider",provider).toString());}
        catch(org.json.JSONException e){throw new IllegalStateException(e);}
    }

    public synchronized void selectAuthenticated(String account,String provider){
        if(!OAuthSubscriptions.supports(provider)||!hasKey(account,provider))throw new IllegalStateException("Подписка не авторизована");
        prefs.edit().putString(namespace(account)+"_provider",provider).apply();
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
                .remove(scope + "_models_" + provider).remove(scope+"_groups_"+provider);
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
    public synchronized org.json.JSONObject configuration(String account,String provider){
        try{
            String raw=prefs.getString(namespace(account)+"_config_"+provider,"");
            return raw.isEmpty()?app.githubble.mobile.network.NativeProviderCatalog.configuration(provider):new org.json.JSONObject(raw);
        }catch(Exception unavailable){return new org.json.JSONObject();}
    }
    public synchronized void configure(String account,String provider,String base,String protocol,String authScope){
        checkProvider(provider);
        try{
            okhttp3.HttpUrl url=okhttp3.HttpUrl.Companion.get(base.trim());
            app.githubble.mobile.network.NetworkEndpointPolicy.INSTANCE.requireApiUrl(url,true);
            if(!app.githubble.mobile.network.ApiGateway.Companion.getProtocols().contains(protocol))throw new IllegalArgumentException("Неизвестный протокол");
            prefs.edit().putString(namespace(account)+"_config_"+provider,new org.json.JSONObject()
                .put("base_url",base.trim()).put("protocol",protocol).put("auth_scope",authScope.trim()).toString()).apply();
        }catch(Exception invalid){throw new IllegalArgumentException("Проверьте API URL и протокол",invalid);}
    }

    public synchronized void saveApiConnection(String account,String provider,String model,String apiKey){
        checkProvider(provider);
        if(OAuthSubscriptions.supports(provider))throw new IllegalArgumentException("Используйте OAuth");
        String credential=apiKey==null?"":apiKey.trim();
        if(credential.isEmpty()&&!hasKey(account,provider)&&("ollama".equals(provider)||"custom".equals(provider)||"proxy-external".equals(provider)))credential="__local_no_auth__";
        if(!credential.isEmpty()){
            if(credential.length()>8192||credential.contains("\n")||credential.contains("\r"))throw new IllegalArgumentException("Некорректный ключ");
            vault.put(key(account,provider),credential);
        }
        if(!hasKey(account,provider))throw new IllegalArgumentException("Введите ключ");
        android.content.SharedPreferences.Editor editor=prefs.edit().putString(namespace(account)+"_provider",provider);
        if(model!=null&&!model.trim().isEmpty())editor.putString(namespace(account)+"_model_"+provider,model.trim());
        editor.apply();
    }

}



