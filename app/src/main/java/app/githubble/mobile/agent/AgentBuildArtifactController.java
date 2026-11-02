package app.githubble.mobile.agent;

import app.githubble.mobile.auth.GitHubAccountStore;
import app.githubble.mobile.network.GitHubApiClient;
import app.githubble.mobile.security.SecureStore;
import org.json.JSONObject;

/**
 * Account-pinned GitHub Actions metadata transport for the native AI Workspace.
 * Artifacts are downloaded by GitHubble's existing BuildArtifactDownloads/Receiver.
 */
public final class AgentBuildArtifactController {
    private final GitHubAccountStore accounts;
    private final SecureStore secrets;

    public AgentBuildArtifactController(GitHubAccountStore accounts, SecureStore secrets) {
        this.accounts = accounts;
        this.secrets = secrets;
    }

    private GitHubApiClient snapshot(String expectedAccount) {
        synchronized (accounts) {
            String active = accounts.activeCacheKey();
            if (expectedAccount == null || expectedAccount.equals("guest")
                    || !expectedAccount.equals(active)) {
                throw new IllegalStateException("Выбран другой GitHub-аккаунт");
            }
            String token = secrets.get("github_token");
            if (token.isEmpty()) throw new IllegalStateException("GitHub не авторизован");
            return new GitHubApiClient(secrets, null, token);
        }
    }

    private static String safeRepo(String repo) {
        if (repo == null || !repo.matches("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}"))
            throw new IllegalArgumentException("Недопустимый GitHub-репозиторий");
        return repo;
    }

    public JSONObject recentRuns(String account, String repository) throws Exception {
        GitHubApiClient api = snapshot(account);
        return api.getJson("https://api.github.com/repos/" + safeRepo(repository)
                + "/actions/runs?per_page=15");
    }

    public JSONObject artifacts(String account, String repository, long runId) throws Exception {
        if (runId <= 0) throw new IllegalArgumentException("Некорректный GitHub Actions run ID");
        GitHubApiClient api = snapshot(account);
        return api.getJson("https://api.github.com/repos/" + safeRepo(repository)
                + "/actions/runs/" + runId + "/artifacts?per_page=60");
    }

    public void verifyDownloadAccount(String account) { snapshot(account); }
}
