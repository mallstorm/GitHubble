package app.githubble.mobile.agent;

import app.githubble.mobile.agent.core.Evidence;
import app.githubble.mobile.network.GitHubApiClient;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** {@link Evidence} read from the GitHub REST API with an account-bound client. */
public final class GitHubEvidence implements Evidence {
    private final GitHubApiClient api;

    public GitHubEvidence(GitHubApiClient api) { this.api = api; }

    private static String base(String repo) {
        if (repo == null || !repo.matches("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}"))
            throw new IllegalArgumentException("Некорректный репозиторий");
        return "https://api.github.com/repos/" + repo;
    }

    private static boolean notFound(Exception e) {
        String m = e.getMessage();
        return m != null && (m.contains("404") || m.toLowerCase(java.util.Locale.ROOT).contains("not found"));
    }

    @Override public String branchHead(String repo, String branch) throws Exception {
        AgentGitWorkspace.validateRepository(repo, branch);
        try {
            JSONObject ref = api.getJsonFresh(base(repo) + "/git/ref/heads/" + GitDataApi.encode(branch));
            JSONObject object = ref.optJSONObject("object");
            return object == null ? null : object.optString("sha");
        } catch (Exception e) {
            if (notFound(e)) return "";
            throw e;
        }
    }

    @Override public Boolean commitExists(String repo, String sha) throws Exception {
        if (sha == null || !sha.matches("[a-fA-F0-9]{7,40}")) return Boolean.FALSE;
        try {
            api.getJsonFresh(base(repo) + "/commits/" + sha);
            return Boolean.TRUE;
        } catch (Exception e) {
            if (notFound(e)) return Boolean.FALSE;
            throw e;
        }
    }

    @Override public PullInfo pullRequest(String repo, int number) throws Exception {
        try {
            JSONObject pr = api.getJsonFresh(base(repo) + "/pulls/" + number);
            JSONObject head = pr.optJSONObject("head"), baseRef = pr.optJSONObject("base");
            return new PullInfo(true, pr.optString("state"), head == null ? "" : head.optString("sha"),
                baseRef == null ? "" : baseRef.optString("ref"));
        } catch (Exception e) {
            if (notFound(e)) return new PullInfo(false, "", "", "");
            throw e;
        }
    }

    @Override public RunInfo workflowRun(String repo, long runId) throws Exception {
        if (runId <= 0) throw new IllegalArgumentException("run_id");
        try {
            JSONObject run = api.getJsonFresh(base(repo) + "/actions/runs/" + runId);
            return new RunInfo(run.optString("status"), run.optString("conclusion"),
                run.optString("head_sha"), run.optString("head_branch"));
        } catch (Exception e) {
            if (notFound(e)) return null;
            throw e;
        }
    }

    @Override public List<ArtifactInfo> artifacts(String repo, long runId) throws Exception {
        JSONObject response = api.getJsonFresh(base(repo) + "/actions/runs/" + runId + "/artifacts?per_page=100");
        JSONArray items = response.optJSONArray("artifacts");
        List<ArtifactInfo> out = new ArrayList<>();
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject a = items.optJSONObject(i);
            if (a != null) out.add(new ArtifactInfo(a.optString("name"), a.optLong("size_in_bytes"), a.optBoolean("expired")));
        }
        return out;
    }
}
