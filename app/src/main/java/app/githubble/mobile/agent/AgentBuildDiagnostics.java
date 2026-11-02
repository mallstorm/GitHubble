package app.githubble.mobile.agent;

import app.githubble.mobile.auth.GitHubAccountStore;
import app.githubble.mobile.network.GitHubApiClient;
import app.githubble.mobile.security.SecureStore;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Read-only, account-bound Actions diagnostics for a single GitHub Actions run.
 * Used to prepare a limited error context for an explicitly approved AI repair request.
 */
public final class AgentBuildDiagnostics {
    private final GitHubAccountStore accounts;
    private final SecureStore secrets;
    public AgentBuildDiagnostics(GitHubAccountStore accounts, SecureStore secrets) {
        this.accounts = accounts;
        this.secrets = secrets;
    }

    private GitHubApiClient api(String account) {
        synchronized (accounts) {
            if (account == null || "guest".equals(account) || !account.equals(accounts.activeCacheKey()))
                throw new IllegalStateException("Активный GitHub-аккаунт изменился");
            String token = secrets.get("github_token");
            if (token.isEmpty()) throw new IllegalStateException("Не выполнена авторизация GitHub");
            return new GitHubApiClient(secrets, null, token);
        }
    }

    private static String base(String repo) {
        if (repo == null || !repo.matches("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}"))
            throw new IllegalArgumentException("Некорректный репозиторий");
        return "https://api.github.com/repos/" + repo;
    }

    public JSONObject latestRun(String account, String repo, String sha) throws Exception {
        if (sha == null || !sha.matches("[a-fA-F0-9]{40}"))
            throw new IllegalArgumentException("Некорректный SHA коммита");
        JSONObject response = api(account).getJson(base(repo) + "/actions/runs?head_sha=" + sha + "&per_page=15");
        JSONArray runs = response.optJSONArray("workflow_runs");
        if (runs == null || runs.length() == 0) return null;
        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run != null && sha.equalsIgnoreCase(run.optString("head_sha"))) return run;
        }
        return null;
    }

    public JSONObject summarize(String account, String repo, long runId) throws Exception {
        if (runId <= 0) throw new IllegalArgumentException("Некорректный ID сборки");
        GitHubApiClient client = api(account);
        String endpoint = base(repo) + "/actions/runs/" + runId;
        JSONObject run = client.getJson(endpoint);
        JSONObject jobs = client.getJson(endpoint + "/jobs?per_page=100");
        JSONArray items = jobs.optJSONArray("jobs");
        JSONArray failures = new JSONArray();
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject job = items.optJSONObject(i);
            if (job == null) continue;
            String conclusion = job.optString("conclusion");
            if (!"failure".equals(conclusion) && !"timed_out".equals(conclusion)
                    && !"cancelled".equals(conclusion)) continue;
            JSONArray steps = job.optJSONArray("steps");
            JSONArray broken = new JSONArray();
            for (int j = 0; steps != null && j < steps.length(); j++) {
                JSONObject step = steps.optJSONObject(j);
                if (step != null && ("failure".equals(step.optString("conclusion"))
                        || "timed_out".equals(step.optString("conclusion")))) {
                    broken.put(step.optString("name", "step"));
                }
            }
            failures.put(new JSONObject().put("job", job.optString("name"))
                    .put("conclusion", conclusion).put("steps", broken));
        }
        return new JSONObject().put("run_id", runId)
                .put("sha", run.optString("head_sha"))
                .put("status", run.optString("status"))
                .put("conclusion", run.optString("conclusion"))
                .put("url", run.optString("html_url"))
                .put("failures", failures);
    }

    /** Never includes logs or secrets: job/step names are metadata only. */
    public String repairContext(JSONObject diagnostics) {
        if (diagnostics == null) return "Сборка ещё не найдена";
        return "GitHub Actions diagnostics (metadata only, no raw logs): "
                + diagnostics.toString();
    }
}
