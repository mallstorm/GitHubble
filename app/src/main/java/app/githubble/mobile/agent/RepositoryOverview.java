package app.githubble.mobile.agent;

import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** One compact read-only survey of a repository: what it is, what is in it, what changed, what CI exists. */
public final class RepositoryOverview {
    private RepositoryOverview() {}

    public static JSONObject build(GitDataApi api, String repo, String branch, JSONObject details) throws Exception {
        String base = GitDataApi.base(repo);
        JSONObject out = new JSONObject();
        if (details != null) {
            out.put("description", details.optString("description", ""));
            out.put("language", details.optString("language", ""));
            out.put("default_branch", details.optString("default_branch", ""));
            out.put("private", details.optBoolean("private"));
            out.put("pushed_at", details.optString("pushed_at", ""));
            out.put("open_issues", details.optInt("open_issues_count"));
        }
        out.put("branch", branch);
        try {
            JSONObject readme = api.get(base + "/readme?ref=" + GitDataApi.encode(branch));
            String raw = readme.optString("content", "").replace("\n", "").replace("\r", "");
            String decoded = new String(java.util.Base64.getMimeDecoder().decode(raw), StandardCharsets.UTF_8);
            out.put("readme", decoded.length() > 1800 ? decoded.substring(0, 1800) + "\n[README truncated]" : decoded);
        } catch (Exception missing) { out.put("readme_error", note(missing)); }
        try {
            JSONObject tree = api.get(base + "/git/trees/" + GitDataApi.encode(branch));
            JSONArray items = tree.optJSONArray("tree"), top = new JSONArray();
            for (int i = 0; items != null && i < items.length() && i < 80; i++) {
                JSONObject entry = items.getJSONObject(i);
                top.put(entry.optString("path") + ("tree".equals(entry.optString("type")) ? "/" : ""));
            }
            out.put("top_level", top);
        } catch (Exception failed) { out.put("tree_error", note(failed)); }
        try {
            JSONArray commits = api.getArray(base + "/commits?sha=" + GitDataApi.encode(branch) + "&per_page=5"), brief = new JSONArray();
            for (int i = 0; i < commits.length(); i++) {
                JSONObject commit = commits.getJSONObject(i), inner = commit.optJSONObject("commit");
                String message = inner == null ? "" : inner.optString("message", "");
                int newline = message.indexOf('\n');
                JSONObject author = inner == null ? null : inner.optJSONObject("author");
                brief.put(new JSONObject().put("sha", commit.optString("sha").substring(0, Math.min(7, commit.optString("sha").length())))
                    .put("message", newline < 0 ? message : message.substring(0, newline))
                    .put("date", author == null ? "" : author.optString("date", "")));
            }
            out.put("recent_commits", brief);
        } catch (Exception failed) { out.put("commits_error", note(failed)); }
        try {
            JSONArray workflows = api.get(base + "/actions/workflows").optJSONArray("workflows"), brief = new JSONArray();
            for (int i = 0; workflows != null && i < workflows.length() && i < 8; i++) {
                JSONObject workflow = workflows.getJSONObject(i);
                brief.put(new JSONObject().put("name", workflow.optString("name")).put("path", workflow.optString("path")).put("state", workflow.optString("state")));
            }
            out.put("workflows", brief);
        } catch (Exception failed) { out.put("workflows_error", note(failed)); }
        try { out.put("recent_branches", BranchHistory.recent(api, repo, 2)); }
        catch (Exception failed) { out.put("branches_error", note(failed)); }
        return out;
    }

    private static String note(Exception error) {
        String message = AgentLogRedactor.redact(error.getMessage());
        return message == null ? error.getClass().getSimpleName() : message;
    }
}
