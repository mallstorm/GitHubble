package app.githubble.mobile.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Secret-free picture of the AI connections that is stored as {@code settings.json} on Google Drive.
 *
 * <p>It holds services with their endpoints, the model and wire protocol chosen for every service, the
 * rotation strategy, the names of the keys (so the user knows what to enter again) and the model
 * favourites. API keys, tokens and usage counters never enter it. The class is pure Java so that the
 * merge rules can be tested without Android.
 */
public final class ConnectionsSnapshot {
    public static final String FORMAT = "githubble-settings";
    public static final int SCHEMA = 1;
    public static final int MAX_BYTES = 512 * 1024;
    private static final int MAX_PROVIDERS = 400, MAX_SERVICES = 100, MAX_PROFILES = 16, MAX_LIST = 100;
    private static final Pattern PROVIDER_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public static final class Service {
        public String id = "", name = "", base = "", protocol = "";
        public boolean keyless;
        Service copy() {
            Service s = new Service();
            s.id = id; s.name = name; s.base = base; s.protocol = protocol; s.keyless = keyless;
            return s;
        }
    }

    public static final class Provider {
        public String model = "", base = "", protocol = "", scope = "";
        /** -1 = not set. */
        public int strategy = -1;
        public List<String> keyLabels = new ArrayList<>();
        public boolean isEmpty() {
            return model.isEmpty() && base.isEmpty() && protocol.isEmpty() && scope.isEmpty()
                    && strategy < 0 && keyLabels.isEmpty();
        }
        Provider copy() {
            Provider p = new Provider();
            p.model = model; p.base = base; p.protocol = protocol; p.scope = scope; p.strategy = strategy;
            p.keyLabels = new ArrayList<>(keyLabels);
            return p;
        }
    }

    public static final class Profile {
        public String selected = "";
        public final Map<String, Provider> providers = new LinkedHashMap<>();
        public List<String> favorites = new ArrayList<>(), recents = new ArrayList<>();
        public boolean isEmpty() {
            return selected.isEmpty() && providers.isEmpty() && favorites.isEmpty() && recents.isEmpty();
        }
        Profile copy() {
            Profile p = new Profile();
            p.selected = selected;
            for (Map.Entry<String, Provider> e : providers.entrySet()) p.providers.put(e.getKey(), e.getValue().copy());
            p.favorites = new ArrayList<>(favorites);
            p.recents = new ArrayList<>(recents);
            return p;
        }
    }

    public enum Action { NONE, UPLOAD, DOWNLOAD, MERGE }

    public long updatedAt;
    public final Map<String, Service> services = new LinkedHashMap<>();
    public final Map<String, Profile> profiles = new LinkedHashMap<>();

    public ConnectionsSnapshot copy() {
        ConnectionsSnapshot c = new ConnectionsSnapshot();
        c.updatedAt = updatedAt;
        for (Map.Entry<String, Service> e : services.entrySet()) c.services.put(e.getKey(), e.getValue().copy());
        for (Map.Entry<String, Profile> e : profiles.entrySet()) c.profiles.put(e.getKey(), e.getValue().copy());
        return c;
    }

    // ------------------------------------------------------------------ decision and merge

    /**
     * What a synchronisation has to do. {@code seenStamp} is the file revision this device saw last;
     * {@code localDirty} says that the device changed its connections since then.
     */
    public static Action decide(boolean remoteExists, long remoteStamp, long seenStamp, boolean localDirty) {
        if (!remoteExists) return Action.UPLOAD;
        boolean remoteChanged = remoteStamp != seenStamp;
        if (remoteChanged && localDirty) return Action.MERGE;
        if (remoteChanged) return Action.DOWNLOAD;
        return localDirty ? Action.UPLOAD : Action.NONE;
    }

