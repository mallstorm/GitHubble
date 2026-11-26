package app.githubble.mobile.repository;

import app.githubble.mobile.mirror.MirrorSecrets;
import app.githubble.mobile.network.GitHubApiClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Repository «Secrets and variables» of GitHub Actions and Dependabot, done on the phone: secrets are encrypted with the
 * repository's public key (libsodium sealed box) before they leave the device, variables are stored as plain values.
 */
public final class RepoSecrets {
    private RepoSecrets() {}

    public enum Kind {
        ACTIONS_SECRET("actions", "secrets", false), DEPENDABOT_SECRET("dependabot", "secrets", false), ACTIONS_VARIABLE("actions", "variables", true);
        final String area, collection; public final boolean variable;
        Kind(String area, String collection, boolean variable) { this.area = area; this.collection = collection; this.variable = variable; }
    }

    public static final class Item {
        public final String name, value, created, updated;
        Item(String name, String value, String created, String updated) { this.name = name; this.value = value; this.created = created; this.updated = updated; }
    }

    public static final int MAX_VALUE_BYTES = 48 * 1024;

    /** Names GitHub accepts: letters, digits and underscores, not starting with a digit or with the reserved GITHUB_ prefix. */
    public static String checkName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Введите имя");
        if (name.length() > 255) throw new IllegalArgumentException("Имя длиннее 255 символов");
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Имя: латинские буквы, цифры и «_», без пробелов и не с цифры");
        if (name.toUpperCase(Locale.ROOT).startsWith("GITHUB_")) throw new IllegalArgumentException("Имена с префиксом GITHUB_ зарезервированы GitHub");
        return name;
    }

    static String checkRepo(String repo) {
        if (repo == null || !repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") || repo.startsWith(".") || repo.contains("/.")) throw new IllegalArgumentException("Некорректное имя репозитория");
        return repo;
    }

    static String checkValue(String value) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Введите значение");
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_BYTES) throw new IllegalArgumentException("Значение больше 48 КБ — GitHub его не примет");
        return value;
    }

    static String base(String repo, Kind kind) { return "https://api.github.com/repos/" + checkRepo(repo) + "/" + kind.area + "/" + kind.collection; }

    public static List<Item> list(GitHubApiClient api, String repo, Kind kind) throws Exception {
        List<Item> out = new ArrayList<>();
        int perPage = kind.variable ? 30 : 100;
        for (int page = 1; page <= 20; page++) {
            JSONObject json = api.getJsonFresh(base(repo, kind) + "?per_page=" + perPage + "&page=" + page);
            out.addAll(parse(json, kind));
            JSONArray array = json.optJSONArray(kind.collection);
            if (array == null || array.length() < perPage) break;
        }
        return out;
    }

    static List<Item> parse(JSONObject json, Kind kind) {
        List<Item> out = new ArrayList<>();
        JSONArray array = json.optJSONArray(kind.collection);
        if (array == null) return out;
        for (int i = 0; i < array.length(); i++) {
            JSONObject x = array.optJSONObject(i);
            if (x == null || x.optString("name").isEmpty()) continue;
            out.add(new Item(x.optString("name"), x.optString("value"), x.optString("created_at"), x.optString("updated_at")));
        }
        return out;
    }

    /** Creates or replaces a secret; the plain value never leaves the phone unencrypted. */
    public static void putSecret(GitHubApiClient api, String repo, Kind kind, String rawName, String value) throws Exception {
        if (kind.variable) throw new IllegalArgumentException("Это переменная, а не секрет");
        String name = checkName(rawName);
        checkValue(value);
        JSONObject key = api.getJsonFresh(base(repo, kind) + "/public-key");
        String id = key.optString("key_id"), pub = key.optString("key");
        if (id.isEmpty() || pub.isEmpty()) throw new SecurityException("GitHub не вернул публичный ключ репозитория");
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        try {
            api.putJson(base(repo, kind) + "/" + name, new JSONObject().put("key_id", id).put("encrypted_value", MirrorSecrets.encryptForGitHub(raw, pub)));
        } finally { Arrays.fill(raw, (byte) 0); }
    }

    /** Creates a variable, or changes its value when it already exists. */
    public static void saveVariable(GitHubApiClient api, String repo, String rawName, String value, boolean exists) throws Exception {
        String name = checkName(rawName);
        checkValue(value);
        String url = base(repo, Kind.ACTIONS_VARIABLE);
        if (exists) api.patchJson(url + "/" + name, new JSONObject().put("name", name).put("value", value));
        else api.postJson(url, new JSONObject().put("name", name).put("value", value), true);
    }

    public static void delete(GitHubApiClient api, String repo, Kind kind, String rawName) throws Exception {
        try { api.delete(base(repo, kind) + "/" + checkName(rawName)); }
        catch (GitHubApiClient.ApiException e) { if (e.statusCode != 404) throw e; }
    }

    /** Human explanation for the usual refusals (missing right to manage secrets). */
    public static String explain(Exception error) {
        if (error instanceof GitHubApiClient.ApiException) {
            int code = ((GitHubApiClient.ApiException) error).statusCode;
            if (code == 403 || code == 404) return "Нет доступа к секретам и переменным этого репозитория. Нужны права администратора репозитория, а для токена или GitHub App — Secrets и Variables (Read and write). HTTP " + code + ".";
            if (code == 422) return "GitHub отклонил значение или имя (HTTP 422): " + error.getMessage();
        }
        return error.getMessage() == null ? "Ошибка сети" : error.getMessage();
    }
}
