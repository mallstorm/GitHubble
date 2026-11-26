package app.githubble.mobile.repository;

import app.githubble.mobile.network.GitHubApiClient;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Repository deploy keys (Settings → Deploy keys): public SSH keys that give a server access to this one repository. */
public final class DeployKeys {
    private DeployKeys() {}

    public static final class Key {
        public final long id; public final String title, key, created; public final boolean readOnly;
        Key(long id, String title, String key, String created, boolean readOnly) { this.id = id; this.title = title; this.key = key; this.created = created; this.readOnly = readOnly; }
    }

    static String url(String repo) { return "https://api.github.com/repos/" + RepoSecrets.checkRepo(repo) + "/keys"; }

    /** Only public keys are accepted: a private key pasted by mistake must never be sent anywhere. */
    public static String checkPublicKey(String raw) {
        String key = raw == null ? "" : raw.trim().replaceAll("\\s*\\r?\\n\\s*", " ");
        if (key.contains("PRIVATE KEY")) throw new IllegalArgumentException("Это закрытый ключ. Вставьте открытый (файл .pub, строка «ssh-ed25519 AAAA…»)");
        if (!key.matches("(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp(256|384|521)|sk-ssh-ed25519@openssh\\.com|sk-ecdsa-sha2-nistp256@openssh\\.com) [A-Za-z0-9+/=]{20,}( .*)?"))
            throw new IllegalArgumentException("Не похоже на открытый SSH-ключ: ожидается «ssh-ed25519 AAAA… комментарий»");
        return key;
    }

    static List<Key> parse(JSONArray array) {
        List<Key> out = new ArrayList<>();
        if (array == null) return out;
        for (int i = 0; i < array.length(); i++) {
            JSONObject x = array.optJSONObject(i);
            if (x != null) out.add(new Key(x.optLong("id"), x.optString("title"), x.optString("key"), x.optString("created_at"), x.optBoolean("read_only", true)));
        }
        return out;
    }

    public static List<Key> list(GitHubApiClient api, String repo) throws Exception {
        List<Key> out = new ArrayList<>();
        for (int page = 1; page <= 10; page++) {
            JSONArray array = api.getArray(url(repo) + "?per_page=100&page=" + page);
            out.addAll(parse(array));
            if (array.length() < 100) break;
        }
        return out;
    }

    public static void add(GitHubApiClient api, String repo, String title, String publicKey, boolean allowWrite) throws Exception {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) throw new IllegalArgumentException("Введите название ключа");
        api.postJson(url(repo), new JSONObject().put("title", t).put("key", checkPublicKey(publicKey)).put("read_only", !allowWrite), true);
        api.invalidatePrefix(url(repo));
    }

    public static void delete(GitHubApiClient api, String repo, long id) throws Exception {
        try { api.delete(url(repo) + "/" + id); }
        catch (GitHubApiClient.ApiException e) { if (e.statusCode != 404) throw e; }
        api.invalidatePrefix(url(repo));
    }

    /** Short form for lists: type and the first and last characters of the key body. */
    public static String preview(String key) {
        String[] parts = key == null ? new String[0] : key.trim().split(" ");
        if (parts.length < 2) return key == null ? "" : key;
        String body = parts[1];
        return parts[0] + " " + (body.length() > 16 ? body.substring(0, 8) + "…" + body.substring(body.length() - 8) : body);
    }
}
