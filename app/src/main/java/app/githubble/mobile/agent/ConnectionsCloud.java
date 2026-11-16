package app.githubble.mobile.agent;

import android.content.Context;
import android.content.SharedPreferences;
import app.githubble.mobile.backup.PortableSettingsManager;
import app.githubble.mobile.network.NativeProviderCatalog;
import app.githubble.mobile.security.SecureStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

/**
 * Bridges the connection stores and the secret-free {@link ConnectionsSnapshot}. It knows nothing about
 * Google: the Drive layer passes the remote text in and uploads the payload that comes back.
 */
public final class ConnectionsCloud {
    private static final String PREFS = "githubble_settings_cloud";
    private static final String KEY_OWNERS = "owners", KEY_SEEN = "seen_stamp", KEY_HASH = "synced_hash";
    private static final String KEY_LAST = "last_sync", KEY_NOTE = "last_note";
    private static final int MAX_OWNERS = 16;

    private final SharedPreferences cloud, main;
    private final OmniChatConnectionStore store;

    /** Outcome of one reconciliation; {@link #payload} is what has to be written to Drive, or null. */
    public static final class Plan {
        public ConnectionsSnapshot.Action action = ConnectionsSnapshot.Action.NONE;
        public String payload;
        long stamp;
        ConnectionsSnapshot remote;
        public int restoredServices, restoredProviders, restoredSettings;
    }

    public ConnectionsCloud(Context context) {
        this(context, new OmniChatConnectionStore(context, new SecureStore(context)));
    }

    public ConnectionsCloud(Context context, OmniChatConnectionStore store) {
        Context app = context.getApplicationContext();
        this.cloud = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.main = app.getSharedPreferences("githubble", Context.MODE_PRIVATE);
        this.store = store;
    }

    /** Remembers which AI profiles belong to the backup; background sync has no screen to ask. */
    public static void track(Context context, String owner) {
        if (owner == null || owner.isEmpty()) return;
        SharedPreferences p = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> owners = new HashSet<>(p.getStringSet(KEY_OWNERS, new HashSet<String>()));
        if (owners.contains(owner) || owners.size() >= MAX_OWNERS) return;
        owners.add(owner);
        p.edit().putStringSet(KEY_OWNERS, owners).apply();
    }

    public long lastSync() { return cloud.getLong(KEY_LAST, 0); }
    public String lastNote() { return cloud.getString(KEY_NOTE, ""); }

    private Set<String> owners() { return new HashSet<>(cloud.getStringSet(KEY_OWNERS, new HashSet<String>())); }

    // ------------------------------------------------------------------ local picture

    private static String encode(Object value) {
        if (value instanceof Boolean) return "b:" + value;
        if (value instanceof Integer) return "i:" + value;
        if (value instanceof Long) return "l:" + value;
        if (value instanceof Float) return "f:" + value;
        if (value instanceof String && !((String) value).contains("\n")) return "s:" + value;
        return null;
    }

    private void collectApp(ConnectionsSnapshot snap) {
        Map<String, ?> all = main.getAll();
        for (String key : PortableSettingsManager.allowedKeys()) {
            String typed = encode(all.get(key));
            if (typed != null && typed.length() <= 300) snap.appSettings.put(key, typed);
        }
        List<String> favorites = new ArrayList<>();
        for (String f : main.getStringSet("favorites", new HashSet<String>())) if (f.contains("/")) favorites.add(f);
        java.util.Collections.sort(favorites);
        snap.appFavorites = favorites.size() > 100 ? new ArrayList<>(favorites.subList(0, 100)) : favorites;
    }

    private void applyApp(ConnectionsSnapshot merged) {
        SharedPreferences.Editor edit = main.edit();
        List<String> allowed = PortableSettingsManager.allowedKeys();
        for (Map.Entry<String, String> e : merged.appSettings.entrySet()) {
            if (!allowed.contains(e.getKey())) continue;
            String v = e.getValue().substring(2);
            try {
                switch (e.getValue().charAt(0)) {
                    case 'b': edit.putBoolean(e.getKey(), Boolean.parseBoolean(v)); break;
                    case 'i': edit.putInt(e.getKey(), Integer.parseInt(v)); break;
                    case 'l': edit.putLong(e.getKey(), Long.parseLong(v)); break;
                    case 'f': edit.putFloat(e.getKey(), Float.parseFloat(v)); break;
                    default: edit.putString(e.getKey(), v);
                }
            } catch (NumberFormatException damaged) { /* skip a damaged value */ }
        }
        Set<String> favorites = new HashSet<>(main.getStringSet("favorites", new HashSet<String>()));
        for (String f : merged.appFavorites) if (f.contains("/")) favorites.add(f);
        edit.putStringSet("favorites", favorites);
        edit.apply();
    }

