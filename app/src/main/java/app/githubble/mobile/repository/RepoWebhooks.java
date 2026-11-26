package app.githubble.mobile.repository;

import app.githubble.mobile.network.GitHubApiClient;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Settings → Webhooks: list, create, switch on/off, test, delivery log with redelivery. */
public final class RepoWebhooks {
    private RepoWebhooks() {}

    public static final class Hook {
        public final long id; public final String url, contentType, lastStatus; public final boolean active; public final List<String> events;
        Hook(long id, String url, String contentType, boolean active, List<String> events, String lastStatus) { this.id = id; this.url = url; this.contentType = contentType; this.active = active; this.events = events; this.lastStatus = lastStatus; }
    }

    public static final class Delivery {
        public final long id; public final int code; public final String event, at, status;
        Delivery(long id, int code, String event, String at, String status) { this.id = id; this.code = code; this.event = event; this.at = at; this.status = status; }
    }

    static String base(String repo) { return Gh.repo(repo) + "/hooks"; }

    public static String checkUrl(String raw) {
        String url = raw == null ? "" : raw.trim();
        if (!url.matches("https?://[^\\s/]+(/\\S*)?")) throw new IllegalArgumentException("Введите адрес вида https://example.com/hook");
        return url;
    }

    /** "*" for everything, otherwise a clean list of event names such as push, pull_request. */
    public static List<String> events(String raw) {
        List<String> out = new ArrayList<>();
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) { out.add("push"); return out; }
        for (String part : value.split("[,\\s]+")) {
            String e = part.trim();
            if (e.isEmpty()) continue;
            if (!e.equals("*") && !e.matches("[a-z_]+")) throw new IllegalArgumentException("Событие «" + e + "»: только строчные латинские буквы и «_», например push, pull_request");
            if (!out.contains(e)) out.add(e);
        }
        if (out.contains("*")) { out.clear(); out.add("*"); }
        return out;
    }

    static List<Hook> parse(JSONArray array) {
        List<Hook> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject x = array.optJSONObject(i);
            if (x == null) continue;
            JSONObject config = x.optJSONObject("config"), last = x.optJSONObject("last_response");
            List<String> events = new ArrayList<>();
            JSONArray list = x.optJSONArray("events");
            if (list != null) for (int j = 0; j < list.length(); j++) events.add(list.optString(j));
            String status = last == null || last.isNull("code") ? "ещё не вызывался" : (last.optInt("code") + " " + last.optString("status", ""));
            out.add(new Hook(x.optLong("id"), Gh.text(config, "url"), Gh.text(config, "content_type"), x.optBoolean("active", true), events, status.trim()));
        }
        return out;
    }

    public static List<Hook> list(GitHubApiClient api, String repo) throws Exception { return parse(Gh.array(api, base(repo) + "?per_page=100")); }

    static JSONObject body(String url, String contentType, String secret, boolean active, List<String> events) throws Exception {
        JSONObject config = new JSONObject().put("url", checkUrl(url)).put("content_type", "form".equals(contentType) ? "form" : "json").put("insecure_ssl", "0");
        if (secret != null && !secret.isEmpty()) config.put("secret", secret);
        return new JSONObject().put("name", "web").put("active", active).put("events", new JSONArray(events)).put("config", config);
    }

    public static void create(GitHubApiClient api, String repo, String url, String contentType, String secret, List<String> events) throws Exception {
        api.postJson(base(repo), body(url, contentType, secret, true, events), true);
    }

    public static void update(GitHubApiClient api, String repo, long id, String url, String contentType, String secret, boolean active, List<String> events) throws Exception {
        api.patchJson(base(repo) + "/" + id, body(url, contentType, secret, active, events));
    }

    public static void setActive(GitHubApiClient api, String repo, long id, boolean active) throws Exception {
        api.patchJson(base(repo) + "/" + id, new JSONObject().put("active", active));
    }

    public static void delete(GitHubApiClient api, String repo, long id) throws Exception {
        try { api.delete(base(repo) + "/" + id); } catch (GitHubApiClient.ApiException e) { if (e.statusCode != 404) throw e; }
    }

    /** Sends the latest push event to the hook (works only for hooks subscribed to push). */
    public static void test(GitHubApiClient api, String repo, long id) throws Exception { api.executeText("POST", base(repo) + "/" + id + "/tests", "", true); }

    public static void ping(GitHubApiClient api, String repo, long id) throws Exception { api.executeText("POST", base(repo) + "/" + id + "/pings", "", true); }

    static List<Delivery> parseDeliveries(JSONArray array) {
        List<Delivery> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject x = array.optJSONObject(i);
            if (x != null) out.add(new Delivery(x.optLong("id"), x.optInt("status_code"), x.optString("event"), x.optString("delivered_at"), x.optString("status")));
        }
        return out;
    }

    public static List<Delivery> deliveries(GitHubApiClient api, String repo, long id) throws Exception { return parseDeliveries(Gh.array(api, base(repo) + "/" + id + "/deliveries?per_page=30")); }

    public static void redeliver(GitHubApiClient api, String repo, long hook, long delivery) throws Exception { api.executeText("POST", base(repo) + "/" + hook + "/deliveries/" + delivery + "/attempts", "", true); }
}
