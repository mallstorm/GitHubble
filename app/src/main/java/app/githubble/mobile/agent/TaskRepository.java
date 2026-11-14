package app.githubble.mobile.agent;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;

/** Durable task identity and execution metadata. Transcripts remain in AgentConversationStore. */
public final class TaskRepository {
    private final UnifiedAgentStore store;
    public TaskRepository(UnifiedAgentStore store) { this.store = store; }
    static JSONObject make(String account, String id, String project, String title, String goal) {
        try {
            long now = System.currentTimeMillis();
            return new JSONObject().put("taskId", id).put("accountKey", account).put("projectId", project)
                .put("conversationId", id).put("title", title).put("goal", goal).put("state", "READY")
                .put("workingBranch", "").put("changeSets", new JSONArray()).put("evidence", new JSONArray())
                .put("budget", new JSONObject().put("rounds", 0).put("tools", 0).put("inputTokens", 0)
                    .put("outputTokens", 0).put("costMicros", 0).put("maxRounds", 12).put("maxTools", 100))
                .put("createdAt", now).put("updatedAt", now);
        } catch (org.json.JSONException bad) { throw new IllegalStateException(bad); }
    }
    public JSONObject create(String account, String project, String title, String goal) {
        return createWithId(account, UUID.randomUUID().toString().replace("-", ""), project, title, goal);
    }
    public JSONObject createWithId(String account, String id, String project, String title, String goal) {
        synchronized (UnifiedAgentStore.LOCK) {
            JSONObject state = store.read(account);
            if (title==null||title.isBlank()||title.length()>96||goal==null||goal.length()>12000)
                throw new IllegalArgumentException("Название задачи: 1–96 символов, цель: до 12000");
            if (project == null || (!project.isEmpty() && !state.optJSONObject("projects").has(project)))
                throw new IllegalArgumentException("Проект не найден");
            if (!id.matches("[a-zA-Z0-9_-]{8,64}")) throw new IllegalArgumentException("Некорректный Task ID");
            if (state.optJSONObject("tasks").has(id)) throw new IllegalStateException("Задача уже существует");
            JSONObject task = make(account, id, project, title, goal);
            try { state.getJSONObject("tasks").put(id, task); }
            catch (org.json.JSONException bad) { throw new IllegalStateException(bad); }
            store.save(account, state); return UnifiedAgentStore.copy(task);
        }
    }
    public JSONObject get(String account, String id) {
        synchronized (UnifiedAgentStore.LOCK) {
            JSONObject t = store.read(account).optJSONObject("tasks").optJSONObject(id);
            if (t == null) throw new IllegalArgumentException("Задача не найдена");
            return UnifiedAgentStore.copy(t);
        }
    }
    public JSONArray list(String account, String project) {
        synchronized (UnifiedAgentStore.LOCK) {
            JSONArray result = new JSONArray(); JSONObject tasks = store.read(account).optJSONObject("tasks");
            for (String id : UnifiedAgentStore.keys(tasks)) {
                JSONObject t = tasks.optJSONObject(id);
                if (project == null || project.equals(t.optString("projectId"))) result.put(t);
            }
            return result;
        }
    }
    public void update(String account, String id, java.util.function.Consumer<JSONObject> edit) {
        synchronized (UnifiedAgentStore.LOCK) {
            JSONObject state = store.read(account), task = state.optJSONObject("tasks").optJSONObject(id);
            if (task == null) throw new IllegalArgumentException("Задача не найдена");
            String project = task.optString("projectId");
            edit.accept(task);
            if (!account.equals(task.optString("accountKey")) || !id.equals(task.optString("taskId"))
                    || !id.equals(task.optString("conversationId")) || !project.equals(task.optString("projectId")))
                throw new SecurityException("Идентичность задачи неизменна");
            try { task.put("updatedAt", System.currentTimeMillis()); }
            catch (org.json.JSONException bad) { throw new IllegalStateException(bad); }
            store.save(account, state);
        }
    }
    /** A process restart never silently resumes an operation whose outcome may be unknown. */
    public void recoverInterrupted(String account,String liveTask) {
        synchronized(UnifiedAgentStore.LOCK) {
            JSONObject state=store.read(account),tasks=state.optJSONObject("tasks");boolean changed=false;
            for(String id:UnifiedAgentStore.keys(tasks)) {
                JSONObject task=tasks.optJSONObject(id);
                if("RUNNING".equals(task.optString("state"))&&!id.equals(liveTask)) {
                    AgentController.put(task,"state","INTERRUPTED");changed=true;
                }
            }
            if(changed)store.save(account,state);
        }
    }
    public void delete(String account, String id) {
        synchronized (UnifiedAgentStore.LOCK) {
            JSONObject state = store.read(account); state.optJSONObject("tasks").remove(id); store.save(account, state);
        }
    }
}