    public ConnectionsSnapshot collect() {
        ConnectionsSnapshot snap = new ConnectionsSnapshot();
        collectApp(snap);
        for (String id : store.customProviders()) {
            NativeProviderCatalog.Profile profile = NativeProviderCatalog.profile(id);
            if (profile == null) continue;
            ConnectionsSnapshot.Service s = new ConnectionsSnapshot.Service();
            s.id = id; s.name = profile.getName(); s.base = profile.getBase(); s.protocol = profile.getProtocol();
            s.keyless = "none".equals(profile.getAuth());
            snap.services.put(id, s);
        }
        for (String owner : owners()) {
            ConnectionsSnapshot.Profile p = new ConnectionsSnapshot.Profile();
            try { p.selected = store.selectedProvider(owner); } catch (RuntimeException none) { p.selected = ""; }
            for (String provider : store.supported().keySet()) {
                if (!OmniChatConnectionStore.poolable(provider)) continue;
                ConnectionsSnapshot.Provider v = new ConnectionsSnapshot.Provider();
                v.model = store.model(owner, provider);
                JSONObject config = store.rawConfiguration(owner, provider);
                if (config != null) {
                    v.base = config.optString("base_url");
                    v.protocol = config.optString("protocol");
                    v.scope = config.optString("auth_scope");
                }
                ProviderPool pool = store.pool();
                if (pool.hasSlots(owner, provider)) {
                    v.strategy = pool.strategy(owner, provider);
                    for (PoolPolicy.Slot slot : pool.slots(owner, provider))
                        v.keyLabels.add(slot.label == null || slot.label.trim().isEmpty() ? "Ключ" : slot.label.trim());
                }
                if (!v.isEmpty()) p.providers.put(provider, v);
            }
            p.favorites = ModelPicks.parse(main.getString("model_favorites_v1_" + owner, ""));
            p.recents = ModelPicks.parse(main.getString("model_recents_v1_" + owner, ""));
            if (!p.isEmpty()) snap.profiles.put(owner, p);
        }
        return snap;
    }

    /** True when connections changed since the last successful synchronisation. */
    public boolean dirty() {
        return !collect().contentHash().equals(cloud.getString(KEY_HASH, ""));
    }

    // ------------------------------------------------------------------ reconcile

    /**
     * Decides what to do with the remote text. A first connection always merges (this device's data wins
     * conflicts); later runs follow {@link ConnectionsSnapshot#decide}. Local stores are updated here.
     */
    public synchronized Plan reconcile(String remoteText, boolean first) throws Exception {
        ConnectionsSnapshot remote = ConnectionsSnapshot.parse(remoteText);
        ConnectionsSnapshot local = collect();
        boolean dirty = !local.contentHash().equals(cloud.getString(KEY_HASH, ""));
        ConnectionsSnapshot.Action action = first
                ? (remote == null ? ConnectionsSnapshot.Action.UPLOAD : ConnectionsSnapshot.Action.MERGE)
                : ConnectionsSnapshot.decide(remote != null, remote == null ? 0 : remote.updatedAt,
                        cloud.getLong(KEY_SEEN, 0), dirty);
        Plan plan = new Plan();
        plan.action = action;
        plan.remote = remote;
        if (action == ConnectionsSnapshot.Action.NONE) return plan;
        ConnectionsSnapshot merged = remote == null ? local
                : ConnectionsSnapshot.merge(local, remote, action == ConnectionsSnapshot.Action.DOWNLOAD);
        if (remote != null) {
            apply(merged);
            ConnectionsSnapshot after = collect();
            for (Map.Entry<String, String> e : after.appSettings.entrySet())
                if (!e.getValue().equals(local.appSettings.get(e.getKey()))) plan.restoredSettings++;
            plan.restoredServices = Math.max(0, after.services.size() - local.services.size());
            plan.restoredProviders = Math.max(0, countProviders(after) - countProviders(local));
        }
        if (remote == null || !merged.sameContent(remote)) {
            ConnectionsSnapshot out = merged.copy();
            plan.stamp = Math.max(System.currentTimeMillis(), remote == null ? 0 : remote.updatedAt + 1);
            out.updatedAt = plan.stamp;
            plan.payload = out.toJson();
        }
        return plan;
    }

    /** Records that the plan was carried out (and uploaded, if it had a payload). */
    public synchronized void commit(Plan plan) {
        long seen = plan.payload != null ? plan.stamp : plan.remote != null ? plan.remote.updatedAt : 0;
        String note = plan.action == ConnectionsSnapshot.Action.NONE ? "без изменений"
                : (plan.restoredServices + plan.restoredProviders + plan.restoredSettings > 0
                    ? "восстановлено: настроек " + plan.restoredSettings + ", сервисов " + plan.restoredServices
                        + ", подключений " + plan.restoredProviders
                    : plan.payload != null ? "настройки сохранены в облако" : "без изменений");
        cloud.edit().putLong(KEY_SEEN, seen).putString(KEY_HASH, collect().contentHash())
                .putLong(KEY_LAST, System.currentTimeMillis()).putString(KEY_NOTE, note).apply();
    }

    private static int countProviders(ConnectionsSnapshot s) {
        int n = 0;
        for (ConnectionsSnapshot.Profile p : s.profiles.values()) n += p.providers.size();
        return n;
    }

    // ------------------------------------------------------------------ apply

    private void apply(ConnectionsSnapshot merged) {
        applyApp(merged);
        for (ConnectionsSnapshot.Service s : merged.services.values())
            store.restoreCustomProvider(s.id, s.name, s.base, s.protocol, s.keyless);
        for (String owner : owners()) {
            ConnectionsSnapshot.Profile p = merged.profiles.get(owner);
            if (p == null) continue;
            for (Map.Entry<String, ConnectionsSnapshot.Provider> e : p.providers.entrySet()) {
                ConnectionsSnapshot.Provider v = e.getValue();
                store.restoreProvider(owner, e.getKey(), v.model, v.base, v.protocol, v.scope);
                if (v.strategy >= 0 && OmniChatConnectionStore.supported().containsKey(e.getKey())
                        && store.pool().strategy(owner, e.getKey()) != v.strategy)
                    store.pool().setStrategy(owner, e.getKey(), v.strategy);
            }
            store.restoreSelected(owner, p.selected);
            SharedPreferences.Editor edit = main.edit();
            List<String> favorites = new ArrayList<>(p.favorites), recents = new ArrayList<>(p.recents);
            edit.putString("model_favorites_v1_" + owner, ModelPicks.serialize(favorites));
            edit.putString("model_recents_v1_" + owner, ModelPicks.serialize(recents));
            edit.apply();
        }
    }
}