    /**
     * Union of both pictures. The preferred side wins on conflicts; fields it leaves empty are filled
     * from the other side. Nothing is deleted: removing a service on one device does not remove it elsewhere.
     * When the remote side is preferred its favourites replace the local lists, because the device has no
     * changes of its own that could be lost.
     */
    public static ConnectionsSnapshot merge(ConnectionsSnapshot local, ConnectionsSnapshot remote, boolean preferRemote) {
        ConnectionsSnapshot out = local.copy();
        out.updatedAt = Math.max(local.updatedAt, remote.updatedAt);
        for (Map.Entry<String, Service> e : remote.services.entrySet()) {
            if (preferRemote || !out.services.containsKey(e.getKey())) out.services.put(e.getKey(), e.getValue().copy());
        }
        for (Map.Entry<String, Profile> e : remote.profiles.entrySet()) {
            Profile mine = out.profiles.get(e.getKey());
            if (mine == null) { out.profiles.put(e.getKey(), e.getValue().copy()); continue; }
            Profile theirs = e.getValue();
            if (preferRemote && !theirs.selected.isEmpty()) mine.selected = theirs.selected;
            else if (mine.selected.isEmpty()) mine.selected = theirs.selected;
            for (Map.Entry<String, Provider> p : theirs.providers.entrySet()) {
                Provider have = mine.providers.get(p.getKey());
                if (have == null) { mine.providers.put(p.getKey(), p.getValue().copy()); continue; }
                Provider other = p.getValue();
                have.model = pick(preferRemote, have.model, other.model);
                have.base = pick(preferRemote, have.base, other.base);
                have.protocol = pick(preferRemote, have.protocol, other.protocol);
                have.scope = pick(preferRemote, have.scope, other.scope);
                if (other.strategy >= 0 && (preferRemote || have.strategy < 0)) have.strategy = other.strategy;
                have.keyLabels = union(preferRemote ? other.keyLabels : have.keyLabels,
                        preferRemote ? have.keyLabels : other.keyLabels);
            }
            mine.favorites = preferRemote ? new ArrayList<>(theirs.favorites) : union(mine.favorites, theirs.favorites);
            mine.recents = preferRemote ? new ArrayList<>(theirs.recents) : union(mine.recents, theirs.recents);
        }
        return out;
    }

    private static String pick(boolean preferRemote, String local, String remote) {
        if (preferRemote) return remote.isEmpty() ? local : remote;
        return local.isEmpty() ? remote : local;
    }

    private static List<String> union(List<String> first, List<String> second) {
        List<String> out = new ArrayList<>();
        for (String v : first) if (!out.contains(v) && out.size() < MAX_LIST) out.add(v);
        for (String v : second) if (!out.contains(v) && out.size() < MAX_LIST) out.add(v);
        return out;
    }

    // ------------------------------------------------------------------ canonical form

