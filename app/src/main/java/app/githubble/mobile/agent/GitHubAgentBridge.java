package app.githubble.mobile.agent;

import app.githubble.mobile.auth.GitHubAccountStore;
import app.githubble.mobile.controller.CopilotAgentController;
import app.githubble.mobile.controller.RepositoryAdminController;
import app.githubble.mobile.network.GitHubApiClient;
import app.githubble.mobile.network.GitHubGraphQlClient;
import app.githubble.mobile.security.SecureStore;
import org.json.JSONArray;
import org.json.JSONObject;

/** Restricted, accountable GitHub tool bridge for future OmniChat AgentEngine calls. */
public final class GitHubAgentBridge {
    private final GitHubAccountStore accounts;
    private final SecureStore secrets;
    private final GitHubApiClient api;
    private final RepositoryAdminController repositories;
    private final CopilotAgentController copilot;

    public GitHubAgentBridge(GitHubAccountStore accounts, SecureStore secrets, GitHubApiClient api) {
        this.accounts = accounts;
        this.secrets = secrets;
        this.api = api;
        this.repositories = new RepositoryAdminController(api);
        this.copilot = new CopilotAgentController(api, new GitHubGraphQlClient(api));
    }

    public String captureAccount() {
        String id = accounts.activeCacheKey();
        if ("guest".equals(id) || secrets.get("github_token").isEmpty())
            throw new IllegalStateException("Сначала подключите GitHub-аккаунт");
        return id;
    }

    public void requireAccount(String expectedAccountId) {
        String selected = accounts.activeCacheKey();
        if (expectedAccountId == null || expectedAccountId.isEmpty() || "guest".equals(selected)
                || !expectedAccountId.equals(selected) || secrets.get("github_token").isEmpty()) {
            throw new IllegalStateException("Активный GitHub-аккаунт изменился. Переключите аккаунт проекта.");
        }
    }

    public JSONObject createRepository(String expected, String name, String description, boolean privateRepo)
            throws Exception {
        requireAccount(expected);
        if (name == null || !name.matches("[A-Za-z0-9_.-]{1,100}") || ".".equals(name) || "..".equals(name))
            throw new IllegalArgumentException("Недопустимое имя репозитория");
        return repositories.createRepository(name, description, privateRepo, true);
    }

    /** Explicit user approval is required in the UI before this method is invoked. */
    public JSONObject startCopilot(String expected, String fullName, String prompt, String branch)
            throws Exception {
        requireAccount(expected);
        verifyRepo(fullName);
        if (prompt == null || prompt.trim().isEmpty())
            throw new IllegalArgumentException("Пустая задача Copilot");
        return copilot.startTask(fullName, prompt, "", "", true,
                branch == null || branch.isEmpty() ? "main" : branch, "");
    }

    public JSONObject listWorkflows(String expected, String fullName) throws Exception {
        requireAccount(expected);
        verifyRepo(fullName);
        return api.getJson("https://api.github.com/repos/" + fullName + "/actions/workflows?per_page=50");
    }

    /** Workflow dispatch is scoped to the previously approved account and repository. */
    public void dispatchWorkflow(String expected, String fullName, String workflowId, String ref)
            throws Exception {
        requireAccount(expected);
        verifyRepo(fullName);
        if (workflowId == null || !workflowId.matches("[A-Za-z0-9_.-]{1,128}"))
            throw new IllegalArgumentException("Недопустимый workflow");
        String branch = ref == null || ref.isEmpty() ? "main" : ref;
        JSONObject body = new JSONObject().put("ref", branch);
        // GitHub returns HTTP 204 with an empty response body for dispatch.
        api.executeText("POST", "https://api.github.com/repos/" + fullName
                + "/actions/workflows/" + workflowId + "/dispatches", body.toString(), true);
    }

    private static void verifyRepo(String fullName) {
        if (fullName == null || !fullName.matches("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}"))
            throw new IllegalArgumentException("Ожидается репозиторий OWNER/REPO");
    }
}
