package app.githubble.mobile.agent;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Permanent account-scoped projects; creating a project never creates/attaches a Workspace. */
public final class ProjectRegistry {
    private final UnifiedAgentStore store;
    public ProjectRegistry(UnifiedAgentStore store) { this.store = store; }
    public JSONObject create(String account, String name, String repository, String branch) {
        synchronized (UnifiedAgentStore.LOCK) {
            JSONObject state = store.read(account);
            JSONObject project = make(account, UUID.randomUUID().toString(), name, repository, branch, "");
            putProject(state, project); store.save(account, state); return UnifiedAgentStore.copy(project);
        }
    }
    public JSONArray list(String account) {
        synchronized (UnifiedAgentStore.LOCK) {
            JSONObject projects = store.read(account).optJSONObject("projects");
            JSONArray result = new JSONArray();
            for (String id : UnifiedAgentStore.keys(projects)) result.put(projects.optJSONObject(id));
            return result;
        }
    }
    public JSONObject get(String account, String id) {
        synchronized (UnifiedAgentStore.LOCK) {
            JSONObject project = store.read(account).optJSONObject("projects").optJSONObject(id);
            if (project == null) throw new IllegalArgumentException("Проект не найден");
            return UnifiedAgentStore.copy(project);
        }
    }
    static JSONObject make(String account, String id, String name, String repo, String branch, String workspace) {
        if (name == null || name.isBlank()) name = repo == null || repo.isBlank() ? "Проект" : repo;
        if (repo != null && !repo.isEmpty() && !repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))
            throw new IllegalArgumentException("Укажите OWNER/REPO");
        try {
            long now = System.currentTimeMillis();
            return new JSONObject().put("projectId", id).put("accountKey", account).put("name", name)
                .put("origin", repo == null || repo.isEmpty() ? "LOCAL" : "GITHUB")
                .put("repository", repo == null ? "" : repo).put("baseBranch", branch == null || branch.isBlank() ? "main" : branch)
                .put("workspaceId", workspace == null ? "" : workspace).put("localUri", "")
                .put("configuration", new JSONObject()).put("memoryScope", workspace == null || workspace.isEmpty() ? id : workspace)
                .put("modelAccess", false).put("createdAt", now).put("updatedAt", now).put("archived", false);
        } catch (org.json.JSONException bad) { throw new IllegalStateException(bad); }
    }
    private static void putProject(JSONObject state, JSONObject project) {
        try { state.getJSONObject("projects").put(project.getString("projectId"), project); }
        catch (org.json.JSONException bad) { throw new IllegalStateException(bad); }
    }
    /** Explicit per-project consent. No legacy account-wide flag can silently opt projects in. */
    public void modelAccess(String account, String id, boolean allowed) {
        synchronized (UnifiedAgentStore.LOCK) {
            JSONObject state = store.read(account), project = state.optJSONObject("projects").optJSONObject(id);
            if (project == null) throw new IllegalArgumentException("Проект не найден");
            try { project.put("modelAccess", allowed).put("updatedAt", System.currentTimeMillis()); }
            catch (org.json.JSONException bad) { throw new IllegalStateException(bad); }
            store.save(account, state);
        }
    }
    /** Restartable migration. Ambiguous repository matches stay separate; every chat retains its ID. */
    public void migrate(String account, Map<String, ?> legacy) {
        synchronized (UnifiedAgentStore.LOCK) {
            store.backup(account, legacy);
            JSONObject state = store.read(account);
            if (state.optBoolean("legacyMigrated")) return;
            String prefix = UnifiedAgentStore.key(account) + "_";
            Map<String, JSONObject> bindings = new TreeMap<>();
            Map<String, Set<String>> repoWorkspaces = new HashMap<>();
            Set<String> threads = new TreeSet<>();
            JSONArray index;
            try { index = new JSONArray(String.valueOf(legacy.get(prefix + "thread_index"))); }
            catch (Exception noIndex) { index = new JSONArray(); }
            Map<String, String> titles = new HashMap<>();
            for (int i = 0; i < index.length(); i++) {
                JSONObject t = index.optJSONObject(i);
                if (t != null) { threads.add(t.optString("id")); titles.put(t.optString("id"), t.optString("title")); }
            }
            Object active = legacy.get(prefix + "active_thread");
            if (active instanceof String && !((String)active).isEmpty()) threads.add((String) active);
            for (Map.Entry<String, ?> e : legacy.entrySet()) {
                if (e.getKey().startsWith(prefix + "thread_") && !e.getKey().equals(prefix + "thread_index"))
                    threads.add(e.getKey().substring((prefix + "thread_").length()));
                if (!e.getKey().startsWith(prefix + "project_")) continue;
                try {
                    JSONObject b = new JSONObject(String.valueOf(e.getValue()));
                    if (!account.equals(b.optString("account_id"))) continue;
                    String thread = e.getKey().substring((prefix + "project_").length());
                    threads.add(thread); bindings.put(thread, b);
                    String repo = b.optString("repository").toLowerCase(Locale.ROOT), ws = b.optString("workspace_id");
                    if (!repo.isEmpty() && !ws.isEmpty()) repoWorkspaces.computeIfAbsent(repo, r -> new HashSet<>()).add(ws);
                } catch (org.json.JSONException corrupt) { throw new IllegalStateException("Повреждена привязка проекта; миграция остановлена", corrupt); }
            }
            try {
                for (String thread : threads) {
                    if (thread.isEmpty()) continue;
                    JSONObject binding = bindings.get(thread);
                    String projectId = "";
                    if (binding != null && (!binding.optString("workspace_id").isEmpty()
                            || !binding.optString("repository").isEmpty() || !binding.optString("name").isEmpty())) {
                        String ws = binding.optString("workspace_id"), repo = binding.optString("repository");
                        boolean unambiguous = repoWorkspaces.getOrDefault(repo.toLowerCase(Locale.ROOT), Collections.emptySet()).size() <= 1;
                        String identity;
                        if (!ws.isEmpty()) identity = "workspace:" + ws;
                        else if (!repo.isEmpty() && unambiguous && repoWorkspaces.containsKey(repo.toLowerCase(Locale.ROOT)))
                            identity = "workspace:" + repoWorkspaces.get(repo.toLowerCase(Locale.ROOT)).iterator().next();
                        else identity = repo.isEmpty() ? "chat:" + thread : "repository:" + repo.toLowerCase(Locale.ROOT);
                        projectId = UUID.nameUUIDFromBytes((account + ":" + identity)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                        JSONObject existing = state.getJSONObject("projects").optJSONObject(projectId);
                        // Same Workspace carrying incompatible repository bindings is ambiguous too.
                        if (existing != null && !repo.isEmpty() && !existing.optString("repository").isEmpty()
                                && !repo.equalsIgnoreCase(existing.optString("repository")))
                            projectId = UUID.nameUUIDFromBytes((account + ":conflict:" + thread)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                        existing = state.getJSONObject("projects").optJSONObject(projectId);
                        if (existing == null) putProject(state, make(account, projectId, binding.optString("name"), repo,
                            binding.optString("branch", "main"), ws));
                        else {
                            if (existing.optString("workspaceId").isEmpty()) existing.put("workspaceId", ws);
                            if (existing.optString("repository").isEmpty() && !repo.isEmpty())
                                existing.put("repository", repo).put("origin", "GITHUB");
                        }
                    }
                    if (!state.getJSONObject("tasks").has(thread)) {
                        JSONObject task = TaskRepository.make(account, thread, projectId, titles.getOrDefault(thread, "Задача"), "");
                        if (binding != null) task.put("workingBranch", binding.optString("branch", "main"));
                        state.getJSONObject("tasks").put(thread, task);
                    }
                }
                state.put("legacyMigrated", true); store.save(account, state);
            } catch (org.json.JSONException bad) { throw new IllegalStateException(bad); }
        }
    }
    /** Bind one task to a permanent project; a blank binding only detaches that task. */
    public void bindTask(String account, String taskId, String name, String repo, String branch, String workspace) {
        synchronized (UnifiedAgentStore.LOCK) {
            JSONObject state = store.read(account), task = state.optJSONObject("tasks").optJSONObject(taskId);
            if (task == null) throw new IllegalArgumentException("Задача не найдена");
            try {
                if (name.isEmpty() && repo.isEmpty() && workspace.isEmpty()) {
                    task.put("projectId", ""); task.put("workingBranch", ""); store.save(account, state); return;
                }
                JSONObject projects = state.getJSONObject("projects");
                JSONObject selected = projects.optJSONObject(task.optString("projectId"));
                boolean updatingCurrent = selected != null;
                // An explicit different Workspace means a different project, never overwrite its identity.
                if (selected != null && !workspace.isEmpty() && !selected.optString("workspaceId").isEmpty()
                        && !workspace.equals(selected.optString("workspaceId"))) selected = null;
                if (selected == null) {
                    java.util.List<JSONObject> candidates = new ArrayList<>();
                    for (String id : UnifiedAgentStore.keys(projects)) {
                        JSONObject p = projects.getJSONObject(id);
                        if ((!workspace.isEmpty() && workspace.equals(p.optString("workspaceId")))
                                || (workspace.isEmpty() && !repo.isEmpty() && repo.equalsIgnoreCase(p.optString("repository"))))
                            candidates.add(p);
                    }
                    if (candidates.size() == 1) selected = candidates.get(0);
                }
                if (selected == null) {
                    selected = make(account, UUID.randomUUID().toString(), name, repo, branch, workspace);
                    putProject(state, selected);
                } else {
                    // A second legacy conversation sharing a Workspace must not erase its GitHub binding.
                    if (!updatingCurrent && repo.isEmpty()) repo = selected.optString("repository");
                    // Consent is revoked if the repository identity changes.
                    if (!repo.equalsIgnoreCase(selected.optString("repository"))) selected.put("modelAccess", false);
                    selected.put("name", name.isEmpty() ? selected.optString("name") : name)
                        .put("repository", repo).put("baseBranch", branch).put("workspaceId", workspace)
                        .put("origin", repo.isEmpty() ? "LOCAL" : "GITHUB").put("updatedAt", System.currentTimeMillis());
                }
                task.put("projectId", selected.getString("projectId")).put("workingBranch", branch);
                store.save(account, state);
            } catch (org.json.JSONException bad) { throw new IllegalStateException(bad); }
        }
    }
    /** Detach metadata only; a project and its history survive deletion of local phone files. */
    public void detachWorkspace(String account, String workspace) {
        synchronized (UnifiedAgentStore.LOCK) {
            JSONObject state = store.read(account);
            try {
                for (String id : UnifiedAgentStore.keys(state.getJSONObject("projects"))) {
                    JSONObject p = state.getJSONObject("projects").getJSONObject(id);
                    if (workspace.equals(p.optString("workspaceId"))) p.put("workspaceId", "");
                }
                store.save(account, state);
            } catch (org.json.JSONException bad) { throw new IllegalStateException(bad); }
        }
    }
}
