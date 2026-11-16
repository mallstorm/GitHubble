package app.githubble.mobile.agent;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import app.githubble.mobile.security.SecureStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Several API keys per service. Secrets live only in the Keystore-backed vault; the preference file
 * holds just labels, switches, limits and counters. Rules are in {@link PoolPolicy}.
 */
public final class ProviderPool {
    private static final int MAX_KEYS_PER_PROVIDER = 64;
    private final SharedPreferences prefs;
    private final SecureStore vault;

    public ProviderPool(Context context, SecureStore vault) {
        this.prefs = context.getApplicationContext().getSharedPreferences("githubble_provider_pool_v1", Context.MODE_PRIVATE);
        this.vault = vault;
    }

    private static String ns(String account) {
        if (account == null || account.isBlank()) throw new IllegalArgumentException("Не выбран профиль AI");
        return Base64.encodeToString(account.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String stateKey(String account, String provider) { return "pool_" + ns(account) + "_" + provider; }
    private static String secretKey(String account, String provider, String id) {
        return "omnichat_pool_v1_" + ns(account) + "_" + provider + "_" + id;
    }

    private PoolPolicy.State load(String account, String provider) {
        return PoolPolicy.State.fromJson(prefs.getString(stateKey(account, provider), ""));
    }

    private void save(String account, String provider, PoolPolicy.State state) {
        prefs.edit().putString(stateKey(account, provider), state.toJson()).apply();
    }

    public synchronized boolean hasSlots(String account, String provider) {
        return !load(account, provider).slots.isEmpty();
    }

    /** True when at least one key is switched on (it may still be paused or over its limit). */
    public synchronized boolean hasEnabled(String account, String provider) {
        for (PoolPolicy.Slot s : load(account, provider).slots) if (s.enabled) return true;
        return false;
    }

    /** Copies of all keys, newest counters, never including secrets. */
    public synchronized List<PoolPolicy.Slot> slots(String account, String provider) {
        PoolPolicy.State state = load(account, provider);
        long now = System.currentTimeMillis();
        List<PoolPolicy.Slot> result = new ArrayList<>();
        for (PoolPolicy.Slot s : state.slots) { PoolPolicy.rollPeriod(s, now); result.add(s.copy()); }
        return result;
    }

    public synchronized int strategy(String account, String provider) { return load(account, provider).strategy; }

    public synchronized void setStrategy(String account, String provider, int strategy) {
        PoolPolicy.State state = load(account, provider);
        state.strategy = Math.max(0, Math.min(PoolPolicy.MOST_QUOTA, strategy));
        save(account, provider, state);
    }

    /** Adds a key and returns its id. */
    public synchronized String addKey(String account, String provider, String label, String secret) {
        String value = secret == null ? "" : secret.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Введите ключ");
        if (value.length() > 8192 || value.contains("\n") || value.contains("\r"))
            throw new IllegalArgumentException("Некорректный ключ");
        PoolPolicy.State state = load(account, provider);
        if (state.slots.size() >= MAX_KEYS_PER_PROVIDER) throw new IllegalArgumentException("Достигнут лимит ключей одного сервиса");
        for (PoolPolicy.Slot s : state.slots)
            if (value.equals(vault.get(secretKey(account, provider, s.id))))
                throw new IllegalArgumentException("Такой ключ уже добавлен: " + s.label);
        PoolPolicy.Slot slot = new PoolPolicy.Slot();
        slot.id = UUID.randomUUID().toString().substring(0, 8);
        slot.label = cleanLabel(label, "Ключ " + (state.slots.size() + 1));
        slot.createdAt = System.currentTimeMillis(); slot.periodStart = slot.createdAt;
        vault.put(secretKey(account, provider, slot.id), value);
        state.slots.add(slot);
        save(account, provider, state);
        return slot.id;
    }

    private static String cleanLabel(String label, String fallback) {
        String text = label == null ? "" : label.trim().replace('\n', ' ').replace('\r', ' ');
        if (text.isEmpty()) return fallback;
        return text.length() > 40 ? text.substring(0, 40) : text;
    }

    public synchronized void removeKey(String account, String provider, String id) {
        PoolPolicy.State state = load(account, provider);
        PoolPolicy.Slot slot = state.find(id);
        if (slot == null) return;
        state.slots.remove(slot);
        vault.remove(secretKey(account, provider, id));
        save(account, provider, state);
    }

    public synchronized void removeProvider(String account, String provider) {
        for (PoolPolicy.Slot s : load(account, provider).slots) vault.remove(secretKey(account, provider, s.id));
        prefs.edit().remove(stateKey(account, provider)).apply();
    }

    public synchronized void setEnabled(String account, String provider, String id, boolean on) {
        PoolPolicy.State state = load(account, provider);
        PoolPolicy.Slot slot = state.find(id);
        if (slot == null) return;
        PoolPolicy.setEnabled(slot, on);
        save(account, provider, state);
    }

    /** Switches every key of the service on or off at once. */
    public synchronized void setAllEnabled(String account, String provider, boolean on) {
        PoolPolicy.State state = load(account, provider);
        for (PoolPolicy.Slot s : state.slots) PoolPolicy.setEnabled(s, on);
        save(account, provider, state);
    }

    /** Empty {@code newSecret} keeps the stored secret. */
    public synchronized void update(String account, String provider, String id, String label, String newSecret,
                                    long quotaTokens, long quotaRequests, int resetMode) {
        PoolPolicy.State state = load(account, provider);
        PoolPolicy.Slot slot = state.find(id);
        if (slot == null) throw new IllegalArgumentException("Ключ не найден");
        String secret = newSecret == null ? "" : newSecret.trim();
        if (!secret.isEmpty()) {
            if (secret.length() > 8192 || secret.contains("\n") || secret.contains("\r"))
                throw new IllegalArgumentException("Некорректный ключ");
            vault.put(secretKey(account, provider, id), secret);
            slot.exhausted = false; slot.cooldownUntil = 0; slot.consecutiveFails = 0; slot.failKind = "";
        }
        slot.label = cleanLabel(label, slot.label);
        slot.quotaTokens = Math.max(0, quotaTokens);
        slot.quotaRequests = Math.max(0, quotaRequests);
        slot.resetMode = Math.max(0, Math.min(PoolPolicy.RESET_MONTHLY, resetMode));
        save(account, provider, state);
    }

    public synchronized void resetStats(String account, String provider, String idOrNull) {
        PoolPolicy.State state = load(account, provider);
        long now = System.currentTimeMillis();
        for (PoolPolicy.Slot s : state.slots) if (idOrNull == null || s.id.equals(idOrNull)) PoolPolicy.resetStats(s, now);
        save(account, provider, state);
    }

    public synchronized String secret(String account, String provider, String id) {
        return vault.get(secretKey(account, provider, id));
    }

    /** Deterministic "current" credential: first switched-on key in list order. */
    public synchronized String primarySecret(String account, String provider) {
        for (PoolPolicy.Slot s : load(account, provider).slots) {
            if (!s.enabled) continue;
            String value = vault.get(secretKey(account, provider, s.id));
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    /** Next usable key per the service strategy, or null when every key is off, paused or spent. */
    public synchronized PoolPolicy.Slot pick(String account, String provider, Set<String> exclude) {
        PoolPolicy.State state = load(account, provider);
        PoolPolicy.Slot slot = PoolPolicy.pick(state, exclude, System.currentTimeMillis());
        save(account, provider, state);
        return slot == null ? null : slot.copy();
    }

    /** Earliest moment a paused (not spent, not switched off) key becomes available again; 0 if none. */
    public synchronized long nextAvailableAt(String account, String provider) {
        long best = 0, now = System.currentTimeMillis();
        for (PoolPolicy.Slot s : load(account, provider).slots)
            if (s.enabled && !s.exhausted && s.cooldownUntil > now && (best == 0 || s.cooldownUntil < best)) best = s.cooldownUntil;
        return best;
    }

    public synchronized void recordSuccess(String account, String provider, String id, long input, long output, boolean reported) {
        PoolPolicy.State state = load(account, provider);
        PoolPolicy.Slot slot = state.find(id);
        if (slot == null) return;
        PoolPolicy.onSuccess(slot, input, output, reported, System.currentTimeMillis());
        save(account, provider, state);
    }

    public synchronized PoolPolicy.Failure recordFailure(String account, String provider, String id, int status, String message) {
        PoolPolicy.Failure failure = PoolPolicy.classify(status, message);
        PoolPolicy.State state = load(account, provider);
        PoolPolicy.Slot slot = state.find(id);
        if (slot != null) {
            PoolPolicy.onFailure(slot, failure, (status > 0 ? "HTTP " + status + ": " : "") + (message == null ? "" : message),
                    System.currentTimeMillis());
            save(account, provider, state);
        }
        return failure;
    }

    public synchronized PoolPolicy.Summary summary(String account, String provider) {
        return PoolPolicy.summarize(slots(account, provider), System.currentTimeMillis());
    }

    /** Imports a pre-pool single key once, so existing connections keep working after the update. */
    public synchronized void migrateLegacy(String account, String provider, String legacySecret) {
        if (legacySecret == null || legacySecret.isBlank() || hasSlots(account, provider)) return;
        if (prefs.getBoolean("migrated_" + ns(account) + "_" + provider, false)) return;
        prefs.edit().putBoolean("migrated_" + ns(account) + "_" + provider, true).apply();
        try { addKey(account, provider, "Основной ключ", legacySecret); } catch (RuntimeException ignored) { /* invalid legacy value */ }
    }

    /** Changes whenever keys, switches or limits change; lets screens detect stale state. */
    public synchronized String signature(String account, String provider) {
        StringBuilder out = new StringBuilder();
        for (PoolPolicy.Slot s : load(account, provider).slots)
            out.append(s.id).append(s.enabled ? '1' : '0').append(s.exhausted ? 'x' : '-').append(';');
        return out.toString();
    }
}
