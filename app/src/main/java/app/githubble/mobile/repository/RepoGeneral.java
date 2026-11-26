package app.githubble.mobile.repository;

import app.githubble.mobile.network.GitHubApiClient;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Settings → General: description, homepage, topics, features, merge options, default branch, archive. */
public final class RepoGeneral {
    private RepoGeneral() {}

    public static final class State {
        public String description = "", homepage = "", defaultBranch = "main", visibility = "";
        public boolean issues, wiki, projects, discussions, squash, merge, rebase, deleteBranch, updateBranch, archived, template, webSignoff, autoMerge;
        public final List<String> topics = new ArrayList<>();
    }

    static State parse(JSONObject r, JSONArray topics) {
        State s = new State();
        s.description = r.isNull("description") ? "" : r.optString("description", "");
        s.homepage = r.isNull("homepage") ? "" : r.optString("homepage", "");
        s.defaultBranch = r.optString("default_branch", "main"); s.visibility = r.optString("visibility", r.optBoolean("private") ? "private" : "public");
        s.issues = r.optBoolean("has_issues"); s.wiki = r.optBoolean("has_wiki"); s.projects = r.optBoolean("has_projects"); s.discussions = r.optBoolean("has_discussions");
        s.squash = r.optBoolean("allow_squash_merge", true); s.merge = r.optBoolean("allow_merge_commit", true); s.rebase = r.optBoolean("allow_rebase_merge", true);
        s.deleteBranch = r.optBoolean("delete_branch_on_merge"); s.updateBranch = r.optBoolean("allow_update_branch"); s.archived = r.optBoolean("archived");
        s.template = r.optBoolean("is_template"); s.webSignoff = r.optBoolean("web_commit_signoff_required"); s.autoMerge = r.optBoolean("allow_auto_merge");
        JSONArray t = topics != null ? topics : r.optJSONArray("topics");
        if (t != null) for (int i = 0; i < t.length(); i++) s.topics.add(t.optString(i));
        return s;
    }

    public static State load(GitHubApiClient api, String repo) throws Exception {
        JSONObject r = Gh.object(api, Gh.repo(repo));
        JSONArray topics = null;
        try { topics = Gh.object(api, Gh.repo(repo) + "/topics").optJSONArray("names"); } catch (GitHubApiClient.ApiException ignored) { }
        return parse(r, topics);
    }

    public static String checkHomepage(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) return "";
        if (!v.matches("https?://\\S+")) throw new IllegalArgumentException("Сайт должен начинаться с http:// или https://");
        return v;
    }

    public static String checkDescription(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.length() > 350) throw new IllegalArgumentException("Описание — не длиннее 350 символов");
        return v;
    }

    /** GitHub topics: lowercase letters, digits and hyphens, up to 50 chars, up to 20 of them. */
    public static List<String> topics(String raw) {
        List<String> out = new ArrayList<>();
        if (raw != null) for (String part : raw.split("[,\\s]+")) {
            String t = part.trim().toLowerCase(java.util.Locale.ROOT);
            if (t.startsWith("#")) t = t.substring(1);
            if (t.isEmpty() || out.contains(t)) continue;
            if (!t.matches("[a-z0-9][a-z0-9-]{0,49}")) throw new IllegalArgumentException("Тема «" + t + "»: латиница в нижнем регистре, цифры и дефис, до 50 символов");
            out.add(t);
        }
        if (out.size() > 20) throw new IllegalArgumentException("Не более 20 тем");
        return out;
    }

    static JSONObject body(State s) throws Exception {
        if (!s.squash && !s.merge && !s.rebase) throw new IllegalArgumentException("Нужен хотя бы один способ слияния");
        return new JSONObject().put("description", checkDescription(s.description)).put("homepage", checkHomepage(s.homepage))
            .put("has_issues", s.issues).put("has_wiki", s.wiki).put("has_projects", s.projects).put("has_discussions", s.discussions)
            .put("allow_squash_merge", s.squash).put("allow_merge_commit", s.merge).put("allow_rebase_merge", s.rebase)
            .put("delete_branch_on_merge", s.deleteBranch).put("allow_update_branch", s.updateBranch).put("allow_auto_merge", s.autoMerge)
            .put("is_template", s.template).put("web_commit_signoff_required", s.webSignoff);
    }

    public static void save(GitHubApiClient api, String repo, State s) throws Exception {
        api.patchJson(Gh.repo(repo), body(s));
        api.putJson(Gh.repo(repo) + "/topics", new JSONObject().put("names", new JSONArray(s.topics)));
    }

    public static void setDefaultBranch(GitHubApiClient api, String repo, String branch) throws Exception {
        if (branch == null || branch.trim().isEmpty()) throw new IllegalArgumentException("Выберите ветку");
        api.patchJson(Gh.repo(repo), new JSONObject().put("default_branch", branch.trim()));
    }

    public static void setArchived(GitHubApiClient api, String repo, boolean archived) throws Exception {
        api.patchJson(Gh.repo(repo), new JSONObject().put("archived", archived));
    }
}
