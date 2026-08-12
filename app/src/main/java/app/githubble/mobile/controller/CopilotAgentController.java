package app.githubble.mobile.controller;

import app.githubble.mobile.network.GitHubApiClient;
import app.githubble.mobile.network.GitHubGraphQlClient;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;

/** Official GitHub Copilot cloud-agent integration through the public-preview Agent Tasks API. */
public final class CopilotAgentController {
    public static final String COPILOT_LOGIN = "copilot-swe-agent";
    public static final String COPILOT_ASSIGNEE = "copilot-swe-agent[bot]";

    private final GitHubApiClient api;
    private final GitHubGraphQlClient graphql;

    public CopilotAgentController(GitHubApiClient api, GitHubGraphQlClient graphql) {
        this.api = api;
        this.graphql = graphql;
    }

    public JSONObject listRepositoryTasks(String fullName, int page, String states) throws Exception {
        return listRepositoryTasks(fullName, page, states, false);
    }

    public JSONObject listRepositoryTasks(String fullName, int page, String states, boolean archived) throws Exception {
        String[] parts = split(fullName);
        StringBuilder url = new StringBuilder("https://api.github.com/agents/repos/")
                .append(path(parts[0])).append('/').append(path(parts[1]))
                .append("/tasks?per_page=30&page=").append(Math.max(1, page))
                .append("&sort=updated_at&direction=desc&is_archived=").append(archived);
        if (states != null && !states.trim().isEmpty()) {
            url.append("&state=").append(query(states.trim()));
        }
        return api.getJson(url.toString());
    }

    public JSONObject listUserTasks(int page, String states) throws Exception {
        return listUserTasks(page, states, false);
    }

    public JSONObject listUserTasks(int page, String states, boolean archived) throws Exception {
        StringBuilder url = new StringBuilder("https://api.github.com/agents/tasks?per_page=30&page=")
                .append(Math.max(1, page)).append("&sort=updated_at&direction=desc&is_archived=").append(archived);
        if (states != null && !states.trim().isEmpty()) {
            url.append("&state=").append(query(states.trim()));
        }
        return api.getJson(url.toString());
    }

    public JSONObject getTask(String fullName, String taskId) throws Exception {
        String[] parts = split(fullName);
        return api.getJson("https://api.github.com/agents/repos/" + path(parts[0]) + "/" + path(parts[1]) + "/tasks/" + path(taskId));
    }

    public JSONObject getTask(String taskId) throws Exception {
        return api.getJson("https://api.github.com/agents/tasks/" + path(taskId));
    }

    public JSONObject startTask(String fullName, String prompt, String model, String customAgent,
                                boolean createPullRequest, String baseRef, String headRef) throws Exception {
        if (prompt == null || prompt.trim().isEmpty()) throw new IllegalArgumentException("Введите задачу для Copilot");
        String[] parts = split(fullName);
        JSONObject body = new JSONObject();
        body.put("prompt", prompt.trim());
        if (model != null && !model.trim().isEmpty()) body.put("model", model.trim());
        if (customAgent != null && !customAgent.trim().isEmpty()) body.put("custom_agent", customAgent.trim());
        body.put("create_pull_request", createPullRequest);
        if (baseRef != null && !baseRef.trim().isEmpty()) body.put("base_ref", baseRef.trim());
        if (headRef != null && !headRef.trim().isEmpty()) body.put("head_ref", headRef.trim());
        return api.postJson("https://api.github.com/agents/repos/" + path(parts[0]) + "/" + path(parts[1]) + "/tasks", body, true);
    }

    public JSONObject assignIssue(String fullName, int issueNumber, String baseBranch,
                                  String instructions, String model, String customAgent) throws Exception {
        if (issueNumber <= 0) throw new IllegalArgumentException("Некорректный номер Issue");
        split(fullName);
        JSONObject assignment = new JSONObject();
        assignment.put("target_repo", fullName);
        if (baseBranch != null && !baseBranch.trim().isEmpty()) assignment.put("base_branch", baseBranch.trim());
        assignment.put("custom_instructions", instructions == null ? "" : instructions.trim());
        assignment.put("custom_agent", customAgent == null ? "" : customAgent.trim());
        assignment.put("model", model == null ? "" : model.trim());
        JSONObject body = new JSONObject();
        body.put("assignees", new JSONArray().put(COPILOT_ASSIGNEE));
        body.put("agent_assignment", assignment);
        return api.postJson("https://api.github.com/repos/" + fullName + "/issues/" + issueNumber + "/assignees", body, true);
    }

