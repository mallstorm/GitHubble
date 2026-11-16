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
                if(java.util.Arrays.asList("RUNNING","BUILDING","EXECUTING","VERIFYING","DELIVERING").contains(task.optString("state"))&&!id.equals(liveTask)) {
                    AgentController.put(task,"state","INTERRUPTED");
                    AgentController.put(task,"recoveryNote","Операции не повторяются автоматически. Откройте панель задачи и сверьте CI/публикацию перед продолжением.");
                    JSONObject ci=task.optJSONObject("ci");
                    if(ci!=null&&"DISPATCH_PENDING".equals(ci.optString("state")))AgentController.put(ci,"state","UNKNOWN");
                    changed=true;
                }
            }
            if(changed)store.save(account,state);
        }
    }
    /** Invoked only by a physical user confirmation; audit previous budget instead of losing it. */
    public void authorizeContinuation(String account,String id) {
        update(account,id,t->{
            JSONObject budget=t.optJSONObject("budget");
            AgentController.put(budget,"roundWindowStart",budget.optInt("rounds"));
            AgentController.put(budget,"toolWindowStart",budget.optInt("tools"));
            AgentController.put(budget,"maxRounds",budget.optInt("rounds")+12);
            AgentController.put(budget,"maxTools",budget.optInt("tools")+100);
            AgentController.put(budget,"maxTokens",Math.max(budget.optLong("maxTokens",180000),budget.optLong("inputTokens")+budget.optLong("outputTokens")+180000));
            JSONObject ci=t.optJSONObject("ci");
            if(ci!=null){
                String state=ci.optString("state");
                if(!state.equals("UNKNOWN")&&!state.equals("DISPATCH_PENDING")){
                    JSONArray grants=t.optJSONArray("ciBudgetHistory");if(grants==null)grants=new JSONArray();
                    JSONArray recent=new JSONArray();for(int i=Math.max(0,grants.length()-19);i<grants.length();i++)recent.put(grants.opt(i));
                    recent.put(UnifiedAgentStore.copy(ci));AgentController.put(t,"ciBudgetHistory",recent);
                    ci.remove("decision");ci.remove("failure");ci.remove("failureKey");ci.remove("decidedRun");ci.remove("retryAt");
                    AgentController.put(ci,"retryRunId",ci.optLong("runId"));AgentController.put(ci,"retry",new JSONObject());
                }
            }
            AgentController.put(t,"state","READY");AgentController.put(t,"continuationApprovedAt",System.currentTimeMillis());
        });
    }
    public void delete(String account, String id) {
        synchronized (UnifiedAgentStore.LOCK) {
            JSONObject state = store.read(account); state.optJSONObject("tasks").remove(id); store.save(account, state);
        }
    }
}