    /** Stable text of the content (no timestamp): equal text means equal settings. */
    public String canonical() {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Service> e : new TreeMap<>(services).entrySet()) {
            Service s = e.getValue();
            b.append("S|").append(e.getKey()).append('|').append(s.name).append('|').append(s.base).append('|')
                    .append(s.protocol).append('|').append(s.keyless).append('\n');
        }
        for (Map.Entry<String, Profile> e : new TreeMap<>(profiles).entrySet()) {
            Profile p = e.getValue();
            if (p.isEmpty()) continue;
            b.append("P|").append(e.getKey()).append('|').append(p.selected).append('\n');
            for (Map.Entry<String, Provider> q : new TreeMap<>(p.providers).entrySet()) {
                Provider v = q.getValue();
                if (v.isEmpty()) continue;
                b.append("V|").append(q.getKey()).append('|').append(v.model).append('|').append(v.base).append('|')
                        .append(v.protocol).append('|').append(v.scope).append('|').append(v.strategy).append('|')
                        .append(String.join("\u001f", v.keyLabels)).append('\n');
            }
            b.append("F|").append(String.join("\u001f", p.favorites)).append('\n');
            b.append("R|").append(String.join("\u001f", p.recents)).append('\n');
        }
        return b.toString();
    }

    public String contentHash() {
        try {
            byte[] sum = MessageDigest.getInstance("SHA-256").digest(canonical().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte x : sum) hex.append(String.format("%02x", x));
            return hex.toString();
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    public boolean sameContent(ConnectionsSnapshot other) { return canonical().equals(other.canonical()); }

    // ------------------------------------------------------------------ JSON

    public String toJson() {
        try {
            JSONObject root = new JSONObject().put("format", FORMAT).put("schema", SCHEMA).put("updatedAt", updatedAt);
            JSONArray svc = new JSONArray();
            for (Map.Entry<String, Service> e : new TreeMap<>(services).entrySet()) {
                Service s = e.getValue();
                svc.put(new JSONObject().put("id", s.id).put("name", s.name).put("base_url", s.base)
                        .put("protocol", s.protocol).put("keyless", s.keyless));
            }
            root.put("services", svc);
            JSONObject owners = new JSONObject();
            for (Map.Entry<String, Profile> e : new TreeMap<>(profiles).entrySet()) {
                Profile p = e.getValue();
                if (p.isEmpty()) continue;
                JSONObject providers = new JSONObject();
                for (Map.Entry<String, Provider> q : new TreeMap<>(p.providers).entrySet()) {
                    Provider v = q.getValue();
                    if (v.isEmpty()) continue;
                    JSONObject item = new JSONObject();
                    if (!v.model.isEmpty()) item.put("model", v.model);
                    if (!v.base.isEmpty()) item.put("base_url", v.base);
                    if (!v.protocol.isEmpty()) item.put("protocol", v.protocol);
                    if (!v.scope.isEmpty()) item.put("auth_scope", v.scope);
                    if (v.strategy >= 0) item.put("strategy", v.strategy);
                    if (!v.keyLabels.isEmpty()) item.put("key_labels", array(v.keyLabels));
                    providers.put(q.getKey(), item);
                }
                owners.put(e.getKey(), new JSONObject().put("selected", p.selected).put("providers", providers)
                        .put("favorites", array(p.favorites)).put("recents", array(p.recents)));
            }
            root.put("profiles", owners);
            return root.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Parses a stored file. Blank text means "no file yet" and yields null. */
    public static ConnectionsSnapshot parse(String text) throws Exception {
        if (text == null || text.trim().isEmpty()) return null;
        if (text.length() > MAX_BYTES) throw new IllegalArgumentException("Файл настроек в облаке слишком большой");
        JSONObject root = new JSONObject(text);
        if (!FORMAT.equals(root.optString("format")) || root.optInt("schema", -1) != SCHEMA)
            throw new IllegalArgumentException("В облаке лежит не settings.json GitHubble версии 1");
        ConnectionsSnapshot s = new ConnectionsSnapshot();
        s.updatedAt = Math.max(0, root.optLong("updatedAt"));
        JSONArray svc = root.optJSONArray("services");
        for (int i = 0; svc != null && i < svc.length() && s.services.size() < MAX_SERVICES; i++) {
            JSONObject o = svc.optJSONObject(i);
            if (o == null) continue;
            Service v = new Service();
            v.id = o.optString("id"); v.name = text(o.optString("name"), 40);
            v.base = text(o.optString("base_url"), 500); v.protocol = text(o.optString("protocol"), 40);
            v.keyless = o.optBoolean("keyless");
            if (v.id.startsWith("custom-") && PROVIDER_ID.matcher(v.id).matches() && !v.name.isEmpty() && !v.base.isEmpty())
                s.services.put(v.id, v);
        }
        JSONObject owners = root.optJSONObject("profiles");
        java.util.Iterator<String> names = owners == null ? new ArrayList<String>().iterator() : owners.keys();
        while (names.hasNext() && s.profiles.size() < MAX_PROFILES) {
            String owner = names.next();
            JSONObject o = owners.optJSONObject(owner);
            if (o == null || owner.isEmpty() || owner.length() > 200) continue;
            Profile p = new Profile();
            String selected = o.optString("selected");
            p.selected = PROVIDER_ID.matcher(selected).matches() ? selected : "";
            JSONObject providers = o.optJSONObject("providers");
            java.util.Iterator<String> ids = providers == null ? new ArrayList<String>().iterator() : providers.keys();
            while (ids.hasNext() && p.providers.size() < MAX_PROVIDERS) {
                String id = ids.next();
                JSONObject item = providers.optJSONObject(id);
                if (item == null || !PROVIDER_ID.matcher(id).matches()) continue;
                Provider v = new Provider();
                v.model = text(item.optString("model"), 160); v.base = text(item.optString("base_url"), 500);
                v.protocol = text(item.optString("protocol"), 40); v.scope = text(item.optString("auth_scope"), 128);
                v.strategy = item.has("strategy") ? Math.max(-1, Math.min(9, item.optInt("strategy", -1))) : -1;
                v.keyLabels = strings(item.optJSONArray("key_labels"), 64);
                if (!v.isEmpty()) p.providers.put(id, v);
            }
            p.favorites = strings(o.optJSONArray("favorites"), 220);
            p.recents = strings(o.optJSONArray("recents"), 220);
            if (!p.isEmpty()) s.profiles.put(owner, p);
        }
        return s;
    }

    private static JSONArray array(List<String> values) {
        JSONArray out = new JSONArray();
        for (String v : values) out.put(v);
        return out;
    }

    private static String text(String value, int max) {
        if (value == null) return "";
        String v = value.trim();
        if (v.length() > max || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) return "";
        return v;
    }

    private static List<String> strings(JSONArray array, int max) {
        List<String> out = new ArrayList<>();
        for (int i = 0; array != null && i < array.length() && out.size() < MAX_LIST; i++) {
            String v = text(array.optString(i), max);
            if (!v.isEmpty() && !out.contains(v)) out.add(v);
        }
        return out;
    }
}