    /** Returns true only when GitHub exposes the Copilot agent as assignable for this repository/user. */
    public boolean isAssignable(String fullName) throws Exception {
        String[] parts = split(fullName);
        String query = "query CopilotAvailability($owner:String!,$name:String!){repository(owner:$owner,name:$name){suggestedActors(capabilities:[CAN_BE_ASSIGNED],first:100){nodes{login __typename}}}}";
        JSONObject variables = new JSONObject().put("owner", parts[0]).put("name", parts[1]);
        JSONObject data = graphql.execute(query, variables);
        JSONObject repository = data.optJSONObject("repository");
        JSONObject connection = repository == null ? null : repository.optJSONObject("suggestedActors");
        JSONArray nodes = connection == null ? null : connection.optJSONArray("nodes");
        if (nodes == null) return false;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            String login = node == null ? "" : node.optString("login", "");
            if (COPILOT_LOGIN.equals(login) || COPILOT_ASSIGNEE.equals(login)) return true;
        }
        return false;
    }

    /** Reads repository-level custom agent profiles from .github/agents on a selected branch. */
    public JSONArray listCustomAgents(String fullName, String ref) throws Exception {
        split(fullName);
        String url = "https://api.github.com/repos/" + fullName + "/contents/.github/agents";
        if (ref != null && !ref.trim().isEmpty()) url += "?ref=" + query(ref.trim());
        JSONArray contents;
        try {
            contents = api.getArray(url);
        } catch (GitHubApiClient.ApiException error) {
            if (error.statusCode == 404) return new JSONArray();
            throw error;
        }
        JSONArray result = new JSONArray();
        for (int i = 0; i < contents.length(); i++) {
            JSONObject file = contents.optJSONObject(i);
            if (file == null || !"file".equals(file.optString("type"))) continue;
            String name = file.optString("name", "");
            String lower = name.toLowerCase();
            String suffix = lower.endsWith(".agent.md") ? ".agent.md" : lower.endsWith(".md") ? ".md" : "";
            if (suffix.isEmpty()) continue;
            String id = name.substring(0, name.length() - suffix.length());
            if (id.trim().isEmpty()) continue;
            result.put(new JSONObject()
                    .put("id", id)
                    .put("name", name)
                    .put("path", file.optString("path", ".github/agents/" + name))
                    .put("sha", file.optString("sha", ""))
                    .put("html_url", file.optString("html_url", ""))
                    .put("download_url", file.optString("download_url", "")));
        }
        return result;
    }

    /** Runs only non-destructive requests and reports which read capabilities are available. */
    public CopilotPermissionReport checkPermissionMatrix(String fullName) {
        CopilotPermissionReport report = new CopilotPermissionReport();
        probe(report, "metadata_read", "Metadata: чтение", true,
                () -> api.getJson("https://api.github.com/repos/" + fullName));
        probe(report, "agent_tasks_read", "Agent tasks: чтение", true,
                () -> listRepositoryTasks(fullName, 1, "", false));
        probe(report, "contents_read", "Contents: чтение", true,
                () -> api.getArray("https://api.github.com/repos/" + fullName + "/contents?per_page=1"));
        probe(report, "issues_read", "Issues: чтение", false,
                () -> api.getArray("https://api.github.com/repos/" + fullName + "/issues?per_page=1"));
        probe(report, "pulls_read", "Pull requests: чтение", false,
                () -> api.getArray("https://api.github.com/repos/" + fullName + "/pulls?per_page=1"));
        probe(report, "actions_read", "Actions: чтение", false,
                () -> api.getJson("https://api.github.com/repos/" + fullName + "/actions/workflows?per_page=1"));
        try {
            boolean assignable = isAssignable(fullName);
            report.add(new CopilotPermissionReport.Check("copilot_assignable", "Copilot доступен для назначения",
                    assignable, false, assignable ? "GitHub вернул Copilot в suggestedActors" : "Copilot не найден среди доступных исполнителей"));
        } catch (Exception error) {
            report.add(new CopilotPermissionReport.Check("copilot_assignable", "Copilot доступен для назначения",
                    false, false, safeMessage(error)));
        }
        report.add(new CopilotPermissionReport.Check("agent_tasks_write", "Agent tasks: запись",
                false, true, "Проверяется только при запуске задачи: безопасного read-only endpoint для подтверждения записи нет"));
        return report;
    }

    public static JSONArray tasks(JSONObject root) {
        JSONArray tasks = root == null ? null : root.optJSONArray("tasks");
        return tasks == null ? new JSONArray() : tasks;
    }

    public static boolean isActiveState(String state) {
        return "queued".equals(state) || "in_progress".equals(state) || "idle".equals(state) || "waiting_for_user".equals(state);
    }

    public static String stateLabel(String state) {
        if (state == null) return "Неизвестно";
        switch (state) {
            case "queued": return "В очереди";
            case "in_progress": return "Выполняется";
            case "completed": return "Завершено";
            case "failed": return "Ошибка";
            case "idle": return "Ожидает";
            case "waiting_for_user": return "Нужен ответ";
            case "timed_out": return "Время истекло";
            case "cancelled": return "Отменено";
            default: return state;
        }
    }

    public static String repositoryFromTask(JSONObject task) {
        if (task == null) return "";
        String direct = task.optString("repository_full_name", "");
        if (!direct.isEmpty()) return direct;
        JSONObject repository = task.optJSONObject("repository");
        if (repository != null) {
            String fullName = repository.optString("full_name", "");
            if (!fullName.isEmpty()) return fullName;
        }
        String html = task.optString("html_url", "");
        String marker = "github.com/";
        int start = html.indexOf(marker);
        if (start < 0) return "";
        String path = html.substring(start + marker.length());
        String[] parts = path.split("/");
        return parts.length >= 2 ? parts[0] + "/" + parts[1] : "";
    }

    public static int pullNumber(JSONObject task) {
        JSONArray artifacts = task == null ? null : task.optJSONArray("artifacts");
        if (artifacts == null) return 0;
        for (int i = 0; i < artifacts.length(); i++) {
            JSONObject artifact = artifacts.optJSONObject(i);
            if (artifact == null || !"pull".equals(artifact.optString("type"))) continue;
            JSONObject data = artifact.optJSONObject("data");
            if (data == null) continue;
            int number = data.optInt("number", 0);
            if (number <= 0) number = data.optInt("id", 0);
            if (number > 0) return number;
        }
        return 0;
    }

    public static String artifactLabel(JSONObject artifact) {
        if (artifact == null) return "Результат";
        String type = artifact.optString("type", "artifact");
        JSONObject data = artifact.optJSONObject("data");
        int number = data == null ? 0 : data.optInt("number", data.optInt("id", 0));
        switch (type) {
            case "pull": return number > 0 ? "Pull Request #" + number : "Pull Request";
            case "issue": return number > 0 ? "Issue #" + number : "Issue";
            case "branch": return "Ветка" + valueSuffix(data, "name", "ref");
            case "commit": return "Коммит" + valueSuffix(data, "sha", "id");
            case "workflow_run": return "Проверка GitHub Actions" + (number > 0 ? " #" + number : "");
            case "file": return "Файл" + valueSuffix(data, "path", "name");
            case "session": return "Сеанс Copilot";
            default: return type.replace('_', ' ');
        }
    }

    public static String artifactUrl(JSONObject artifact) {
        if (artifact == null) return "";
        JSONObject data = artifact.optJSONObject("data");
        if (data == null) return artifact.optString("html_url", artifact.optString("url", ""));
        String[] fields = {"html_url", "url", "web_url"};
        for (String field : fields) {
            String value = data.optString(field, "");
            if (!value.isEmpty()) return value;
        }
        return artifact.optString("html_url", artifact.optString("url", ""));
    }

    public static String sessionUrl(JSONObject session, JSONObject task) {
        if (session != null) {
            String html = session.optString("html_url", "");
            if (!html.isEmpty()) return html;
            String url = session.optString("url", "");
            if (!url.isEmpty() && url.startsWith("https://github.com/")) return url;
        }
        return task == null ? "" : task.optString("html_url", "");
    }

    /** REST currently exposes archived filtering, but not a public archive/unarchive mutation endpoint. */
    public static boolean archiveMutationAvailable() { return false; }

    private interface Probe { Object run() throws Exception; }

    private static void probe(CopilotPermissionReport report, String id, String title, boolean required, Probe probe) {
        try {
            probe.run();
            report.add(new CopilotPermissionReport.Check(id, title, true, required, "Доступ подтверждён безопасным запросом"));
        } catch (Exception error) {
            report.add(new CopilotPermissionReport.Check(id, title, false, required, safeMessage(error)));
        }
    }

    private static String safeMessage(Exception error) {
        String message = error == null ? "Неизвестная ошибка" : error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private static String valueSuffix(JSONObject data, String first, String second) {
        if (data == null) return "";
        String value = data.optString(first, data.optString(second, ""));
        if (value.isEmpty()) return "";
        if ("sha".equals(first) && value.length() > 10) value = value.substring(0, 10);
        return " · " + value;
    }

    private static String[] split(String fullName) {
        String value = fullName == null ? "" : fullName.trim();
        int slash = value.indexOf('/');
        if (slash <= 0 || slash >= value.length() - 1) throw new IllegalArgumentException("Ожидается репозиторий OWNER/REPO");
        return new String[]{value.substring(0, slash), value.substring(slash + 1)};
    }

    private static String path(String value) {
        try { return URLEncoder.encode(value == null ? "" : value, "UTF-8").replace("+", "%20"); }
        catch (Exception ignored) { return value == null ? "" : value; }
    }

    private static String query(String value) { return path(value); }
}
