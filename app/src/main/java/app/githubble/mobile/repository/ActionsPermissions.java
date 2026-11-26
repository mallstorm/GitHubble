package app.githubble.mobile.repository;

import app.githubble.mobile.network.GitHubApiClient;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Settings → Actions → General: whether Actions run, allowed actions, GITHUB_TOKEN rights and approval of fork pull requests. */
public final class ActionsPermissions {
    private ActionsPermissions() {}

    public static final class State {
        public boolean enabled = true, canApprove, githubOwned = true, verified;
        public String allowed = "all", token = "read", fork = "";
        public final List<String> patterns = new ArrayList<>();
    }

    static String base(String repo) { return Gh.repo(repo) + "/actions/permissions"; }

    static void read(State s, JSONObject general, JSONObject workflow, JSONObject fork, JSONObject selected) {
        if (general != null) { s.enabled = general.optBoolean("enabled", true); s.allowed = general.optString("allowed_actions", "all"); }
        if (workflow != null) { s.token = workflow.optString("default_workflow_permissions", "read"); s.canApprove = workflow.optBoolean("can_approve_pull_request_reviews", false); }
        if (fork != null) s.fork = fork.optString("approval_policy", "");
        if (selected != null) {
            s.githubOwned = selected.optBoolean("github_owned_allowed", true); s.verified = selected.optBoolean("verified_allowed", false);
            JSONArray list = selected.optJSONArray("patterns_allowed");
            if (list != null) for (int i = 0; i < list.length(); i++) s.patterns.add(list.optString(i));
        }
    }

    public static State load(GitHubApiClient api, String repo) throws Exception {
        State s = new State();
        JSONObject general = Gh.object(api, base(repo)), workflow = Gh.object(api, base(repo) + "/workflow"), fork = null, selected = null;
        try { fork = Gh.object(api, base(repo) + "/fork-pr-contributor-approval"); } catch (GitHubApiClient.ApiException e) { if (e.statusCode != 404 && e.statusCode != 403) throw e; }
        if ("selected".equals(general.optString("allowed_actions"))) selected = Gh.object(api, base(repo) + "/selected-actions");
        read(s, general, workflow, fork, selected);
        return s;
    }

    public static void saveGeneral(GitHubApiClient api, String repo, boolean enabled, String allowed) throws Exception {
        if (!allowed.matches("all|local_only|selected")) throw new IllegalArgumentException("Недопустимый режим");
        JSONObject body = new JSONObject().put("enabled", enabled);
        if (enabled) body.put("allowed_actions", allowed);
        api.putJson(base(repo), body);
    }

    public static void saveToken(GitHubApiClient api, String repo, String token, boolean canApprove) throws Exception {
        if (!token.matches("read|write")) throw new IllegalArgumentException("Недопустимые права токена");
        api.putJson(base(repo) + "/workflow", new JSONObject().put("default_workflow_permissions", token).put("can_approve_pull_request_reviews", canApprove));
    }

    public static void saveFork(GitHubApiClient api, String repo, String policy) throws Exception {
        if (!policy.matches("first_time_contributors_new_to_github|first_time_contributors|all_external_contributors")) throw new IllegalArgumentException("Недопустимая политика");
        api.putJson(base(repo) + "/fork-pr-contributor-approval", new JSONObject().put("approval_policy", policy));
    }

    public static void saveSelected(GitHubApiClient api, String repo, boolean githubOwned, boolean verified, List<String> patterns) throws Exception {
        JSONArray list = new JSONArray();
        for (String p : patterns) if (!p.trim().isEmpty()) list.put(p.trim());
        api.putJson(base(repo) + "/selected-actions", new JSONObject().put("github_owned_allowed", githubOwned).put("verified_allowed", verified).put("patterns_allowed", list));
    }

    public static List<String> splitPatterns(String raw) {
        List<String> out = new ArrayList<>();
        if (raw != null) for (String part : raw.split("[,\\n]")) { String p = part.trim(); if (!p.isEmpty() && !out.contains(p)) out.add(p); }
        return out;
    }
}
