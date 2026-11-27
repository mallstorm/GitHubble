package app.githubble.mobile.repository;

import app.githubble.mobile.network.GitHubApiClient;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Deployments waiting for a reviewer (Environments with required reviewers): list, approve, reject. */
public final class PendingDeployments {
    private PendingDeployments() {}

    public static final class Item {
        public final long runId, envId; public final String run, branch, env, url; public final boolean canApprove; public final int waitMinutes;
        Item(long runId, long envId, String run, String branch, String env, String url, boolean canApprove, int waitMinutes) {
            this.runId = runId; this.envId = envId; this.run = run; this.branch = branch; this.env = env; this.url = url; this.canApprove = canApprove; this.waitMinutes = waitMinutes;
        }
    }

    static List<Item> parse(JSONObject run, JSONArray pending) {
        List<Item> out = new ArrayList<>();
        String title = run.isNull("display_title") ? run.optString("name", "") : run.optString("display_title", "");
        for (int i = 0; i < pending.length(); i++) {
            JSONObject p = pending.optJSONObject(i), env = p == null ? null : p.optJSONObject("environment");
            if (env == null) continue;
            out.add(new Item(run.optLong("id"), env.optLong("id"), title, run.isNull("head_branch") ? "" : run.optString("head_branch", ""), env.optString("name"), run.optString("html_url"),
                p.optBoolean("current_user_can_approve"), p.optInt("wait_timer")));
        }
        return out;
    }

    public static List<Item> load(GitHubApiClient api, String repo) throws Exception {
        JSONObject runs = Gh.object(api, Gh.repo(repo) + "/actions/runs?status=waiting&per_page=20");
        JSONArray list = runs.optJSONArray("workflow_runs");
        List<Item> out = new ArrayList<>();
        if (list == null) return out;
        for (int i = 0; i < list.length(); i++) {
            JSONObject run = list.optJSONObject(i);
            if (run == null) continue;
            out.addAll(parse(run, Gh.array(api, Gh.repo(repo) + "/actions/runs/" + run.optLong("id") + "/pending_deployments")));
        }
        return out;
    }

    static JSONObject body(long envId, boolean approve, String comment) throws Exception {
        String note = comment == null ? "" : comment.trim();
        if (note.isEmpty()) note = approve ? "Одобрено в GitHubble" : "Отклонено в GitHubble";
        return new JSONObject().put("environment_ids", new JSONArray().put(envId)).put("state", approve ? "approved" : "rejected").put("comment", note);
    }

    public static void review(GitHubApiClient api, String repo, long runId, long envId, boolean approve, String comment) throws Exception {
        api.executeText("POST", Gh.repo(repo) + "/actions/runs/" + runId + "/pending_deployments", body(envId, approve, comment).toString(), true);
    }
}
