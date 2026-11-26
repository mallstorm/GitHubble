package app.githubble.mobile.repository;

import app.githubble.mobile.network.GitHubApiClient;
import org.json.JSONObject;

/** Settings → Pages: publishing source, custom domain, HTTPS and the latest build. */
public final class RepoPages {
    private RepoPages() {}

    public static final class State {
        public boolean exists, workflow, httpsEnforced, isPublic; public String url = "", branch = "", path = "/", cname = "", status = "";
    }

    static State parse(JSONObject j) {
        State s = new State();
        s.exists = true; s.url = j.optString("html_url", ""); s.status = j.optString("status", ""); s.workflow = "workflow".equals(j.optString("build_type"));
        s.cname = j.isNull("cname") ? "" : j.optString("cname", ""); s.httpsEnforced = j.optBoolean("https_enforced"); s.isPublic = j.optBoolean("public", true);
        JSONObject src = j.optJSONObject("source");
        if (src != null) { s.branch = src.optString("branch", ""); s.path = src.optString("path", "/"); }
        return s;
    }

    public static State load(GitHubApiClient api, String repo) throws Exception {
        try { return parse(Gh.object(api, Gh.repo(repo) + "/pages")); }
        catch (GitHubApiClient.ApiException e) { if (e.statusCode == 404) return new State(); throw e; }
    }

    public static String checkPath(String path) {
        if ("/".equals(path) || "/docs".equals(path)) return path;
        throw new IllegalArgumentException("Папка публикации — «/» или «/docs»");
    }

    public static String checkDomain(String raw) {
        String d = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (d.isEmpty()) return "";
        if (!d.matches("([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,}")) throw new IllegalArgumentException("Введите домен вида docs.example.com");
        return d;
    }

    static JSONObject source(String branch, String path) throws Exception {
        if (branch == null || branch.trim().isEmpty()) throw new IllegalArgumentException("Выберите ветку");
        return new JSONObject().put("branch", branch.trim()).put("path", checkPath(path));
    }

    public static void enable(GitHubApiClient api, String repo, boolean workflow, String branch, String path) throws Exception {
        JSONObject body = workflow ? new JSONObject().put("build_type", "workflow") : new JSONObject().put("build_type", "legacy").put("source", source(branch, path));
        api.postJson(Gh.repo(repo) + "/pages", body, true);
    }

    public static void update(GitHubApiClient api, String repo, boolean workflow, String branch, String path, String cname, boolean https) throws Exception {
        JSONObject body = new JSONObject().put("build_type", workflow ? "workflow" : "legacy");
        if (!workflow) body.put("source", source(branch, path));
        String domain = checkDomain(cname);
        body.put("cname", domain.isEmpty() ? JSONObject.NULL : domain);
        if (!domain.isEmpty()) body.put("https_enforced", https);
        api.executeText("PUT", Gh.repo(repo) + "/pages", body.toString(), true);
    }

    public static void disable(GitHubApiClient api, String repo) throws Exception {
        try { api.delete(Gh.repo(repo) + "/pages"); } catch (GitHubApiClient.ApiException e) { if (e.statusCode != 404) throw e; }
    }

    public static void rebuild(GitHubApiClient api, String repo) throws Exception { api.executeText("POST", Gh.repo(repo) + "/pages/builds", "", true); }

    public static String latestBuild(GitHubApiClient api, String repo) throws Exception {
        JSONObject b = Gh.object(api, Gh.repo(repo) + "/pages/builds/latest");
        String status = b.optString("status", "");
        JSONObject err = b.optJSONObject("error");
        String message = err == null || err.isNull("message") ? "" : err.optString("message", "");
        return message.isEmpty() ? status : status + ": " + message;
    }
}
