package app.githubble.mobile.agent;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Recently worked branches of a repository (GitHub REST only, no local clone) and the switch of the
 * chat's active working branch. Every branch is returned with a clickable app link.
 */
public final class BranchHistory {
    public static final String SCHEME = "githubble-branch://";
    private static final DateTimeFormatter DATE =
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());
    private BranchHistory() {}

    public static String link(String repo, String branch) {
        try {
            return SCHEME + repo + "?b=" + URLEncoder.encode(branch, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** @return {repo, branch} or null when the string is not a valid branch link. */
    public static String[] parse(String url) {
        if (url == null || !url.startsWith(SCHEME)) return null;
        try {
            String rest = url.substring(SCHEME.length());
            int q = rest.indexOf("?b=");
            if (q < 0) return null;
            String repo = rest.substring(0, q);
            String branch = URLDecoder.decode(rest.substring(q + 3), "UTF-8");
            AgentGitWorkspace.validateRepository(repo, branch);
            return new String[] {repo, branch};
        } catch (Exception invalid) {
            return null;
        }
    }

    private static String fmt(String iso) {
        try { return DATE.format(Instant.parse(iso)); } catch (Exception e) { return iso == null ? "" : iso; }
    }

    private static String firstLine(String message) {
        if (message == null) return "";
        int n = message.indexOf('\n');
        String line = n < 0 ? message : message.substring(0, n);
        return line.length() > 120 ? line.substring(0, 117) + "…" : line;
    }

    /** Newest branches first (by last commit date), with creation date and what changed last. */
    public static JSONObject recent(GitDataApi api, String repo, int limit) throws Exception {
        AgentGitWorkspace.validateRepository(repo, "main");
        limit = Math.max(1, Math.min(5, limit));
        String base = GitDataApi.base(repo);
        String def = api.get(base).optString("default_branch", "main");
        JSONArray all = api.getArray(base + "/branches?per_page=100");
        final class Row { String name; String date = ""; }
        List<Row> rows = new ArrayList<>();
        // The branch list is alphabetical; one lookup per branch (capped) yields the real last-commit date.
        for (int i = 0; i < all.length() && i < 40; i++) {
            String name = all.getJSONObject(i).optString("name");
            if (name.isEmpty()) continue;
            Row row = new Row();
            row.name = name;
            try {
                JSONObject commit = api.get(base + "/commits/" + all.getJSONObject(i).getJSONObject("commit").getString("sha"));
                row.date = commit.getJSONObject("commit").getJSONObject("committer").optString("date");
            } catch (Exception skip) { continue; }
            rows.add(row);
        }
        Collections.sort(rows, new Comparator<Row>() {
            public int compare(Row a, Row b) { return b.date.compareTo(a.date); }
        });
        JSONArray out = new JSONArray();
        for (Row row : rows) {
            if (out.length() >= limit) break;
            JSONObject item = new JSONObject().put("branch", row.name).put("is_default", row.name.equals(def))
                .put("updated", fmt(row.date)).put("link", "[" + row.name + "](" + link(repo, row.name) + ")");
            if (!row.name.equals(def)) {
                try {
                    JSONObject cmp = api.get(base + "/compare/" + GitDataApi.encode(def) + "..." + GitDataApi.encode(row.name));
                    JSONArray commits = cmp.optJSONArray("commits"), files = cmp.optJSONArray("files");
                    item.put("ahead_of_default", cmp.optInt("ahead_by"));
                    if (commits != null && commits.length() > 0) {
                        item.put("created", fmt(commits.getJSONObject(0).getJSONObject("commit").getJSONObject("committer").optString("date")));
                        JSONArray last = new JSONArray();
                        for (int i = commits.length() - 1; i >= 0 && last.length() < 3; i--)
                            last.put(firstLine(commits.getJSONObject(i).getJSONObject("commit").optString("message")));
                        item.put("last_commits", last);
                    }
                    if (files != null) {
                        JSONArray names = new JSONArray();
                        for (int i = 0; i < files.length() && i < 8; i++) {
                            JSONObject f = files.getJSONObject(i);
                            names.put(f.optString("status") + " " + f.optString("filename"));
                        }
                        item.put("changed_files_total", files.length()).put("changed_files", names);
                    }
                } catch (Exception partial) { item.put("note", "сравнение с основной веткой недоступно"); }
            } else {
                JSONArray commits = api.getArray(base + "/commits?sha=" + GitDataApi.encode(row.name) + "&per_page=3");
                JSONArray last = new JSONArray();
                for (int i = 0; i < commits.length(); i++)
                    last.put(firstLine(commits.getJSONObject(i).getJSONObject("commit").optString("message")));
                item.put("last_commits", last);
            }
            out.put(item);
        }
        return new JSONObject().put("repository", repo).put("default_branch", def).put("branches", out)
            .put("instruction", "Show each branch to the user as the markdown link from the 'link' field, verbatim: tapping it makes that branch the active working branch.");
    }

    /** Makes {repo}@{branch} the chat's working branch; verifies it exists and pins the source to its head. */
    public static JSONObject adopt(GitDataApi api, AgentProjectStore projects, String account, String thread,
            String repo, String branch) throws Exception {
        AgentGitWorkspace.validateRepository(repo, branch);
        String sha = GitHubWorkspaceSource.head(api, repo, branch);
        AgentProjectStore.Project current = projects.projectForChat(account, thread);
        projects.save(new AgentProjectStore.Project(account, current.name.isEmpty() ? repo : current.name, repo, branch, current.workspaceId));
        projects.tasks.update(account, thread, t -> {
            AgentController.put(t, "workingBranch", branch);
            AgentController.put(t, "adoptedBranch", branch);
            AgentController.put(t, "adoptedRepository", repo);
            AgentController.put(t, "sourceRepository", repo);
            AgentController.put(t, "sourceCommitSha", sha);
            t.remove("publication");
            t.remove("taskBranch");
        });
        return new JSONObject().put("repository", repo).put("branch", branch).put("head_sha", sha);
    }

    public static JSONObject active(AgentProjectStore projects, String account, String thread) throws Exception {
        AgentProjectStore.Project p = projects.projectForChat(account, thread);
        JSONObject task = projects.tasks.get(account, thread);
        return new JSONObject().put("repository", p.repository).put("branch", p.branch)
            .put("adopted_by_user", !task.optString("adoptedBranch").isEmpty());
    }
}
