package app.githubble.mobile.agent;

import app.githubble.mobile.network.GitHubApiClient;
import java.time.Instant;
import java.util.function.BiConsumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Executes already-classified gateway calls and bulk Actions cleanup. */
public final class GatewayOps {
    /** Set by the UI to refresh its repository list after a successful repository deletion. */
    public static volatile BiConsumer<String, String> onRepositoryDeleted;
    static final int MAX_RESPONSE = 60000, MAX_DELETE = 300;
    private GatewayOps() {}

    public static String redact(String text) {
        String clipped = text == null ? "" : text.length() > MAX_RESPONSE ? text.substring(0, MAX_RESPONSE) + "…[обрезано]" : text;
        return app.githubble.mobile.memory.MemoryPolicy.INSTANCE.redactSecrets(clipped);
    }

    public static String get(GitHubApiClient api, GatewayPolicy.Decision d) throws Exception {
        if (d.risk != GatewayPolicy.Risk.READ) throw new SecurityException("github_get выполняет только GET");
        return redact(api.getText(d.url()));
    }

    public static JSONObject write(GitHubApiClient api, GatewayPolicy.Decision d, String body, String account) throws Exception {
        if (d.risk == GatewayPolicy.Risk.READ || d.blocked()) throw new SecurityException("Операция запрещена шлюзом");
        String payload = body == null || body.trim().isEmpty() ? null : body;
        if (payload != null) new JSONObject(payload); // must be a JSON object; never forward free text
        String out = api.executeText(d.method, d.url(), payload, true);
        JSONObject result = new JSONObject().put("ok", true).put("method", d.method).put("path", d.path);
        if (out != null && !out.trim().isEmpty()) result.put("response", redact(out));
        String[] segs = d.path.split("\\?")[0].substring(1).split("/");
        if ("DELETE".equals(d.method) && segs.length == 3 && "repos".equalsIgnoreCase(segs[0])) {
            BiConsumer<String, String> hook = onRepositoryDeleted;
            if (hook != null && account != null && !account.isEmpty()) { try { hook.accept(account, segs[1] + "/" + segs[2]); } catch (RuntimeException ignored) { } }
        }
        return result;
    }

    // ---- Actions cleanup -------------------------------------------------------------------------

    public static final class Item {
        public final long id, size;
        public final String label, date;
        Item(long id, long size, String label, String date) { this.id = id; this.size = size; this.label = label; this.date = date; }
    }

    static String listKey(String kind) {
        switch (kind) { case "caches": return "actions_caches"; case "artifacts": return "artifacts"; case "runs": return "workflow_runs"; default: throw new IllegalArgumentException("kind: caches | artifacts | runs"); }
    }
    static String listPath(String kind) {
        switch (kind) { case "caches": return "/actions/caches?per_page=100"; case "artifacts": return "/actions/artifacts?per_page=100";
            default: return "/actions/runs?per_page=100&status=completed"; }
    }
    static String dateField(String kind) { return "caches".equals(kind) ? "last_accessed_at" : "created_at"; }
    static String itemPath(String kind, long id) { return "/actions/" + ("runs".equals(kind) ? "runs" : kind) + "/" + id; }

    /** Keeps entries older than the cutoff; days<=0 keeps everything. Pure and unit-testable. */
    public static JSONArray olderThan(JSONArray items, String dateKey, int days, long nowMillis) throws Exception {
        JSONArray out = new JSONArray();
        long cutoff = nowMillis - days * 86400000L;
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (days <= 0) { out.put(item); continue; }
            String when = item.optString(dateKey, "");
            try { if (!when.isEmpty() && Instant.parse(when).toEpochMilli() <= cutoff) out.put(item); } catch (RuntimeException badDate) { /* keep unknown dates */ }
        }
        return out;
    }

    public static java.util.List<Item> plan(GitHubApiClient api, String repo, String kind, int days) throws Exception {
        String base = "https://api.github.com/repos/" + repo;
        java.util.List<Item> items = new java.util.ArrayList<>();
        for (int page = 1; page <= 3 && items.size() < MAX_DELETE; page++) {
            JSONObject response = api.getJson(base + listPath(kind) + "&page=" + page);
            JSONArray rows = olderThan(response.optJSONArray(listKey(kind)), dateField(kind), days, System.currentTimeMillis());
            JSONArray raw = response.optJSONArray(listKey(kind));
            for (int i = 0; i < rows.length() && items.size() < MAX_DELETE; i++) {
                JSONObject o = rows.getJSONObject(i);
                String label = "caches".equals(kind) ? o.optString("key") : o.optString("name", o.optString("display_title", "run"));
                items.add(new Item(o.optLong("id"), o.optLong("size_in_bytes"), label, o.optString(dateField(kind))));
            }
            if (raw == null || raw.length() < 100) break;
        }
        return items;
    }

    public static String preview(String repo, String kind, int days, java.util.List<Item> items) {
        long bytes = 0; for (Item i : items) bytes += i.size;
        StringBuilder sb = new StringBuilder(repo + " · " + kind + (days > 0 ? " старше " + days + " дн." : " (все)"))
            .append("\nБудет удалено: ").append(items.size()).append(items.size() >= MAX_DELETE ? "+ (лимит за раз " + MAX_DELETE + ")" : "")
            .append(" шт., ").append(bytes / 1048576).append(" МБ");
        for (int i = 0; i < Math.min(6, items.size()); i++) sb.append("\n• ").append(items.get(i).label).append(" · ").append(items.get(i).date);
        return sb.toString();
    }

    public static JSONObject delete(GitHubApiClient api, String repo, String kind, java.util.List<Item> items, Runnable guard) throws Exception {
        int deleted = 0, failed = 0; long bytes = 0;
        for (Item item : items) {
            guard.run();
            try { api.delete("https://api.github.com/repos/" + repo + itemPath(kind, item.id)); deleted++; bytes += item.size; }
            catch (GitHubApiClient.ApiException gone) { if (gone.statusCode == 404 || gone.statusCode == 410) deleted++; else failed++; }
        }
        return new JSONObject().put("ok", failed == 0).put("kind", kind).put("deleted", deleted).put("failed", failed).put("freed_mb", bytes / 1048576);
    }
}
