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
    private final ProviderPool pool;
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
        for (ConnectionCatalog.Account a : ConnectionCatalog.accounts()) NAMES.put(a.id, a.title);
    }

    private final java.util.Set<String> migratedAccounts = new java.util.HashSet<>();

    /**
     * One-time merge of the former "Subscription Proxy" section into the single accounts list.
     * proxy-chatgpt/claude/grok used the very same OAuth client as chatgpt-plan/claude-subscription/
     * grok-subscription, so they become one connection (the canonical one wins); proxy-nvidia was a
     * second NVIDIA NIM key and joins the NVIDIA key pool; the discontinued Google Antigravity login is dropped.
     */
    private synchronized void migrateAliases(String account) {
        if (account == null || account.isBlank() || !migratedAccounts.add(account)) return;
        try {
            String scope = namespace(account);
            for (java.util.Map.Entry<String, String> alias : ConnectionCatalog.legacyAliases().entrySet()) {
                String from = alias.getKey(), to = alias.getValue();
                String fromKey = "omnichat_key_v2_" + scope + "_" + from;
                if (to == null) { // discontinued: remove silently
                    vault.remove(fromKey);
                    pool.removeProvider(account, from);
                    dropPrefs(scope, from);
                    continue;
                }
                if (ConnectionCatalog.isApiAlias(from)) {
                    String single = vault.get(fromKey);
                    if (!single.isEmpty()) {
                        try { pool.addKey(account, to, "", single); } catch (IllegalArgumentException duplicate) { /* already there */ }
                        vault.remove(fromKey);
                    }
                    for (PoolPolicy.Slot slot : pool.slots(account, from)) {
                        String secret = pool.secret(account, from, slot.id);
                        if (secret == null || secret.isEmpty()) continue;
                        try { pool.addKey(account, to, slot.label, secret); } catch (IllegalArgumentException duplicate) { /* already there */ }
                    }
                    pool.removeProvider(account, from);
                    String model = prefs.getString(scope + "_model_" + from, "");
                    if (!model.isEmpty() && prefs.getString(scope + "_model_" + to, "").isEmpty())
                        prefs.edit().putString(scope + "_model_" + to, model).apply();
                    dropPrefs(scope, from);
                } else {
                    String session = vault.get(fromKey);
                    String toKey = "omnichat_key_v2_" + scope + "_" + to;
                    if (!session.isEmpty() && vault.get(toKey).isEmpty()) {
                        try {
                            vault.put(toKey, new org.json.JSONObject(session).put("provider", to).toString());
                            SharedPreferences.Editor e = prefs.edit();
                            for (String part : new String[]{"_model_", "_models_", "_groups_"}) {
                                String value = prefs.getString(scope + part + from, "");
                                if (!value.isEmpty()) e.putString(scope + part + to, value);
                            }
                            e.apply();
                        } catch (org.json.JSONException damaged) { /* an unreadable session is simply dropped */ }
                    }
                    vault.remove(fromKey);
                    dropPrefs(scope, from);
                }
                if (from.equals(prefs.getString(scope + "_provider", "")))
                    prefs.edit().putString(scope + "_provider", to).apply();
            }
            String selected = prefs.getString(scope + "_provider", "");
            if (selected.startsWith("proxy-") && !"proxy-external".equals(selected) || "gemini-antigravity".equals(selected))
                prefs.edit().remove(scope + "_provider").apply();
        } catch (RuntimeException ignored) { /* the merge is best-effort; the old data is never exposed again */ }
    }

    private void dropPrefs(String scope, String provider) {
        prefs.edit().remove(scope + "_model_" + provider).remove(scope + "_models_" + provider)
                .remove(scope + "_groups_" + provider).remove(scope + "_config_" + provider).apply();
    }

    public OmniChatConnectionStore(Context context, SecureStore vault) {
        this.prefs = context.getApplicationContext().getSharedPreferences(
                "githubble_omnichat_connections_v2", Context.MODE_PRIVATE);
        this.vault = vault;
        this.pool = new ProviderPool(context, vault);
        app.githubble.mobile.network.NativeProviderCatalog.initialize(context);
        loadCustomProviders();
        synchronized (NAMES) { NAMES.putAll(app.githubble.mobile.network.NativeProviderCatalog.names()); }
    }

    /** Several keys per service: keys, switches, limits and statistics. */
    public ProviderPool pool() { return pool; }

    public static Map<String, String> supported() {
        synchronized (NAMES) { return Collections.unmodifiableMap(new LinkedHashMap<>(NAMES)); }
    }

    private static void checkProvider(String provider) {
        synchronized (NAMES) {
            if (!NAMES.containsKey(provider)) throw new IllegalArgumentException("Провайдер не поддерживается: " + provider);
        }
    }

    /** API-key services keep their keys in the pool; OAuth subscriptions stay a single session. */
    public static boolean poolable(String provider) {
        return provider != null && !OAuthSubscriptions.supports(provider);
    }

    public static boolean isCustom(String provider) { return provider != null && provider.startsWith("custom-"); }

    private String legacyKey(String account, String provider) { return key(account, provider); }

    /** One-time import of a pre-pool single key into the pool; the pool then owns the credential. */
    private void ensurePool(String account, String provider) {
        if (!poolable(provider)) return;
        String legacy = vault.get(legacyKey(account, provider));
        if (legacy.isEmpty()) return;
        pool.migrateLegacy(account, provider, legacy);
        if (pool.hasSlots(account, provider)) vault.remove(legacyKey(account, provider));
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
            app.githubble.mobile.network.NativeProviderCatalog.keyless(provider))credential="__local_no_auth__";
        if (!credential.isEmpty()) {
            if(OAuthSubscriptions.supports(provider))throw new IllegalArgumentException("Для подписки используйте OAuth-вход");
            if (credential.length() > 8192 || credential.contains("\n") || credential.contains("\r"))
                throw new IllegalArgumentException("Некорректный API-ключ");
            addKeyIfNew(account, provider, "", credential);
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
        for(Map.Entry<String,String> e:NAMES.entrySet())if(!OAuthSubscriptions.supports(e.getKey())&&!ConnectionCatalog.isAccount(e.getKey()))result.put(e.getKey(),e.getValue());
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
        if(session.optString("access_token").isEmpty()||!java.util.Arrays.asList(OAuthSubscriptions.GROK,"openrouter-account","kilo-account").contains(app.githubble.mobile.network.NativeSubscriptionRuntime.base(provider))&&session.optString("refresh_token").isEmpty())throw new IllegalArgumentException("Нет подтверждённой OAuth-сессии");
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
        migrateAliases(account);
        String selected = prefs.getString(namespace(account) + "_provider", "");
        synchronized (NAMES) { return NAMES.containsKey(selected) ? selected : ""; }
    }

    public synchronized String model(String account, String provider) {
        checkProvider(provider);
        return prefs.getString(namespace(account) + "_model_" + provider, "");
    }

    public synchronized boolean hasKey(String account, String provider) {
        migrateAliases(account);
        checkProvider(provider);
        if (poolable(provider)) { ensurePool(account, provider); return pool.hasEnabled(account, provider); }
        return !vault.get(key(account, provider)).isEmpty();
    }

    /** Adds a key to the service pool unless the same key is already there (idempotent for legacy callers). */
    private void addKeyIfNew(String account, String provider, String label, String secret) {
        ensurePool(account, provider);
        if (!poolable(provider)) { vault.put(key(account, provider), secret); return; }
        try { pool.addKey(account, provider, label, secret); }
        catch (IllegalArgumentException duplicate) {
            if (duplicate.getMessage() == null || !duplicate.getMessage().startsWith("Такой ключ уже добавлен")) throw duplicate;
            for (PoolPolicy.Slot s : pool.slots(account, provider))
                if (secret.equals(pool.secret(account, provider, s.id))) pool.setEnabled(account, provider, s.id, true);
        }
    }

    public synchronized boolean isConfigured(String account, String provider) {
        return hasKey(account, provider) && !model(account, provider).isEmpty();
    }

    public synchronized String credential(String account, String provider) {
        migrateAliases(account);
        checkProvider(provider);
        if (poolable(provider)) { ensurePool(account, provider); return pool.primarySecret(account, provider); }
        return vault.get(key(account, provider));
    }

    public synchronized void disconnect(String account, String provider) {
        checkProvider(provider);
        vault.remove(key(account, provider));
        pool.removeProvider(account, provider);
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
        synchronized (NAMES) { return NAMES.containsKey(provider) ? NAMES.get(provider) : "Не подключено"; }
    }
    public synchronized org.json.JSONObject configuration(String account,String provider){
        try{
            if(isCustom(provider))return app.githubble.mobile.network.NativeProviderCatalog.configuration(provider);
            String raw=prefs.getString(namespace(account)+"_config_"+provider,"");
            return raw.isEmpty()?app.githubble.mobile.network.NativeProviderCatalog.configuration(provider):new org.json.JSONObject(raw);
        }catch(Exception unavailable){return new org.json.JSONObject();}
    }
    public synchronized void configure(String account,String provider,String base,String protocol,String authScope){
        checkProvider(provider);
        if(isCustom(provider)){updateCustomProvider(provider,null,base,protocol);return;}
        try{
            String selectedBase=base.trim();
            if("cloudflare".equals(provider)){
                String id=authScope.trim();if(!id.matches("[A-Za-z0-9_-]{6,128}"))throw new IllegalArgumentException("Укажите Cloudflare Account ID в поле Scope");
                selectedBase="https://api.cloudflare.com/client/v4/accounts/"+id+"/ai/v1";
            }
            okhttp3.HttpUrl url=okhttp3.HttpUrl.Companion.get(selectedBase);
            app.githubble.mobile.network.NetworkEndpointPolicy.INSTANCE.requireApiUrl(url,true);
            if(!app.githubble.mobile.network.ApiGateway.Companion.getProtocols().contains(protocol))throw new IllegalArgumentException("Неизвестный протокол");
            prefs.edit().putString(namespace(account)+"_config_"+provider,new org.json.JSONObject()
                .put("base_url",selectedBase).put("protocol",protocol).put("auth_scope",authScope.trim()).toString()).apply();
        }catch(Exception invalid){throw new IllegalArgumentException("Проверьте API URL и протокол",invalid);}
    }

    public synchronized void saveApiConnection(String account,String provider,String model,String apiKey){
        checkProvider(provider);
        if(OAuthSubscriptions.supports(provider))throw new IllegalArgumentException("Используйте OAuth");
        String credential=apiKey==null?"":apiKey.trim();
        if(credential.isEmpty()&&!hasKey(account,provider)&&app.githubble.mobile.network.NativeProviderCatalog.keyless(provider))credential="__local_no_auth__";
        if(!credential.isEmpty()){
            if(credential.length()>8192||credential.contains("\n")||credential.contains("\r"))throw new IllegalArgumentException("Некорректный ключ");
            addKeyIfNew(account,provider,"",credential);
        }
        if(!hasKey(account,provider))throw new IllegalArgumentException("Введите ключ");
        android.content.SharedPreferences.Editor editor=prefs.edit().putString(namespace(account)+"_provider",provider);
        if(model!=null&&!model.trim().isEmpty())editor.putString(namespace(account)+"_model_"+provider,model.trim());
        editor.apply();
    }

    // ------------------------------------------------------------------ user-defined services

    private static final java.util.regex.Pattern CUSTOM_ID = java.util.regex.Pattern.compile("custom-[a-z0-9-]{1,40}");

    private static String validateBase(String base) {
        String value = base == null ? "" : base.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        try {
            okhttp3.HttpUrl url = okhttp3.HttpUrl.Companion.get(value);
            app.githubble.mobile.network.NetworkEndpointPolicy.INSTANCE.requireApiUrl(url, true);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Укажите корректный адрес: https://host/v1 (HTTP — только для localhost)", invalid);
        }
        return value;
    }

    private static String validateProtocol(String protocol) {
        String value = protocol == null ? "" : protocol.trim();
        if (!app.githubble.mobile.network.ApiGateway.Companion.getProtocols().contains(value)
                || app.githubble.mobile.network.ApiGateway.CHATGPT_WEB.equals(value))
            throw new IllegalArgumentException("Неизвестный протокол");
        return value;
    }

    private void loadCustomProviders() {
        try {
            org.json.JSONArray list = new org.json.JSONArray(prefs.getString("custom_providers_v1", "[]"));
            for (int i = 0; i < list.length(); i++) {
                org.json.JSONObject item = list.optJSONObject(i);
                if (item == null || !CUSTOM_ID.matcher(item.optString("id")).matches()) continue;
                app.githubble.mobile.network.NativeProviderCatalog.registerCustom(item.optString("id"),
                        item.optString("name", item.optString("id")), item.optString("base_url"),
                        item.optString("protocol", "openai-chat"), item.optBoolean("keyless"));
            }
        } catch (Exception damaged) { /* a damaged list must not block the app */ }
    }

    private void saveCustomProviders() throws org.json.JSONException {
        org.json.JSONArray list = new org.json.JSONArray();
        for (String id : customProviders()) {
            app.githubble.mobile.network.NativeProviderCatalog.Profile p = app.githubble.mobile.network.NativeProviderCatalog.profile(id);
            list.put(new org.json.JSONObject().put("id", id).put("name", p.getName()).put("base_url", p.getBase())
                    .put("protocol", p.getProtocol()).put("keyless", "none".equals(p.getAuth())));
        }
        prefs.edit().putString("custom_providers_v1", list.toString()).apply();
    }

    /** Ids of user-defined services in creation order. */
    public java.util.List<String> customProviders() {
        java.util.List<String> ids = new java.util.ArrayList<>();
        synchronized (NAMES) { for (String id : NAMES.keySet()) if (isCustom(id)) ids.add(id); }
        return ids;
    }

    /** Creates a service with its own endpoint and wire protocol, e.g. a key reseller or a private proxy. */
    public synchronized String createCustomProvider(String name, String baseUrl, String protocol, boolean keyless) {
        String title = name == null ? "" : name.trim().replace('\n', ' ').replace('\r', ' ');
        if (title.isEmpty() || title.length() > 40) throw new IllegalArgumentException("Название: от 1 до 40 символов");
        String base = validateBase(baseUrl), wire = validateProtocol(protocol);
        String slug = title.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) slug = "api";
        if (slug.length() > 24) slug = slug.substring(0, 24);
        String id = "custom-" + slug;
        synchronized (NAMES) { if (NAMES.containsKey(id)) id = id + "-" + Integer.toHexString((int) (System.nanoTime() & 0xfff)); }
        app.githubble.mobile.network.NativeProviderCatalog.registerCustom(id, title, base, wire, keyless);
        synchronized (NAMES) { NAMES.put(id, title); }
        try { saveCustomProviders(); } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
        return id;
    }

    public synchronized void updateCustomProvider(String provider, String newName, String baseUrl, String protocol) {
        if (!isCustom(provider)) throw new IllegalArgumentException("Это не пользовательский сервис");
        checkProvider(provider);
        app.githubble.mobile.network.NativeProviderCatalog.Profile old = app.githubble.mobile.network.NativeProviderCatalog.profile(provider);
        String title = newName == null || newName.trim().isEmpty() ? old.getName() : newName.trim();
        if (title.length() > 40) throw new IllegalArgumentException("Название: от 1 до 40 символов");
        app.githubble.mobile.network.NativeProviderCatalog.registerCustom(provider, title, validateBase(baseUrl),
                validateProtocol(protocol), "none".equals(old.getAuth()));
        synchronized (NAMES) { NAMES.put(provider, title); }
        try { saveCustomProviders(); } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
    }

    /** Removes the service definition and every key stored for it. */
    public synchronized void deleteCustomProvider(String account, String provider) {
        if (!isCustom(provider)) throw new IllegalArgumentException("Это не пользовательский сервис");
        disconnect(account, provider);
        app.githubble.mobile.network.NativeProviderCatalog.unregisterCustom(provider);
        synchronized (NAMES) { NAMES.remove(provider); }
        try { saveCustomProviders(); } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
    }

    // ------------------------------------------------------------------ cloud restore (never touches secrets)

    /** The endpoint the user saved for a service, or null when the catalog default is in use. */
    public synchronized org.json.JSONObject rawConfiguration(String account, String provider) {
        checkProvider(provider);
        if (isCustom(provider)) return null;
        try {
            String raw = prefs.getString(namespace(account) + "_config_" + provider, "");
            return raw.isEmpty() ? null : new org.json.JSONObject(raw);
        } catch (Exception damaged) { return null; }
    }

    /** Creates or updates a user-defined service under its original id. Invalid data is skipped. */
    public synchronized boolean restoreCustomProvider(String id, String name, String baseUrl, String protocol, boolean keyless) {
        try {
            if (!CUSTOM_ID.matcher(id == null ? "" : id).matches()) return false;
            String title = name == null ? "" : name.trim();
            if (title.isEmpty() || title.length() > 40) return false;
            String base = validateBase(baseUrl), wire = validateProtocol(protocol);
            app.githubble.mobile.network.NativeProviderCatalog.registerCustom(id, title, base, wire, keyless);
            synchronized (NAMES) { NAMES.put(id, title); }
            saveCustomProviders();
            return true;
        } catch (Exception invalid) { return false; }
    }

    /** Puts the model and endpoint decided by the merge into place; keys stay empty until entered. */
    public synchronized void restoreProvider(String account, String provider, String model, String base, String protocol, String scope) {
        if (!supported().containsKey(provider) || !poolable(provider)) return;
        SharedPreferences.Editor edit = prefs.edit();
        String ns = namespace(account);
        String name = model == null ? "" : model.trim();
        if (!name.isEmpty() && name.length() <= 160)
            edit.putString(ns + "_model_" + provider, name);
        if (!isCustom(provider) && base != null && !base.isEmpty()) {
            try {
                String checked = validateBase(base), wire = validateProtocol(protocol);
                edit.putString(ns + "_config_" + provider, new org.json.JSONObject().put("base_url", checked)
                        .put("protocol", wire).put("auth_scope", scope == null ? "" : scope.trim()).toString());
            } catch (Exception invalid) { /* an unsafe address from the cloud is ignored */ }
        }
        edit.apply();
    }

    /** Selects the service only when it is usable on this device (a key is entered). */
    public synchronized void restoreSelected(String account, String provider) {
        if (provider == null || provider.isEmpty() || !supported().containsKey(provider)) return;
        if (!isConfigured(account, provider)) return;
        prefs.edit().putString(namespace(account) + "_provider", provider).apply();
    }
}
