package app.githubble.mobile.auth;

import android.content.Context;
import android.content.SharedPreferences;

import app.githubble.mobile.security.SecureStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Local multi-account registry for GitHub sessions.
 *
 * Account metadata is kept in normal app preferences while every OAuth secret
 * remains encrypted by SecureStore. The selected account is projected into the
 * legacy github_* keys so existing API/background code uses one active session
 * without sharing tokens between accounts.
 */
public final class GitHubAccountStore {
    public static final int MAX_ACCOUNTS = 10;

    private static final String PREFS = "githubble_github_accounts";
    private static final String KEY_ACCOUNTS = "accounts_json";
    private static final String KEY_ACTIVE = "active_account_id";
    private static final String SECRET_PREFIX = "github_account_";

    private static final String[] SESSION_KEYS = {
            "github_token",
            "github_token_scope",
            "github_token_type",
            "github_token_expires_at",
            "github_refresh_token",
            "github_refresh_token_expires_at",
            "github_reauth_required",
            "github_login",
            "github_name"
    };

    public static final class Account {
        public final String id;
        public final long githubUserId;
        public final String login;
        public final String name;
        public final boolean active;

        Account(String id, long githubUserId, String login, String name, boolean active) {
            this.id = id;
            this.githubUserId = githubUserId;
            this.login = login == null ? "" : login;
            this.name = name == null ? "" : name;
            this.active = active;
        }

        public String displayName() {
            if (!login.isEmpty()) return "@" + login;
            if (!name.isEmpty()) return name;
            return "GitHub account";
        }
    }

    private static final class MutableAccount {
        String id;
        long githubUserId;
        String login;
        String name;

        MutableAccount(String id, long githubUserId, String login, String name) {
            this.id = id;
            this.githubUserId = githubUserId;
            this.login = login == null ? "" : login;
            this.name = name == null ? "" : name;
        }
    }

    private final SharedPreferences prefs;
    private final SecureStore secureStore;

    public GitHubAccountStore(Context context, SecureStore secureStore) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.secureStore = secureStore;
        migrateLegacySessionIfNeeded();
    }

    public synchronized int count() {
        return readAccounts().size();
    }

    public synchronized boolean canAddAccount() {
        return count() < MAX_ACCOUNTS;
    }

    public synchronized String activeId() {
        return prefs.getString(KEY_ACTIVE, "");
    }

    public synchronized String activeCacheKey() {
        String id = activeId();
        if (id.isEmpty()) {
            String login = secureStore.get("github_login");
            if (!login.isEmpty()) id = fallbackId(login, "");
        }
        return id.isEmpty() ? "guest" : safeKey(id);
    }

    public synchronized Account activeAccount() {
        String active = activeId();
        for (MutableAccount item : readAccounts()) {
            if (item.id.equals(active)) return immutable(item, true);
        }
        return null;
    }

    public synchronized List<Account> listAccounts() {
        String active = activeId();
        List<Account> result = new ArrayList<>();
        for (MutableAccount item : readAccounts()) result.add(immutable(item, item.id.equals(active)));
        return Collections.unmodifiableList(result);
    }

    public synchronized void persistActiveSession() {
        String id = activeId();
        if (id.isEmpty()) return;
        List<MutableAccount> accounts = readAccounts();
        MutableAccount current = find(accounts, id);
        if (current == null) return;
        copyLegacyToAccount(id);
        String login = secureStore.get("github_login");
        String name = secureStore.get("github_name");
        if (!login.isEmpty()) current.login = login;
        if (!name.isEmpty()) current.name = name;
        writeAccounts(accounts);
    }

    public synchronized Account captureCurrentSession(long githubUserId, String login, String name) {
        String normalizedLogin = login == null ? "" : login.trim();
        String id = githubUserId > 0L ? "u" + githubUserId : fallbackId(normalizedLogin, secureStore.get("github_token"));
        List<MutableAccount> accounts = readAccounts();
        MutableAccount target = find(accounts, id);
        if (target == null && !normalizedLogin.isEmpty()) target = findByLogin(accounts, normalizedLogin);
        if (target == null) {
            if (accounts.size() >= MAX_ACCOUNTS) {
                throw new IllegalStateException("Можно сохранить не более " + MAX_ACCOUNTS + " GitHub-аккаунтов");
            }
            target = new MutableAccount(id, githubUserId, normalizedLogin, name);
            accounts.add(target);
        } else {
            if (!target.id.equals(id) && githubUserId > 0L) {
                migrateSecretNamespace(target.id, id);
                target.id = id;
            }
            target.githubUserId = githubUserId > 0L ? githubUserId : target.githubUserId;
            if (!normalizedLogin.isEmpty()) target.login = normalizedLogin;
            if (name != null) target.name = name.trim();
        }
        secureStore.put("github_login", target.login);
        secureStore.put("github_name", target.name);
        copyLegacyToAccount(target.id);
        writeAccounts(accounts);
        prefs.edit().putString(KEY_ACTIVE, target.id).apply();
        return immutable(target, true);
    }

    public synchronized boolean switchTo(String id) {
        if (id == null || id.isEmpty() || id.equals(activeId())) return find(readAccounts(), id) != null;
        List<MutableAccount> accounts = readAccounts();
        MutableAccount target = find(accounts, id);
        if (target == null) return false;
        persistActiveSession();
        clearLegacySession();
        copyAccountToLegacy(target.id);
        secureStore.put("github_login", target.login);
        secureStore.put("github_name", target.name);
        prefs.edit().putString(KEY_ACTIVE, target.id).apply();
        return !secureStore.get("github_token").isEmpty();
    }

    public synchronized void remove(String id) {
        if (id == null || id.isEmpty()) return;
        List<MutableAccount> accounts = readAccounts();
        boolean wasActive = id.equals(activeId());
        MutableAccount existing = find(accounts, id);
        if (existing == null) return;
        accounts.remove(existing);
        clearAccountSecrets(id);
        writeAccounts(accounts);
        if (!wasActive) return;
        clearLegacySession();
        prefs.edit().remove(KEY_ACTIVE).apply();
        if (!accounts.isEmpty()) {
            MutableAccount next = accounts.get(0);
            copyAccountToLegacy(next.id);
            secureStore.put("github_login", next.login);
            secureStore.put("github_name", next.name);
            prefs.edit().putString(KEY_ACTIVE, next.id).apply();
        }
    }

    public synchronized void removeActiveAccount() {
        String id = activeId();
        if (id.isEmpty()) {
            clearLegacySession();
            return;
        }
        remove(id);
    }

    public synchronized void clearAll() {
        for (MutableAccount account : readAccounts()) clearAccountSecrets(account.id);
        clearLegacySession();
        prefs.edit().clear().commit();
    }

    private void migrateLegacySessionIfNeeded() {
        synchronized (this) {
            if (!readAccounts().isEmpty() || secureStore.get("github_token").isEmpty()) return;
            String login = secureStore.get("github_login");
            String name = secureStore.get("github_name");
            String id = fallbackId(login, secureStore.get("github_token"));
            MutableAccount account = new MutableAccount(id, 0L, login, name);
            List<MutableAccount> accounts = new ArrayList<>();
            accounts.add(account);
            copyLegacyToAccount(id);
            writeAccounts(accounts);
            prefs.edit().putString(KEY_ACTIVE, id).apply();
        }
    }

    private void copyLegacyToAccount(String id) {
        for (String key : SESSION_KEYS) {
            String value = secureStore.get(key);
            String target = accountSecretKey(id, key);
            if (value.isEmpty()) secureStore.remove(target); else secureStore.put(target, value);
        }
    }

    private void copyAccountToLegacy(String id) {
        for (String key : SESSION_KEYS) {
            String value = secureStore.get(accountSecretKey(id, key));
            if (value.isEmpty()) secureStore.remove(key); else secureStore.put(key, value);
        }
    }

    private void migrateSecretNamespace(String fromId, String toId) {
        if (fromId.equals(toId)) return;
        for (String key : SESSION_KEYS) {
            String oldKey = accountSecretKey(fromId, key);
            String value = secureStore.get(oldKey);
            if (!value.isEmpty()) secureStore.put(accountSecretKey(toId, key), value);
            secureStore.remove(oldKey);
        }
    }

    private void clearAccountSecrets(String id) {
        for (String key : SESSION_KEYS) secureStore.remove(accountSecretKey(id, key));
    }

    private void clearLegacySession() {
        for (String key : SESSION_KEYS) secureStore.remove(key);
    }

    private String accountSecretKey(String id, String legacyKey) {
        return SECRET_PREFIX + safeKey(id) + "_" + legacyKey;
    }

    private List<MutableAccount> readAccounts() {
        List<MutableAccount> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY_ACCOUNTS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id", "");
                if (id.isEmpty()) continue;
                result.add(new MutableAccount(id, item.optLong("github_user_id", 0L),
                        item.optString("login", ""), item.optString("name", "")));
            }
        } catch (Exception ignored) { }
        return result;
    }

    private void writeAccounts(List<MutableAccount> accounts) {
        JSONArray array = new JSONArray();
        for (MutableAccount account : accounts) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", account.id);
                item.put("github_user_id", account.githubUserId);
                item.put("login", account.login);
                item.put("name", account.name);
            } catch (Exception ignored) { }
            array.put(item);
        }
        prefs.edit().putString(KEY_ACCOUNTS, array.toString()).apply();
    }

    private static MutableAccount find(List<MutableAccount> accounts, String id) {
        if (id == null) return null;
        for (MutableAccount account : accounts) if (id.equals(account.id)) return account;
        return null;
    }

    private static MutableAccount findByLogin(List<MutableAccount> accounts, String login) {
        if (login == null || login.isEmpty()) return null;
        for (MutableAccount account : accounts) if (login.equalsIgnoreCase(account.login)) return account;
        return null;
    }

    private static Account immutable(MutableAccount account, boolean active) {
        return new Account(account.id, account.githubUserId, account.login, account.name, active);
    }

    private static String fallbackId(String login, String token) {
        String material = login == null || login.trim().isEmpty() ? token : "login:" + login.trim().toLowerCase(Locale.ROOT);
        if (material == null || material.isEmpty()) material = "anonymous";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder("h");
            for (int i = 0; i < 12; i++) out.append(String.format(Locale.ROOT, "%02x", digest[i] & 0xff));
            return out.toString();
        } catch (Exception ignored) {
            return "h" + Integer.toHexString(material.hashCode());
        }
    }

    private static String safeKey(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
